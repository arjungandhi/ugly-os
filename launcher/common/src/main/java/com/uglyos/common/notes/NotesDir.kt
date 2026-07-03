package com.uglyos.common.notes

import java.io.File

/**
 * A directory of markdown [Note]s, one `<title>.md` file each.
 *
 * Kept dependency-free (java.io.File only) so listing, naming and saving stay
 * testable without Android. Writes go through a temp file and an atomic rename,
 * mirroring [com.uglyos.common.todo.TodoFile], so a crash mid-write can't leave
 * a half-written note behind.
 */
class NotesDir(val dir: File) {

    /** Every `*.md` note in [dir], newest-modified first. Skips unreadable files. */
    fun list(): List<Note> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .mapNotNull { file ->
                val body = try {
                    file.readText()
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                Note(
                    title = file.nameWithoutExtension,
                    body = body,
                    lastModified = file.lastModified(),
                )
            }
            .sortedByDescending { it.lastModified }
    }

    /**
     * Create or update a note and return the saved [Note].
     *
     * When [original] is null a new note is created; otherwise the note that
     * currently lives at `original.title` is edited. If [title] differs from
     * `original?.title` the old `.md` file is removed (rename). A [title] that
     * would collide with a DIFFERENT existing note is disambiguated by appending
     * " 2", " 3", ... A blank title falls back to "untitled" (then unique-named).
     * Path separators and other filename-illegal characters in [title] are
     * sanitized. Writes atomically via a temp file + rename.
     */
    fun save(original: Note?, title: String, body: String): Note {
        dir.mkdirs()

        val base = sanitize(title).ifBlank { UNTITLED }
        val originalFile = original?.let { File(dir, it.title + ".md") }
        val finalTitle = uniqueTitle(base, keep = originalFile)
        val target = File(dir, finalTitle + ".md")

        val tmp = File(dir, finalTitle + ".md.tmp")
        try {
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                // renameTo can fail across some filesystems; fall back to a copy.
                target.writeText(tmp.readText())
            }
        } finally {
            // Never leave a stray tmp behind — after a successful rename it's already
            // gone (harmless no-op), and on the copy path or any failure it's cleaned.
            tmp.delete()
        }

        // Drop the old file after a rename so an edit never leaves a stale copy.
        if (originalFile != null && originalFile != target && originalFile.exists()) {
            originalFile.delete()
        }

        return Note(finalTitle, body, target.lastModified())
    }

    /** Remove [note]'s `.md` file if it still exists. */
    fun delete(note: Note) {
        File(dir, note.title + ".md").delete()
    }

    /**
     * [base], or "[base] 2", "[base] 3", ... — the first that doesn't clash with
     * an existing note other than [keep] (the file being edited, which may keep
     * its own name).
     */
    private fun uniqueTitle(base: String, keep: File?): String {
        var candidate = base
        var n = 2
        while (true) {
            val file = File(dir, candidate + ".md")
            if (!file.exists() || file == keep) return candidate
            candidate = base + " " + n
            n++
        }
    }

    companion object {
        private const val UNTITLED = "untitled"

        // Reserved on common filesystems, plus control chars. Spaces and hyphens
        // are legal and kept.
        private val ILLEGAL = Regex("[/\\\\:*?\"<>|\\x00-\\x1f]")
        private val WHITESPACE = Regex("\\s+")

        /** Replace filename-illegal characters with spaces and collapse whitespace. */
        private fun sanitize(title: String): String =
            ILLEGAL.replace(title, " ").replace(WHITESPACE, " ").trim()
    }
}
