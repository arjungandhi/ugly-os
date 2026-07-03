package com.uglyos.common.notes

/**
 * A single markdown note, backed by a `<title>.md` file on disk.
 *
 * The [title] is the filename without its `.md` extension; [body] is the full
 * markdown text; [lastModified] is the file's last-modified epoch millis, used
 * to sort newest-first and to date a preview. Instances are immutable snapshots
 * of what was on disk at read time.
 */
data class Note(
    val title: String,
    val body: String,
    val lastModified: Long,
)
