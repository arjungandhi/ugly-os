package com.uglyos.launcher

import android.content.Context
import android.os.FileObserver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** A task paired with its line index in the file, so edits can target the right line. */
private data class IndexedTask(val index: Int, val task: Task)

/** What the todo page has to show once it's tried to load the file. */
private sealed interface TodoState {
    /** No todo dir configured yet. */
    object NoDir : TodoState
    /** Todo dir set, but we lack the all-files access needed to read it. */
    object NoAccess : TodoState
    /** Access granted, but the todo.txt file wasn't found. */
    object NotFound : TodoState
    /** Every task, keyed to its file line and sorted for display. Modes filter
     * this in memory, so a mode switch never touches disk. */
    data class Loaded(val tasks: List<IndexedTask>) : TodoState
}

/** Read the whole todo.txt, sorted for display, resolving to the right state. */
private fun loadTodoState(context: Context): TodoState {
    val todoFile = Settings.todoFile(context) ?: return TodoState.NoDir
    if (!Settings.hasStorageAccess()) return TodoState.NoAccess
    if (!todoFile.exists()) return TodoState.NotFound
    return try {
        val list = TodoList.parse(todoFile.readText())
        val items = list.tasks
            .mapIndexed { i, t -> IndexedTask(i, t) }
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

/**
 * A task line with the current [mode]'s hidden `+project`/`@context` appended
 * unless already present, so a task added inside a filtered mode lands in that
 * mode. These tags are hidden in that view (the switcher already names them), so
 * they're added silently rather than pre-filled in the editor. Inverted modes
 * scope to nothing, so they append nothing.
 */
private fun withScoping(line: String, mode: TodoMode): String {
    val task = Task.parse(line) ?: return line
    var result = line
    mode.hiddenProject?.let { if (it !in task.projects) result = "$result +$it" }
    mode.hiddenContext?.let { if (it !in task.contexts) result = "$result @$it" }
    return result
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

/** A pending add (index null) or edit of an existing line, driving the editor sheet. */
private data class TaskEdit(val index: Int?, val task: Task)

/** A pending add ([index] null) or edit of the mode at [index], driving the mode sheet. */
private data class ModeEdit(val index: Int?, val mode: TodoMode)

/**
 * An interactive todo.txt list page backed by one file, viewed through the user's
 * [TodoModeStore] modes. A fresh install is seeded with an "all" mode that shows
 * everything, but it's editable like any other; each mode narrows to a
 * `+project`/`@context` (or its inverse) via [TodoMode.matches]. A scoped mode
 * hides its own tag on every row (the footer already names it) and
 * auto-appends it to tasks added there. The active mode lives in the footer; tapping
 * it opens the [ModeMenu] to switch, add, edit, or delete modes. The file is read
 * whole and watched with FileObserver, so changes (from edits here or synced in by
 * Syncthing) appear live; it also reloads on resume. Switching modes just re-filters
 * the loaded list in memory, and the choice persists across restarts. Tap a task's
 * dot to complete it (archived to done.txt), tap its text to edit or delete it, use
 * "add task" to append.
 */
@Composable
fun TodoPage() {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    val scope = rememberCoroutineScope()
    var modes by remember { mutableStateOf(TodoModeStore.modes(context)) }
    var selectedMode by remember { mutableStateOf(TodoModeStore.selected(context)) }
    // The mode list can be empty if the user deleted every mode; fall back to the
    // unfiltered "all" so the page still shows every task.
    val mode = modes.getOrElse(selectedMode.coerceIn(0, maxOf(0, modes.size - 1))) { TodoModeStore.DEFAULT }
    val hiddenContext = mode.hiddenContext
    val hiddenProject = mode.hiddenProject
    var state by remember { mutableStateOf<TodoState>(TodoState.NoDir) }
    var editing by remember { mutableStateOf<TaskEdit?>(null) }
    var editingMode by remember { mutableStateOf<ModeEdit?>(null) }
    var showModeMenu by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Set and persist the selected mode. Callers pass an already-valid index.
    fun select(index: Int) {
        selectedMode = index
        TodoModeStore.setSelected(context, index)
    }

    // Re-read modes after an add/edit, re-clamping the selection in case it moved.
    fun reloadModes() {
        modes = TodoModeStore.modes(context)
        select(selectedMode.coerceIn(0, maxOf(0, modes.size - 1)))
    }

    // The file is loaded whole and unfiltered; switching modes just re-filters
    // this in memory below, so a mode switch never reads disk or flashes tasks
    // from the previous mode.
    DisposableEffect(lifecycleOwner) {
        var observer: FileObserver? = null

        fun refresh() {
            state = loadTodoState(context)
            observer?.stopWatching()
            observer = Settings.todoFile(context)?.parentFile
                ?.takeIf { it.isDirectory }
                ?.let { dir -> watchDir(dir) { state = loadTodoState(context) } }
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

    // Mutations run off the main thread, then reload the list directly. We don't
    // wait on the FileObserver for our own edits: it watches emulated external
    // storage (where inotify events are dropped) and the tmp-file+rename save
    // emits a burst of events, so a local add would only show up intermittently.
    // The observer stays for external (Syncthing) changes.
    fun mutate(block: () -> Unit) = scope.launch {
        state = withContext(Dispatchers.IO) {
            block()
            loadTodoState(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 40.dp),
    ) {
        Text(
            text = "todo",
            color = colors.foreground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        when (val s = state) {
            TodoState.NoDir -> Hint("set the todo dir in settings", highlight = "settings")
            TodoState.NoAccess -> Hint("grant all-files access in settings", highlight = "settings")
            TodoState.NotFound -> Hint("no todo.txt found in todo dir", highlight = "todo dir")
            is TodoState.Loaded -> {
                val visible = s.tasks.filter { mode.matches(it.task) }
                if (visible.isEmpty()) {
                    Hint("no tasks")
                    Spacer(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(visible) { item ->
                            TaskRow(
                                task = item.task,
                                hiddenContext = hiddenContext,
                                hiddenProject = hiddenProject,
                                onComplete = { mutate { completeTask(context, item.index) } },
                                onEdit = { editing = TaskEdit(item.index, item.task) },
                            )
                        }
                    }
                }
                // The footer — current mode on the left, "add task" on the right —
                // is pinned to the bottom, in thumb reach, split from the scrolling
                // list by a hairline.
                Hairline()
                FooterBar(
                    mode = mode.label,
                    onOpenModes = { showModeMenu = true },
                    onAddTask = { editing = TaskEdit(index = null, task = Task()) },
                )
            }
        }
    }

    editing?.let { edit ->
        TaskSheet(
            edit = edit,
            hiddenContext = hiddenContext,
            hiddenProject = hiddenProject,
            onDismiss = { editing = null },
            onSave = { task ->
                // Re-attach the mode's hidden project/context (kept out of the
                // editor) so a task added here stays in this mode. A now-empty
                // description means the task was cleared: skip the add, or delete
                // the existing line.
                val scoped = withScoping(task.format(), mode)
                mutate {
                    when {
                        edit.index == null -> if (task.description.isNotBlank()) addTask(context, scoped)
                        task.description.isBlank() -> deleteTask(context, edit.index)
                        else -> updateTask(context, edit.index, scoped)
                    }
                }
                editing = null
            },
            onDelete = edit.index?.let { index ->
                { mutate { deleteTask(context, index) }; editing = null }
            },
        )
    }

    if (showModeMenu) {
        ModeMenu(
            modes = modes,
            selected = selectedMode,
            onSelect = { i -> select(i); showModeMenu = false },
            onEdit = { i -> showModeMenu = false; editingMode = ModeEdit(i, modes[i]) },
            onAdd = { showModeMenu = false; editingMode = ModeEdit(index = null, mode = TodoMode("")) },
            onDismiss = { showModeMenu = false },
        )
    }

    editingMode?.let { edit ->
        ModeSheet(
            edit = edit,
            onDismiss = { editingMode = null },
            onSave = { newMode ->
                if (edit.index == null) {
                    // A freshly added mode is appended; switch to it so the view
                    // reflects the mode you just made.
                    TodoModeStore.add(context, newMode)
                    modes = TodoModeStore.modes(context)
                    select(modes.lastIndex.coerceAtLeast(0))
                } else {
                    TodoModeStore.update(context, edit.index, newMode)
                    reloadModes()
                }
                editingMode = null
            },
            onDelete = edit.index?.let { index ->
                {
                    TodoModeStore.removeAt(context, index)
                    modes = TodoModeStore.modes(context)
                    // Keep the view steady: deleting a mode before the selected one
                    // shifts its index down; deleting the selected one leaves the
                    // index pointing at the next mode (clamped if it was the last).
                    select(
                        when {
                            selectedMode > index -> selectedMode - 1
                            else -> selectedMode
                        }.coerceIn(0, maxOf(0, modes.size - 1))
                    )
                    editingMode = null
                }
            },
        )
    }
}

/**
 * The pinned footer: "add task" on the left (its "+" in the [DOT_GUTTER], lined up
 * under the task dots) and the current [mode] in the right corner. Tapping the
 * mode opens the [ModeMenu] to switch or manage modes.
 */
@Composable
private fun FooterBar(mode: String, onOpenModes: () -> Unit, onAddTask: () -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onAddTask)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(DOT_GUTTER), contentAlignment = Alignment.Center) {
                Text(
                    text = "+",
                    color = colors.accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = "add task",
                color = colors.mutedForeground,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenModes)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = mode,
                color = colors.foreground,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
            )
            // A quiet disclosure caret marks this as a picker (tap opens the menu).
            DisclosureCaret(colors.subtle)
        }
    }
}

/**
 * A small hand-drawn disclosure caret ("⌄") marking the mode control as a picker.
 * Stroked by hand rather than set as a font glyph so it centers cleanly and renders
 * identically everywhere (no emoji fallback), like the app's other glyphs.
 */
@Composable
private fun DisclosureCaret(color: Color) {
    Canvas(
        Modifier
            .size(width = 10.dp, height = 6.dp)
            .offset(y = 1.dp),
    ) {
        val stroke = 1.5.dp.toPx()
        // Inset by half the stroke so the round caps aren't clipped at the edges.
        val inset = stroke / 2f
        val path = Path().apply {
            moveTo(inset, inset)
            lineTo(size.width / 2f, size.height - inset)
            lineTo(size.width - inset, inset)
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * The mode manager: a bottom sheet listing every mode with its selection marker.
 * Tap a row to switch to it (and close); long-press a row to edit it; "add mode"
 * makes a new one. Every mode is editable — the seeded "all" is just an ordinary
 * mode. A hairline splits the switchable list from the "add mode" action, echoing
 * the page footer's list/action divider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeMenu(
    modes: List<TodoMode>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = UglyTheme.colors
    val sheetState = rememberModalBottomSheetState()
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "modes",
                color = colors.mutedForeground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            modes.forEachIndexed { i, mode ->
                ModeMenuRow(
                    label = mode.label,
                    active = i == selected,
                    onSelect = { onSelect(i) },
                    onEdit = { onEdit(i) },
                )
            }
            Hairline(Modifier.padding(vertical = 8.dp))
            AddRow(label = "add mode", onClick = onAdd)
        }
    }
}

/**
 * One row in the [ModeMenu]: a selection marker (filled `accent` when active, a
 * hollow ring otherwise) and the label. The whole row is one target — tap to
 * switch to this mode, long-press to edit it — so a row reads as a single
 * unambiguous control rather than two competing ones.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModeMenuRow(label: String, active: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onSelect, onLongClick = onEdit)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(DOT_GUTTER), contentAlignment = Alignment.Center) {
            if (active) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colors.accent))
            } else {
                Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, colors.subtle, CircleShape))
            }
        }
        Text(
            text = label,
            color = if (active) colors.foreground else colors.mutedForeground,
            fontSize = 16.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * One task line: a tappable completion dot in the gutter, the description, and a
 * due badge on the right. Aurora carries the state — a semantic priority badge
 * folded into the text (`(A)` error, `(B)` warning), quiet `mutedForeground`
 * `@context`/`+project` tokens, a `success` dot for done, and an urgency-colored
 * due date. Tapping the dot completes; tapping the text opens the editor.
 */
@Composable
private fun TaskRow(
    task: Task,
    hiddenContext: String?,
    hiddenProject: String?,
    onComplete: () -> Unit,
    onEdit: () -> Unit,
) {
    // Top-aligned so a task that wraps to several lines keeps its dot and due
    // badge on the first line, rather than floating them to the block's center.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CompletionDot(completed = task.completed, onClick = onComplete)
        Text(
            text = taskAnnotated(task, hiddenContext, hiddenProject),
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.Monospace,
            textDecoration = if (task.completed) TextDecoration.LineThrough else null,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        )
        DueBadge(task)
    }
}

/**
 * A hollow ring while open, a filled [success] dot once done — the tap target.
 * Sized to [DOT_GUTTER] so it aligns to the first text line in a top-aligned row.
 */
@Composable
private fun CompletionDot(completed: Boolean, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .size(DOT_GUTTER)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (completed) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(colors.success))
        } else {
            Box(Modifier.size(14.dp).clip(CircleShape).border(1.5.dp, colors.subtle, CircleShape))
        }
    }
}

/** The one word of urgency for a due date, or nothing. Suppressed on done tasks. */
@Composable
private fun DueBadge(task: Task) {
    if (task.completed) return
    val info = dueInfo(task.due) ?: return
    Text(
        text = info.label,
        color = info.color,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontFamily = FontFamily.Monospace,
    )
}

/** A due date rendered as a terse label plus the aurora color that grades it. */
private data class DueInfo(val label: String, val color: Color)

@Composable
private fun dueInfo(due: LocalDate?): DueInfo? {
    if (due == null) return null
    val colors = UglyTheme.colors
    val days = ChronoUnit.DAYS.between(LocalDate.now(), due)
    return when {
        days < 0 -> DueInfo("overdue", colors.error)
        days == 0L -> DueInfo("today", colors.warning)
        days == 1L -> DueInfo("tomorrow", colors.warning)
        days <= 6 -> DueInfo(
            due.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.US).lowercase(),
            colors.mutedForeground,
        )
        else -> DueInfo(MONTH_DAY.format(due).lowercase(), colors.mutedForeground)
    }
}

private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** `(A)` error, `(B)` warning, anything lower a quiet secondary accent. */
private fun priorityColor(priority: Char, colors: com.uglyos.common.theme.ThemeColors): Color =
    when (priority) {
        'A' -> colors.error
        'B' -> colors.warning
        else -> colors.accentMuted
    }

/**
 * The task text as shown: a leading semantic priority badge (on open tasks), the
 * description with its `due:`/`key:value` metadata stripped out (surfaced as the
 * due badge instead), and `@context`/`+project` tokens dimmed to `mutedForeground`
 * so they read as quiet tags, not part of the sentence. User casing is untouched.
 */
@Composable
private fun taskAnnotated(task: Task, hiddenContext: String?, hiddenProject: String?): AnnotatedString {
    val colors = UglyTheme.colors
    val done = task.completed
    val base = if (done) colors.mutedForeground else colors.foreground
    return buildAnnotatedString {
        if (!done) task.priority?.let { p ->
            withStyle(SpanStyle(color = priorityColor(p, colors), fontWeight = FontWeight.Bold)) {
                append("($p) ")
            }
        }
        val text = task.displayText(
            hideContexts = setOfNotNull(hiddenContext),
            hideProjects = setOfNotNull(hiddenProject),
        )
        text.split(" ").forEachIndexed { i, word ->
            if (i > 0) append(" ")
            val color = if (word.startsWith("@") || word.startsWith("+")) colors.mutedForeground else base
            withStyle(SpanStyle(color = color)) { append(word) }
        }
    }
}

/**
 * The add/edit sheet. Rather than make you type todo.txt syntax, it breaks the
 * task into parts: a plain description field (where `@context`/`+project` still
 * live as words), tap-to-set priority pips, and due-date chips backed by a date
 * picker. On save it reassembles the `(A) … due:…` line for you. An existing
 * task also gets an [error]-colored delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskSheet(
    edit: TaskEdit,
    hiddenContext: String?,
    hiddenProject: String?,
    onDismiss: () -> Unit,
    onSave: (Task) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = UglyTheme.colors
    val sheetState = rememberModalBottomSheetState()
    val focusRequester = remember { FocusRequester() }

    // Edit the parts we surface as controls; everything else on the task
    // (completion, dates, other tags) is carried through untouched on save. The
    // mode's scoping project/context is hidden here and re-attached by the caller;
    // due lives in its own control, so it's stripped from the editable text.
    val original = edit.task
    var description by remember(edit) {
        mutableStateOf(
            original.editableText(
                hideContexts = setOfNotNull(hiddenContext),
                hideProjects = setOfNotNull(hiddenProject),
            )
        )
    }
    var priority by remember(edit) { mutableStateOf(original.priority) }
    var due by remember(edit) { mutableStateOf(original.due) }
    var showPicker by remember(edit) { mutableStateOf(false) }

    // Rebuild the task from the original, overriding only what the sheet edits.
    // The typed text is re-cleaned so a hand-typed due: can't duplicate the one
    // from the due control.
    fun assemble(): Task {
        val body = Task(description = description.trim()).editableText()
        val withDue = when {
            due == null -> body
            body.isEmpty() -> "due:$due"
            else -> "$body due:$due"
        }
        return original.copy(priority = priority, description = withDue)
    }

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
            SheetField(
                value = description,
                onValueChange = { description = it },
                placeholder = "what needs doing",
                fieldModifier = Modifier.focusRequester(focusRequester),
                singleLine = false,
                keyboardActions = KeyboardActions(onDone = { onSave(assemble()) }),
            )

            PrioritySection(
                selected = priority,
                extra = original.priority?.takeIf { it !in 'A'..'C' },
            ) { priority = it }
            DueSection(
                due = due,
                onPick = { due = it },
                onOpenPicker = { showPicker = true },
            )

            SheetActions(
                onSave = { onSave(assemble()) },
                onDelete = onDelete,
                resetKey = edit,
            )
        }
    }

    if (showPicker) {
        DuePickerDialog(
            initial = due,
            onDismiss = { showPicker = false },
            onConfirm = { due = it; showPicker = false },
        )
    }
}

/**
 * The add/edit sheet for a custom mode: a name, an optional `+project` and
 * `@context` to filter on (either or both), and a match/exclude toggle that
 * inverts the filter. Saving needs a name; an existing mode also gets a delete.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModeSheet(
    edit: ModeEdit,
    onDismiss: () -> Unit,
    onSave: (TodoMode) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = UglyTheme.colors
    val sheetState = rememberModalBottomSheetState()
    val focusRequester = remember { FocusRequester() }

    var label by remember(edit) { mutableStateOf(edit.mode.label) }
    var project by remember(edit) { mutableStateOf(edit.mode.project ?: "") }
    var context by remember(edit) { mutableStateOf(edit.mode.context ?: "") }
    var invert by remember(edit) { mutableStateOf(edit.mode.invert) }

    // Accept a tag with or without its sigil and keep only the first token — a
    // tag has no spaces. Blank means "no filter on this axis".
    fun clean(raw: String): String? =
        raw.trim().trimStart('+', '@').substringBefore(' ').ifBlank { null }

    fun assemble() = TodoMode(
        label = label.trim(),
        project = clean(project),
        context = clean(context),
        invert = invert,
    )

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
                text = if (edit.index == null) "add mode" else "edit mode",
                color = colors.mutedForeground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
            )
            SheetField(
                value = label,
                onValueChange = { label = it },
                placeholder = "name",
                fieldModifier = Modifier.focusRequester(focusRequester),
            )
            SheetField(value = project, onValueChange = { project = it }, placeholder = "+project")
            SheetField(value = context, onValueChange = { context = it }, placeholder = "@context")

            // exclude is warning-colored: it subtracts from the view, not adds.
            LabeledSection("filter") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("match", selected = !invert, color = colors.accent) { invert = false }
                    Chip("exclude", selected = invert, color = colors.warning) { invert = true }
                }
            }

            SheetActions(
                saveEnabled = label.isNotBlank(),
                onSave = { if (label.isNotBlank()) onSave(assemble()) },
                onDelete = onDelete,
                resetKey = edit,
            )
        }
    }
}

/** A text field styled for the sheets: a rounded elevated well with a muted
 * placeholder. [fieldModifier] rides the inner field (e.g. a focus request). */
@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    fieldModifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceElevated)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = colors.foreground,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = keyboardActions,
            modifier = fieldModifier.fillMaxWidth(),
        ) { inner ->
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = colors.mutedForeground,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            inner()
        }
    }
}

/**
 * Tap-to-set priority: `none` plus `a`/`b`/`c`, each in its aurora color. An
 * [extra] priority (a `(D)`–`(Z)` set by another tool) is offered too, so
 * touching the control can't strand it as unreachable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrioritySection(selected: Char?, extra: Char?, onSelect: (Char?) -> Unit) {
    val colors = UglyTheme.colors
    LabeledSection("priority") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf<Char?>(null, 'A', 'B', 'C') + listOfNotNull(extra)
            options.forEach { p ->
                Chip(
                    label = p?.lowercaseChar()?.toString() ?: "none",
                    selected = selected == p,
                    color = p?.let { priorityColor(it, colors) } ?: colors.mutedForeground,
                    onClick = { onSelect(p) },
                )
            }
        }
    }
}

/**
 * Tap-to-set due date: `none`, a few relative presets (today/tomorrow/…), and a
 * `pick` chip that opens the calendar — which also shows the chosen date when
 * it's not one of the presets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DueSection(due: LocalDate?, onPick: (LocalDate?) -> Unit, onOpenPicker: () -> Unit) {
    val colors = UglyTheme.colors
    val today = remember { LocalDate.now() }
    val presets = remember(today) {
        listOf(
            "today" to today,
            "tomorrow" to today.plusDays(1),
            "weekend" to today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)),
            "next week" to today.plusWeeks(1),
        )
    }
    // Only the first matching preset lights up, so a date two presets share
    // (today == weekend on a Saturday) doesn't highlight both chips.
    val matchIndex = presets.indexOfFirst { it.second == due }
    val isCustom = due != null && matchIndex < 0

    LabeledSection("due") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("none", selected = due == null, color = colors.mutedForeground) { onPick(null) }
            presets.forEachIndexed { i, (label, date) ->
                Chip(label, selected = i == matchIndex, color = colors.accent) { onPick(date) }
            }
            Chip(
                label = if (isCustom) MONTH_DAY.format(due).lowercase() else "pick",
                selected = isCustom,
                color = colors.accent,
                onClick = onOpenPicker,
            )
        }
    }
}

/** A micro-label signpost over a control, echoing the section labels elsewhere. */
@Composable
private fun LabeledSection(label: String, content: @Composable () -> Unit) {
    val colors = UglyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = label.uppercase(),
            color = colors.mutedForeground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
        content()
    }
}

/** A small selectable pill: [color] tint + border when [selected], else quiet. */
@Composable
private fun Chip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) color.copy(alpha = 0.18f) else colors.surfaceElevated)
            .border(1.dp, if (selected) color else colors.subtle, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) color else colors.mutedForeground,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The calendar behind the `pick` chip. Drawn by hand to match the home calendar
 * card rather than dropping in Material's picker: an accent bullet + tracked
 * `MONTH YEAR` header with prev/next arrows, uppercase weekday initials, and
 * circular day cells — today outlined in `subtle`, the current selection filled
 * `accent`. Tapping a day picks it; tapping outside dismisses.
 */
@Composable
private fun DuePickerDialog(initial: LocalDate?, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val colors = UglyTheme.colors
    val today = remember { LocalDate.now() }
    val weekStart = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    var month by remember { mutableStateOf(YearMonth.from(initial ?: today)) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = MONTH_YEAR.format(month).uppercase(Locale.getDefault()),
                    color = colors.foreground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                MonthArrow("‹") { month = month.minusMonths(1) }
                Spacer(Modifier.width(4.dp))
                MonthArrow("›") { month = month.plusMonths(1) }
            }
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    Text(
                        text = weekStart.plus(i.toLong())
                            .getDisplayName(JavaTextStyle.NARROW, Locale.getDefault())
                            .uppercase(Locale.getDefault()),
                        color = colors.mutedForeground,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val lead = (month.atDay(1).dayOfWeek.value - weekStart.value + 7) % 7
            val cells = buildList<LocalDate?> {
                repeat(lead) { add(null) }
                for (d in 1..month.lengthOfMonth()) add(month.atDay(d))
                while (size % 7 != 0) add(null)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            DayPickCell(
                                day = day,
                                isToday = day == today,
                                isSelected = day != null && day == initial,
                                onClick = { day?.let(onConfirm) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

/** A round, tappable month-navigation chevron in the picker header. */
@Composable
private fun MonthArrow(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = UglyTheme.colors.mutedForeground,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** One day in the picker grid: empty for padding, else a circled, tappable day. */
@Composable
private fun DayPickCell(
    day: LocalDate?,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = UglyTheme.colors
    Box(
        modifier = modifier
            .height(40.dp)
            .then(if (day != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (day == null) return@Box
        val circle = Modifier.size(36.dp).clip(CircleShape).let {
            when {
                isSelected -> it.background(colors.accent)
                isToday -> it.border(1.dp, colors.subtle, CircleShape)
                else -> it
            }
        }
        Box(circle, contentAlignment = Alignment.Center) {
            Text(
                text = day.dayOfMonth.toString(),
                color = if (isSelected) colors.background else colors.foreground,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/**
 * A sheet's pinned action row, echoing the page footer's own left/right split: the
 * quiet, tap-to-arm [DeleteAction] on the left (dropped when there's nothing to
 * delete yet) and the primary save on the right. [resetKey] disarms delete whenever
 * the edited item changes.
 */
@Composable
private fun SheetActions(
    saveEnabled: Boolean = true,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    resetKey: Any?,
) {
    // Children align on their text baselines, not their centers, so the smaller
    // "delete" and the bigger "save" sit on one line despite the size difference.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (onDelete != null) {
            DeleteAction(onDelete = onDelete, resetKey = resetKey, modifier = Modifier.alignByBaseline())
        } else {
            Spacer(Modifier.width(1.dp))
        }
        SaveAction(enabled = saveEnabled, onClick = onSave, modifier = Modifier.alignByBaseline())
    }
}

