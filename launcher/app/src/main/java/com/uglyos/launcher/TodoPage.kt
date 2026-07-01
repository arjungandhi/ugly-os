package com.uglyos.launcher

import android.content.Context
import android.os.FileObserver
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.uglyos.common.theme.UglyTheme
import com.uglyos.common.todo.Task
import com.uglyos.common.todo.TodoList
import java.io.File

/** What the todo page has to show once it's tried to load the file. */
private sealed interface TodoState {
    /** No monkey dir configured yet. */
    object NoDir : TodoState
    /** Monkey dir set, but we lack the all-files access needed to read it. */
    object NoAccess : TodoState
    /** Access granted, but the todo.txt file wasn't found. */
    object NotFound : TodoState
    /** Parsed tasks, already filtered and sorted for display. */
    data class Loaded(val tasks: List<Task>) : TodoState
}

/** Read and filter the todo.txt, resolving to the right display state. */
private fun loadTodoState(context: Context, filter: (Task) -> Boolean): TodoState {
    val todoFile = Settings.todoFile(context) ?: return TodoState.NoDir
    if (!Settings.hasStorageAccess()) return TodoState.NoAccess
    if (!todoFile.exists()) return TodoState.NotFound
    return try {
        val tasks = TodoList.parse(todoFile.readText()).sortedForDisplay().filter(filter)
        TodoState.Loaded(tasks)
    } catch (e: Exception) {
        TodoState.NotFound
    }
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

/**
 * A read-only todo.txt list page. [filter] narrows which tasks appear so the
 * same component can back several pages (e.g. one excluding a context, one
 * showing only it). The file is read directly and watched with FileObserver, so
 * changes synced in by Syncthing appear live; it also reloads on resume.
 */
@Composable
fun TodoPage(title: String, filter: (Task) -> Boolean) {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    val currentFilter by rememberUpdatedState(filter)
    var state by remember { mutableStateOf<TodoState>(TodoState.NoDir) }
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
            is TodoState.Loaded ->
                if (s.tasks.isEmpty()) {
                    Hint("nothing here")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(s.tasks) { TaskRow(it) }
                    }
                }
        }
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

/** One task line: optional priority badge, then the description. */
@Composable
private fun TaskRow(task: Task) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
        )
    }
}
