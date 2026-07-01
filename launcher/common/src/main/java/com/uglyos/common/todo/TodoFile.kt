package com.uglyos.common.todo

import java.io.File

/**
 * Reads and writes a [TodoList] to a todo.txt file on disk.
 *
 * Kept separate from the pure model so parsing/formatting stays testable
 * without touching the filesystem. Writes go through a temp file and an atomic
 * rename so a crash mid-write can't leave a half-written list behind.
 */
class TodoFile(val file: File) {

    /** Load the file, or an empty list if it doesn't exist yet. */
    fun load(): TodoList =
        if (file.exists()) TodoList.parse(file.readText()) else TodoList()

    /** Overwrite the file with [list]'s contents, atomically. */
    fun save(list: TodoList) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(list.render() + "\n")
        if (!tmp.renameTo(file)) {
            // renameTo can fail across some filesystems; fall back to a copy.
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** Load, apply [edit], and save back. Returns the edited list. */
    fun edit(edit: (TodoList) -> Unit): TodoList {
        val list = load()
        edit(list)
        save(list)
        return list
    }
}
