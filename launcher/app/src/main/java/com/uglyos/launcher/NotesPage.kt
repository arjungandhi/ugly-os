package com.uglyos.launcher

import android.content.Context
import android.os.FileObserver
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.uglyos.common.notes.Note
import com.uglyos.common.notes.NoteMeta
import com.uglyos.common.notes.NotesDir
import com.uglyos.common.theme.UglyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the notes page has to show once it's tried to read the dir. */
private sealed interface NotesState {
    /** No notes dir configured yet. */
    object NoDir : NotesState
    /** Notes dir set, but we lack the all-files access needed to read it. */
    object NoAccess : NotesState
    /** Access granted, dir readable, but it holds no notes yet. */
    object Empty : NotesState
    /**
     * Every note in the dir as row metadata (no bodies), newest-modified first.
     * This is the no-search view; a search streams the dir separately.
     */
    data class Loaded(val notes: List<NoteMeta>) : NotesState
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
    // Search results (title-or-body matches) for the current query, streamed off the
    // dir. Null means no active search — show the full [NotesState.Loaded] list.
    var results by remember { mutableStateOf<List<NoteMeta>?>(null) }
    var editing by remember { mutableStateOf<NoteEdit?>(null) }
    // Title of the row currently being opened (read off-thread before the editor
    // takes over), so a tap gets an immediate, minimal acknowledgement.
    var loadingTitle by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Reads run off the main thread: unlike the single small todo.txt, a notes dir
    // can hold many files, so even the metadata-only listing (a preview line per
    // file) is done off the ui thread to keep resume/refresh smooth.
    fun reload() = scope.launch {
        state = withContext(Dispatchers.IO) { loadNotesState(context) }
    }

    // Search streams the dir off the main thread, matching title or body, and keeps
    // only the hits — so it never holds every note body in memory the way an
    // in-memory filter would. Debounced, and keyed on `query` alone: keying on
    // `state` too would restart the search (blanking results to the title-only
    // placeholder) on every unrelated dir reload — a Syncthing write, a resume, or
    // our own autosave — flickering the results. So matches reflect the dir as of
    // when the query last changed; retyping refreshes them. A cleared query drops
    // back to the full list; a stale result is cleared up front so the placeholder
    // shows during the debounce instead of the previous query's matches.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null
            return@LaunchedEffect
        }
        results = null
        delay(200)
        val dir = Settings.notesDir(context)
        results = if (dir != null) {
            withContext(Dispatchers.IO) { NotesDir(dir).search(q) }
        } else {
            emptyList()
        }
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
                // No query: the full list. With a query: the streamed matches once
                // they land; until then, an instant title-only filter of the loaded
                // metadata so typing feels responsive before the disk search returns.
                val visible = when {
                    q.isEmpty() -> s.notes
                    results != null -> results!!
                    else -> s.notes.filter { it.title.contains(q, ignoreCase = true) }
                }
                SearchField(value = query, onValueChange = { query = it })
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (visible.size == 1) "1 note" else "${visible.size} notes",
                    color = colors.mutedForeground,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                if (visible.isEmpty()) {
                    Hint("no matches")
                    Spacer(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(visible) { meta ->
                            // Rows carry no body, so opening one loads its full text
                            // off-thread. If the read fails (file synced away or a
                            // transient error mid-sync), refuse rather than opening a
                            // blank editor on the real title — that empty body would
                            // become the editor's baseline and autosave would clobber
                            // the real note on the first keystroke.
                            NoteRow(
                                meta = meta,
                                loading = loadingTitle == meta.title,
                                onOpen = onOpen@{
                                    if (loadingTitle != null) return@onOpen
                                    loadingTitle = meta.title
                                    scope.launch {
                                        try {
                                            val note = withContext(Dispatchers.IO) {
                                                Settings.notesDir(context)?.let { NotesDir(it).read(meta.title) }
                                            }
                                            if (note != null) {
                                                editing = NoteEdit(note)
                                            } else {
                                                Toast.makeText(context, "couldn't open note", Toast.LENGTH_SHORT).show()
                                            }
                                        } finally {
                                            loadingTitle = null
                                        }
                                    }
                                },
                            )
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
        // always falls back to "untitled", new note or existing — a fully-blank note
        // is left unwritten (or an existing one deleted). No-op once discarded.
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
                        val finalTitle = trimmed.ifBlank { "untitled" }
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
            delay(250)
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
            dirty = dirty(),
            resetKey = edit,
            onDismiss = { close() },
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
 * A slim search well over the note list — its own quiet, focusable field rather
 * than the sheet's tall one, so it reads as a filter, not a note being written.
 * Filters by title or body as you type; empty means show everything.
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val colors = UglyTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                focusManager.clearFocus()
            }),
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
private fun NoteRow(meta: NoteMeta, loading: Boolean, onOpen: () -> Unit) {
    val colors = UglyTheme.colors
    val rowAlpha = if (loading) 0.5f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.size(DOT_GUTTER), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .border(1.5.dp, colors.subtle.copy(alpha = rowAlpha), CircleShape),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = meta.title,
                color = colors.foreground.copy(alpha = rowAlpha),
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.preview.isNotEmpty()) {
                Text(
                    text = meta.preview,
                    color = colors.mutedForeground.copy(alpha = rowAlpha),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A minimal hairline-pill drag handle, in place of the default M3 one. */
@Composable
private fun NoteDragHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(UglyTheme.colors.subtle),
        )
    }
}

/**
 * The full-screen note editor. A near-full-height [ModalBottomSheet] — the same
 * sheet the todo editor uses, so it isolates the home pager's swipes and insets
 * itself past the nav bar and keyboard for free (a raw Dialog does neither). A
 * single-line title field (the filename) sits over a large scrollable body field.
 * Edits autosave (the caller persists as typing pauses and again on close), so the
 * pinned action row carries no save: a tap-to-arm [DeleteAction] on the left (shown
 * once the note exists) and a bold `done` on the right that just dismisses. The
 * editor is a pure view — the caller owns the text state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(
    title: String,
    onTitleChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    autofocusTitle: Boolean,
    dirty: Boolean,
    resetKey: Any?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = UglyTheme.colors
    val focusRequester = remember { FocusRequester() }
    val bodyFocusRequester = remember { FocusRequester() }
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
        dragHandle = { NoteDragHandle() },
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
            // The title shares its line with a quiet save-state dot on the right —
            // the only feedback the silent autosave gives, kept out of thumb reach
            // since it's not interactive.
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    keyboardActions = KeyboardActions(onNext = { bodyFocusRequester.requestFocus() }),
                    modifier = Modifier
                        .weight(1f)
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
                SaveDot(dirty = dirty)
            }
            Hairline(Modifier.padding(vertical = 16.dp))
            // The field fills at least the visible height, so a tap anywhere in the
            // empty area below the text lands in it — not only on the first line.
            // BoxWithConstraints measures the bounded height; the scroll sits on the
            // inner box so long notes still scroll past that minimum.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                val minBodyHeight = maxHeight
                Box(Modifier.verticalScroll(rememberScrollState())) {
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minBodyHeight)
                            .focusRequester(bodyFocusRequester),
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
            }
            Hairline(Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(8.dp))
            NoteActions(
                onDone = onDismiss,
                onDelete = onDelete,
                resetKey = resetKey,
            )
        }
    }
}

/**
 * A small non-interactive save-state dot on the title line: filled [accent][UglyTheme]
 * while an edit is still pending, a hollow subtle ring (echoing the note-row dot) once
 * the autosave has landed. The only feedback the otherwise-silent autosave gives.
 */
@Composable
private fun SaveDot(dirty: Boolean) {
    val colors = UglyTheme.colors
    Box(
        Modifier
            .padding(start = 12.dp)
            .size(10.dp)
            .clip(CircleShape)
            .then(
                if (dirty) Modifier.background(colors.accent)
                else Modifier.border(1.5.dp, colors.subtle, CircleShape),
            ),
    )
}

/**
 * The editor's pinned action row, all on one line: the quiet tap-to-arm
 * [DeleteAction] on the left (shown once the note has been written) and a bold
 * `done` on the right. Edits have already autosaved, so `done` only dismisses.
 * [resetKey] disarms delete when the note changes.
 */
@Composable
private fun NoteActions(
    onDone: () -> Unit,
    onDelete: (() -> Unit)?,
    resetKey: Any?,
) {
    // Children align on their text baselines, not their centers, so the smaller
    // "delete" and the bigger "done" sit on one line despite the size gap.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (onDelete != null) {
            DeleteAction(onDelete = onDelete, resetKey = resetKey, modifier = Modifier.alignByBaseline())
        } else {
            Spacer(Modifier.width(1.dp))
        }
        SaveAction(enabled = true, onClick = onDone, label = "done", modifier = Modifier.alignByBaseline())
    }
}

