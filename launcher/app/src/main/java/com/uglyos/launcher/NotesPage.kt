package com.uglyos.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.FileObserver
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.uglyos.common.notes.Note
import com.uglyos.common.notes.NotesDir
import com.uglyos.common.theme.UglyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the notes page has to show once it's tried to read the dir. */
private sealed interface NotesState {
    /** No notes dir configured yet. */
    object NoDir : NotesState
    /** Notes dir set, but we lack the all-files access needed to read it. */
    object NoAccess : NotesState
    /** Access granted, dir readable, but it holds no notes yet. */
    object Empty : NotesState
    /** Every note in the dir, newest-modified first. Search filters this in memory. */
    data class Loaded(val notes: List<Note>) : NotesState
}

/** List the notes dir, resolving to the right state. */
private fun loadNotesState(context: Context): NotesState {
    val dir = Settings.notesDir(context) ?: return NotesState.NoDir
    if (!Settings.hasStorageAccess()) return NotesState.NoAccess
    val notes = NotesDir(dir).list()
    return if (notes.isEmpty()) NotesState.Empty else NotesState.Loaded(notes)
}

/** A pending new note ([note] null) or an edit of an existing one, driving the editor. */
private data class NoteEdit(val note: Note?)

/** The first non-blank line of a note's body, for the row preview. Blank if none. */
private fun Note.preview(): String =
    body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/**
 * A markdown notes page backed by a directory of `<title>.md` files. The dir is
 * listed newest-first and watched with FileObserver, so changes (from edits here
 * or synced in by Syncthing) appear live; it also reloads on resume. A search
 * field narrows the list by title or body. Tapping a note opens the full-screen
 * editor on it; "new note" opens a blank one. Saves and deletes go through
 * [NotesDir], run off the main thread, then reload the list.
 */
@Composable
fun NotesPage() {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<NotesState>(NotesState.NoDir) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<NoteEdit?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Reads run off the main thread: unlike the single small todo.txt, a notes dir
    // can hold many/large files and loadNotesState reads each one whole (search
    // needs the bodies in memory), so a synchronous load would jank the ui.
    fun reload() = scope.launch {
        state = withContext(Dispatchers.IO) { loadNotesState(context) }
    }

    DisposableEffect(lifecycleOwner) {
        var observer: FileObserver? = null

        fun refresh() {
            observer?.stopWatching()
            observer = Settings.notesDir(context)
                ?.takeIf { it.isDirectory }
                ?.let { dir -> watchDir(dir) { reload() } }
            observer?.startWatching()
            reload()
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
    // emits a burst of events, so a local edit would only show up intermittently.
    // The observer stays for external (Syncthing) changes.
    fun mutate(block: () -> Unit) = scope.launch {
        state = withContext(Dispatchers.IO) {
            block()
            loadNotesState(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 40.dp),
    ) {
        Text(
            text = "notes",
            color = colors.foreground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        when (val s = state) {
            NotesState.NoDir -> Hint("set the notes dir in settings", highlight = "settings")
            NotesState.NoAccess -> Hint("grant all-files access in settings", highlight = "settings")
            NotesState.Empty -> {
                Hint("no notes")
                Spacer(Modifier.weight(1f))
                // The "new note" action still shows on an empty dir — otherwise a
                // fresh notes dir would be a dead end with no way to make the first.
                Hairline()
                AddRow(label = "new note", onClick = { editing = NoteEdit(null) })
            }
            is NotesState.Loaded -> {
                val q = query.trim()
                val visible =
                    if (q.isEmpty()) {
                        s.notes
                    } else {
                        s.notes.filter {
                            it.title.contains(q, ignoreCase = true) ||
                                it.body.contains(q, ignoreCase = true)
                        }
                    }
                SearchField(value = query, onValueChange = { query = it })
                Spacer(Modifier.height(16.dp))
                if (visible.isEmpty()) {
                    Hint("no matches")
                    Spacer(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(visible) { note ->
                            NoteRow(note = note, onOpen = { editing = NoteEdit(note) })
                        }
                    }
                }
                // The "new note" action is pinned to the bottom, in thumb reach,
                // split from the scrolling list by a hairline.
                Hairline()
                AddRow(label = "new note", onClick = { editing = NoteEdit(null) })
            }
        }
    }

    editing?.let { edit ->
        NoteEditor(
            edit = edit,
            onDismiss = { editing = null },
            onSave = { title, body ->
                mutate {
                    val dir = Settings.notesDir(context) ?: return@mutate
                    val note = edit.note
                    when {
                        // Nothing left in it: discard a brand-new note, or delete an
                        // existing one emptied out (mirrors clearing a todo task).
                        title.isBlank() && body.isBlank() -> if (note != null) NotesDir(dir).delete(note)
                        // A cleared title on an existing note keeps its filename
                        // rather than silently renaming the note to "untitled".
                        else -> {
                            val finalTitle = if (title.isBlank() && note != null) note.title else title
                            NotesDir(dir).save(note, finalTitle, body)
                        }
                    }
                }
                editing = null
            },
            // Only an already-saved note has a file to hand off. Persist the current
            // edits first (so the external editor sees them), then open its file; a
            // cleared title keeps the note's own name rather than becoming "untitled".
            onOpenExternal = edit.note?.let { note ->
                { title, body ->
                    scope.launch {
                        val dir = Settings.notesDir(context)
                        if (dir == null) {
                            editing = null
                            return@launch
                        }
                        val saved = withContext(Dispatchers.IO) {
                            val finalTitle = if (title.isBlank()) note.title else title
                            NotesDir(dir).save(note, finalTitle, body)
                        }
                        val opened = openNoteExternally(context, File(dir, saved.title + ".md"))
                        state = withContext(Dispatchers.IO) { loadNotesState(context) }
                        if (opened) editing = null
                    }
                }
            },
            onDelete = edit.note?.let { note ->
                {
                    mutate {
                        Settings.notesDir(context)?.let { NotesDir(it).delete(note) }
                    }
                    editing = null
                }
            },
        )
    }
}

/**
 * Hand [file] to a real editor via an ACTION_EDIT chooser, granting temporary
 * read/write on a FileProvider content uri so the picked app edits the actual note
 * (its changes sync back and reload here). Returns whether an editor took it; shows
 * a quiet toast and returns false when nothing can handle it.
 */
private fun openNoteExternally(context: Context, file: File): Boolean {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    } catch (e: IllegalArgumentException) {
        return false
    }
    val edit = Intent(Intent.ACTION_EDIT).apply {
        setDataAndType(uri, "text/plain")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    return try {
        context.startActivity(Intent.createChooser(edit, "open in"))
        true
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "no editor app found", Toast.LENGTH_SHORT).show()
        false
    }
}

/**
 * A slim search well over the note list — its own quiet, focusable field rather
 * than the sheet's tall one, so it reads as a filter, not a note being written.
 * Filters by title or body as you type; empty means show everything.
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.foreground,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        ) { inner ->
            if (value.isEmpty()) {
                Text(
                    text = "search",
                    color = colors.mutedForeground,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            inner()
        }
    }
}

/**
 * One note row: a dot in the [DOT_GUTTER], the title, and a muted single-line
 * preview of the first non-blank body line. The whole row taps into the editor.
 * Top-aligned so the dot rides the title line when the block is two lines tall.
 */
@Composable
private fun NoteRow(note: Note, onOpen: () -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.size(DOT_GUTTER), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, colors.subtle, CircleShape))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = note.title,
                color = colors.foreground,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val preview = note.preview()
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    color = colors.mutedForeground,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The full-screen note editor. A near-full-height [ModalBottomSheet] — the same
 * sheet the todo editor uses, so it isolates the home pager's swipes and insets
 * itself past the nav bar and keyboard for free (a raw Dialog does neither). A
 * single-line title field (the filename) sits over a large scrollable body field;
 * a pinned action row echoes the todo sheet — a tap-to-arm [DeleteAction] on the
 * left (dropped for a brand-new note), a muted "open in…" handoff, and the primary
 * bold `accent` save on the right. Save hands the raw title and body back to the
 * caller, which persists via [NotesDir].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(
    edit: NoteEdit,
    onDismiss: () -> Unit,
    onSave: (title: String, body: String) -> Unit,
    onOpenExternal: ((title: String, body: String) -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val colors = UglyTheme.colors
    val focusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var title by remember(edit) { mutableStateOf(edit.note?.title ?: "") }
    var body by remember(edit) { mutableStateOf(edit.note?.body ?: "") }

    // A brand-new blank note lands with the cursor in the title; an existing one
    // opens quietly so a stray keystroke doesn't rename it.
    LaunchedEffect(edit) {
        if (edit.note == null) {
            kotlinx.coroutines.delay(100)
            runCatching { focusRequester.requestFocus() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.background,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The sheet fills the screen, so clear the status bar up top and the
                // keyboard down low, keeping the title and the action row in view.
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 16.dp)
                .imePadding(),
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.foreground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            ) { inner ->
                if (title.isEmpty()) {
                    Text(
                        text = "title",
                        color = colors.mutedForeground,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                inner()
            }
            Hairline(Modifier.padding(vertical = 16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicTextField(
                    value = body,
                    onValueChange = { body = it },
                    textStyle = TextStyle(
                        color = colors.foreground,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                ) { inner ->
                    if (body.isEmpty()) {
                        Text(
                            text = "note",
                            color = colors.mutedForeground,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    inner()
                }
            }
            Hairline(Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(8.dp))
            NoteActions(
                onSave = { onSave(title.trim(), body) },
                onOpen = onOpenExternal?.let { open -> { open(title.trim(), body) } },
                onDelete = onDelete,
                resetKey = edit,
            )
        }
    }
}

/**
 * The editor's pinned action row, echoing the todo sheet's left/right split: the
 * quiet tap-to-arm [DeleteAction] on the left (dropped for a brand-new note), a
 * muted "open in…" handoff in the middle for an existing note, and the primary
 * save on the right. [resetKey] disarms delete when the note changes.
 */
@Composable
private fun NoteActions(
    onSave: () -> Unit,
    onOpen: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    resetKey: Any?,
) {
    // Children align on their text baselines, not their centers, so the smaller
    // "delete"/"open" and the bigger "save" sit on one line despite the size gap.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (onDelete != null) {
            DeleteAction(onDelete = onDelete, resetKey = resetKey, modifier = Modifier.alignByBaseline())
        } else {
            Spacer(Modifier.width(1.dp))
        }
        if (onOpen != null) {
            OpenAction(onClick = onOpen, modifier = Modifier.alignByBaseline())
        }
        SaveAction(enabled = true, onClick = onSave, modifier = Modifier.alignByBaseline())
    }
}

/**
 * The handoff to a real editor: a quiet, muted "open in…" — deliberately understated
 * so save stays the one loud thing. Saves the note first (done by the caller), then
 * fires an ACTION_EDIT on its file so a purpose-built markdown editor can take over.
 */
@Composable
private fun OpenAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "open in…",
        color = UglyTheme.colors.mutedForeground,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    )
}

