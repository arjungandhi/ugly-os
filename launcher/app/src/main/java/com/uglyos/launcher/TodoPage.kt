package com.uglyos.launcher

import android.content.Context
import android.os.FileObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.uglyos.common.theme.UglyTheme
import com.uglyos.common.todo.Task
import com.uglyos.common.todo.TodoFile
import com.uglyos.common.todo.TodoList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** A task paired with its line index in the file, so edits can target the right line. */
private data class IndexedTask(val index: Int, val task: Task)

/** What the todo page has to show once it's tried to load the file. */
private sealed interface TodoState {
    /** No monkey dir configured yet. */
    object NoDir : TodoState
    /** Monkey dir set, but we lack the all-files access needed to read it. */
    object NoAccess : TodoState
    /** Access granted, but the todo.txt file wasn't found. */
    object NotFound : TodoState
    /** Parsed tasks, already filtered and sorted for display. */
    data class Loaded(val tasks: List<IndexedTask>) : TodoState
}

/** Read and filter the todo.txt, resolving to the right display state. */
private fun loadTodoState(context: Context, filter: (Task) -> Boolean): TodoState {
    val todoFile = Settings.todoFile(context) ?: return TodoState.NoDir
    if (!Settings.hasStorageAccess()) return TodoState.NoAccess
    if (!todoFile.exists()) return TodoState.NotFound
    return try {
        val list = TodoList.parse(todoFile.readText())
        val items = list.tasks
            .mapIndexed { i, t -> IndexedTask(i, t) }
            .filter { filter(it.task) }
            .sortedWith(compareBy(TodoList.DISPLAY_ORDER) { it.task })
        TodoState.Loaded(items)
    } catch (e: Exception) {
        TodoState.NotFound
    }
}

/** Append a task to the todo.txt, parsed from a raw todo.txt [line]. */
private fun addTask(context: Context, line: String) {
    val file = Settings.todoFile(context) ?: return
    TodoFile(file).edit { it.add(line) }
}

/** Replace the line at [index]; a line that parses to nothing deletes it. */
private fun updateTask(context: Context, index: Int, line: String) {
    val file = Settings.todoFile(context) ?: return
    TodoFile(file).edit { list ->
        if (index in 0 until list.size) {
            val task = Task.parse(line)
            if (task == null) list.removeAt(index) else list.update(index, task)
        }
    }
}

/** Remove the line at [index] from the todo.txt. */
private fun deleteTask(context: Context, index: Int) {
    val file = Settings.todoFile(context) ?: return
    TodoFile(file).edit { if (index in 0 until it.size) it.removeAt(index) }
}

/** Mark the task at [index] done and move it out of todo.txt into done.txt. */
private fun completeTask(context: Context, index: Int) {
    val todo = Settings.todoFile(context) ?: return
    val done = Settings.doneFile(context) ?: return
    val list = TodoFile(todo).load()
    if (index !in 0 until list.size) return
    val task = list[index]
    val completed = if (task.completed) task else task.complete(LocalDate.now())
    TodoFile(done).edit { it.add(completed) }
    list.removeAt(index)
    TodoFile(todo).save(list)
}

/** Watch [dir] and call [onChange] whenever a file in it is written or renamed. */
private fun watchDir(dir: File, onChange: () -> Unit): FileObserver {
    // Syncthing writes a temp file then renames it in, so watch for the rename
    // (MOVED_TO) as well as direct writes (CLOSE_WRITE) and deletes.
    val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
        FileObserver.MOVED_FROM or FileObserver.DELETE or FileObserver.CREATE
    return object : FileObserver(dir, mask) {
        override fun onEvent(event: Int, path: String?) = onChange()
    }
}

/** A pending add (index null) or edit of an existing line, driving the editor sheet. */
private data class TaskEdit(val index: Int?, val initial: String)

/**
 * An interactive todo.txt list page. [filter] narrows which tasks appear so the
 * same component can back several pages (e.g. one excluding a context, one
 * showing only it). The file is read directly and watched with FileObserver, so
 * changes (from edits here or synced in by Syncthing) appear live; it also
 * reloads on resume. Tap a task's dot to complete it (archived to done.txt), tap
 * its text to edit or delete it, and use "add task" to append a new line.
 */
@Composable
fun TodoPage(title: String, filter: (Task) -> Boolean) {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    val scope = rememberCoroutineScope()
    val currentFilter by rememberUpdatedState(filter)
    var state by remember { mutableStateOf<TodoState>(TodoState.NoDir) }
    var editing by remember { mutableStateOf<TaskEdit?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        var observer: FileObserver? = null

        fun refresh() {
            state = loadTodoState(context, currentFilter)
            observer?.stopWatching()
            observer = Settings.todoFile(context)?.parentFile
                ?.takeIf { it.isDirectory }
                ?.let { dir -> watchDir(dir) { state = loadTodoState(context, currentFilter) } }
            observer?.startWatching()
        }

        val lifeObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(lifeObserver)
        refresh()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifeObserver)
            observer?.stopWatching()
        }
    }

    // Mutations run off the main thread; the FileObserver above picks up the
    // resulting file change and reloads the list.
    fun mutate(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { block() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp),
    ) {
        Text(
            text = title,
            color = colors.foreground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        when (val s = state) {
            TodoState.NoDir -> Hint("set the monkey dir in settings")
            TodoState.NoAccess -> Hint("grant all-files access in settings")
            TodoState.NotFound -> Hint("no todo.txt found in monkey dir")
            is TodoState.Loaded -> {
                AddRow(onClick = { editing = TaskEdit(index = null, initial = "") })
                if (s.tasks.isEmpty()) {
                    Hint("nothing here")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(s.tasks) { item ->
                            TaskRow(
                                task = item.task,
                                onComplete = { mutate { completeTask(context, item.index) } },
                                onEdit = { editing = TaskEdit(item.index, item.task.format()) },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { edit ->
        TaskSheet(
            edit = edit,
            onDismiss = { editing = null },
            onSave = { line ->
                val trimmed = line.trim()
                mutate {
                    if (edit.index == null) {
                        if (trimmed.isNotEmpty()) addTask(context, trimmed)
                    } else {
                        updateTask(context, edit.index, trimmed)
                    }
                }
                editing = null
            },
            onDelete = edit.index?.let { index ->
                { mutate { deleteTask(context, index) }; editing = null }
            },
        )
    }
}

/** A dimmed message for empty/unconfigured states. */
@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = UglyTheme.colors.mutedForeground,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
    )
}

/** A tappable "add task" affordance above the list. */
@Composable
private fun AddRow(onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "+",
            color = colors.accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "add task",
            color = colors.mutedForeground,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * One task line: a tappable completion dot, an optional priority badge, then the
 * description. Tapping the dot completes; tapping the text opens the editor.
 */
@Composable
private fun TaskRow(task: Task, onComplete: () -> Unit, onEdit: () -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(onClick = onComplete),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (task.completed) colors.success else colors.subtle)
            )
        }
        task.priority?.let {
            Text(
                text = "($it)",
                color = colors.accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = task.description,
            color = if (task.completed) colors.mutedForeground else colors.foreground,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            textDecoration = if (task.completed) TextDecoration.LineThrough else null,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        )
    }
}

/**
 * The add/edit sheet: one monospace field holding the raw todo.txt line, so
 * priority, `due:`, contexts and tags are all editable inline. Save commits;
 * an existing line also gets a [error]-colored delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskSheet(
    edit: TaskEdit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = UglyTheme.colors
    val sheetState = rememberModalBottomSheetState()
    val focusRequester = remember { FocusRequester() }
    var line by remember(edit) { mutableStateOf(edit.initial) }

    LaunchedEffect(edit) {
        kotlinx.coroutines.delay(100)
        runCatching { focusRequester.requestFocus() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = if (edit.index == null) "add task" else "edit task",
                color = colors.mutedForeground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceElevated)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                BasicTextField(
                    value = line,
                    onValueChange = { line = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = colors.foreground,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(line) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                ) { inner ->
                    if (line.isEmpty()) {
                        Text(
                            text = "(A) buy milk @errands due:2026-07-01",
                            color = colors.mutedForeground,
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    inner()
                }
            }
            SheetAction(label = "save", color = colors.accent, onClick = { onSave(line) })
            if (onDelete != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.subtle)
                )
                SheetAction(label = "delete", color = colors.error, onClick = onDelete)
            }
        }
    }
}

/** A dot + label action row inside the task sheet. */
@Composable
private fun SheetAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
