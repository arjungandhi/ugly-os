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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * editor on it; "new note" opens a blank one. Edits autosave through [NotesDir] as
 * you pause typing, on close, and when the app backgrounds; deletes go the same way.
 * All of it runs off the main thread, then reloads the list.
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
        // Editor state, reset whenever a different note is opened. `saved` is the
        // note as it currently lives on disk (null until first written, or after a
        // blank note is discarded). `pristine` is the raw text last written, so we
        // can tell a real edit from the untouched load — and, since it's the raw
        // input, comparing against it (not the sanitized filename) never mistakes a
        // clean save for a change and re-saves in a loop.
        var title by remember(edit) { mutableStateOf(edit.note?.title ?: "") }
        var body by remember(edit) { mutableStateOf(edit.note?.body ?: "") }
        var saved by remember(edit) { mutableStateOf(edit.note) }
        var pristine by remember(edit) {
            mutableStateOf((edit.note?.title ?: "") to (edit.note?.body ?: ""))
        }
        // Autosave, the close and pause flushes, delete and the open-in handoff all
        // mutate the same note file. `saveLock` funnels every one of them through a
        // single serialized writer so they can't overlap on disk (racing writes
        // spawn " 2" duplicates or resurrect a just-deleted note); `discarded` is
        // set by delete so a write already queued behind it can't recreate the file.
        val saveLock = remember(edit) { Mutex() }
        var discarded by remember(edit) { mutableStateOf(false) }

        fun dirty() = (title to body) != pristine

        // The one writer. Persists a text snapshot and updates `saved`/`pristine`
        // in-lock, so the next serialized write renames from the file this one left
        // (not a stale original) — retitling never piles up files. A cleared title
        // on an existing note keeps its filename; a fully-blank note is left
        // unwritten (or an existing one deleted). No-op once the note is discarded.
        suspend fun write(t: String, b: String) = saveLock.withLock {
            if (discarded) return@withLock
            val next = withContext(Dispatchers.IO) {
                val dir = Settings.notesDir(context) ?: return@withContext saved
                val trimmed = t.trim()
                when {
                    trimmed.isBlank() && b.isBlank() -> {
                        saved?.let { NotesDir(dir).delete(it) }
                        null
                    }
                    else -> {
                        val finalTitle = if (trimmed.isBlank()) saved?.title ?: trimmed else trimmed
                        NotesDir(dir).save(saved, finalTitle, b)
                    }
                }
            }
            saved = next
            pristine = t to b
        }

        // Close the editor. Snapshot the text and flush any unsaved edit in the
        // page scope (which outlives this block), so dismissing mid-edit never
        // strands a keystroke the debounce hadn't reached yet.
        fun close() {
            val flush = dirty()
            val t = title
            val b = body
            editing = null
            scope.launch {
                if (flush) write(t, b)
                reload()
            }
        }

        // Autosave: after a brief pause in typing, write the note. Keyed on the text
        // so each keystroke restarts the timer; the dirty check skips the pristine
        // load so opening a note doesn't rewrite it. The (t, b) snapshot is taken
        // here, before the write suspends, so a keystroke landing mid-write isn't
        // mistaken for already-saved.
        LaunchedEffect(edit, title, body) {
            if (!dirty()) return@LaunchedEffect
            val t = title
            val b = body
            delay(600)
            write(t, b)
        }

        // Backgrounding mid-edit flushes too, in case the app is dropped before the
        // debounce fires. Runs in the page scope so the pause doesn't cancel it.
        DisposableEffect(edit) {
            val obs = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE && dirty()) {
                    val t = title
                    val b = body
                    scope.launch { write(t, b) }
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }

        NoteEditor(
            title = title,
            onTitleChange = { title = it },
            body = body,
            onBodyChange = { body = it },
            autofocusTitle = edit.note == null,
            resetKey = edit,
            onDismiss = { close() },
            // The handoff flushes through the same writer, then opens whatever file
            // `saved` now names — read after the serialized write, so an autosave
            // rename can't leave it on a stale path.
            onOpenExternal = if (saved != null) {
                {
                    val t = title
                    val b = body
                    scope.launch {
                        write(t, b)
                        val note = saved
                        val dir = Settings.notesDir(context)
                        val opened = if (note != null && dir != null) {
                            openNoteExternally(context, File(dir, note.title + ".md"))
                        } else {
                            false
                        }
                        reload()
                        if (opened) editing = null
                    }
                }
            } else {
                null
            },
            // Delete goes through the same lock and marks the session discarded, so a
            // write already in flight can't bring the note back; it removes whatever
            // `saved` names at that point, not a capture that a rename may have staled.
            onDelete = if (saved != null) {
                {
                    discarded = true
                    editing = null
                    scope.launch {
                        saveLock.withLock {
                            val note = saved
                            if (note != null) {
                                withContext(Dispatchers.IO) {
                                    Settings.notesDir(context)?.let { NotesDir(it).delete(note) }
                                }
                                saved = null
                            }
                        }
                        reload()
                    }
                }
            } else {
                null
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
 * single-line title field (the filename) sits over a large scrollable body field.
 * Edits autosave (the caller persists as typing pauses and again on close), so the
 * pinned action row carries no save: a tap-to-arm [DeleteAction] on the left (shown
 * once the note exists), a muted "open in…" handoff, and a bold `done` on the right
 * that just dismisses. The editor is a pure view — the caller owns the text state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(
    title: String,
    onTitleChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    autofocusTitle: Boolean,
    resetKey: Any?,
    onDismiss: () -> Unit,
    onOpenExternal: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val colors = UglyTheme.colors
    val focusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // A brand-new blank note lands with the cursor in the title; an existing one
    // opens quietly so a stray keystroke doesn't rename it.
    LaunchedEffect(resetKey) {
        if (autofocusTitle) {
            delay(100)
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
                onValueChange = onTitleChange,
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
                    onValueChange = onBodyChange,
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
                onDone = onDismiss,
                onOpen = onOpenExternal,
                onDelete = onDelete,
                resetKey = resetKey,
            )
        }
    }
}

/**
 * The editor's pinned action row: the quiet tap-to-arm [DeleteAction] on the left
 * (shown once the note has been written), a muted "open in…" handoff in the middle,
 * and a bold `done` on the right. Edits have already autosaved, so `done` only
 * dismisses the editor. [resetKey] disarms delete when the note changes.
 */
@Composable
private fun NoteActions(
    onDone: () -> Unit,
    onOpen: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    resetKey: Any?,
) {
    // Children align on their text baselines, not their centers, so the smaller
    // "delete"/"open" and the bigger "done" sit on one line despite the size gap.
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
        SaveAction(enabled = true, onClick = onDone, label = "done", modifier = Modifier.alignByBaseline())
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

