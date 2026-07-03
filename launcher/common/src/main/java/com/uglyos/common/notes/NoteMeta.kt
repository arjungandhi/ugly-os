package com.uglyos.common.notes

/**
 * A note's list-row metadata, without its body.
 *
 * The notes page renders rows from just three things — the [title], a one-line
 * [preview] (the note's first non-blank line, trimmed; empty if it has none), and
 * [lastModified] epoch millis for newest-first sorting. Reading only this, instead
 * of every full [Note] body, is what keeps listing a large notes dir cheap; the
 * body is loaded on demand ([NotesDir.read]) only when a note is opened to edit.
 */
data class NoteMeta(
    val title: String,
    val preview: String,
    val lastModified: Long,
)
