package com.uglyos.launcher

import android.content.Context
import com.uglyos.common.todo.Task

/**
 * One selectable view of the todo file: a [label] for the switcher and a filter
 * over a task's `+project` and/or `@context`. A null field is a wildcard, so the
 * default mode (both null) matches everything. [invert] flips the test, turning
 * "tasks in +work" into "tasks not in +work".
 */
data class TodoMode(
    val label: String,
    val project: String? = null,
    val context: String? = null,
    val invert: Boolean = false,
) {
    /**
     * True when this mode shows [task]: its `+project` and `@context` must both
     * match (a null field matches anything), with the whole test flipped by
     * [invert].
     */
    fun matches(task: Task): Boolean {
        // A mode with nothing to filter on shows everything — even inverted, so a
        // stray "exclude" with no tags can't become a permanently empty view.
        if (project == null && context == null) return true
        val base = (project == null || project in task.projects) &&
            (context == null || context in task.contexts)
        return base != invert
    }

    /**
     * The `@context` to strip from rows (the mode already names it) and auto-append
     * to tasks added here — only when this mode scopes *to* that context, not when
     * it excludes it.
     */
    val hiddenContext: String? get() = context?.takeUnless { invert }

    /** The `+project`, likewise, stripped from rows and auto-appended on add. */
    val hiddenProject: String? get() = project?.takeUnless { invert }
}

/**
 * The user's todo modes, persisted in their own SharedPreferences. A fresh install
 * is seeded with a single "all" mode (no filter, shows everything) — but that's
 * just a starting point: it's a normal mode the user can rename, edit, or delete
 * like any other. We also remember which mode was last selected so the page reopens
 * where you left it. The list can legitimately end up empty (every mode deleted);
 * the page then falls back to showing everything.
 */
object TodoModeStore {
    private const val PREFS = "todo_modes"
    private const val KEY_MODES = "modes"
    private const val KEY_SELECTED = "selected"
    private const val FIELD_SEP = "\u001F"
    private const val RECORD_SEP = "\n"

    /** What a fresh install starts with, and the page's fallback when no mode is set. */
    val DEFAULT = TodoMode("all")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every mode in order. Seeds [DEFAULT] on first run (before any is stored). */
    fun modes(context: Context): List<TodoMode> {
        val p = prefs(context)
        if (!p.contains(KEY_MODES)) {
            val seed = listOf(DEFAULT)
            setModes(context, seed)
            return seed
        }
        val raw = p.getString(KEY_MODES, null) ?: return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { decode(it) }
    }

    private fun setModes(context: Context, modes: List<TodoMode>) {
        prefs(context).edit()
            .putString(KEY_MODES, modes.joinToString(RECORD_SEP) { encode(it) })
            .apply()
    }

    /** Append a new mode. */
    fun add(context: Context, mode: TodoMode) = setModes(context, modes(context) + mode)

    /** Replace the mode at [index]. */
    fun update(context: Context, index: Int, mode: TodoMode) {
        val cur = modes(context).toMutableList()
        if (index in cur.indices) {
            cur[index] = mode
            setModes(context, cur)
        }
    }

    /** Remove the mode at [index]. */
    fun removeAt(context: Context, index: Int) {
        val cur = modes(context).toMutableList()
        if (index in cur.indices) {
            cur.removeAt(index)
            setModes(context, cur)
        }
    }

    /** The last-selected index into [modes], clamped to what's currently there. */
    fun selected(context: Context): Int {
        val count = modes(context).size
        if (count == 0) return 0
        return prefs(context).getInt(KEY_SELECTED, 0).coerceIn(0, count - 1)
    }

    fun setSelected(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_SELECTED, index).apply()
    }

    private fun encode(m: TodoMode): String =
        listOf(m.label, m.project ?: "", m.context ?: "", if (m.invert) "1" else "0")
            // The separators are our record structure; a pasted one in a field
            // would corrupt it, so strip them out.
            .joinToString(FIELD_SEP) { it.replace(FIELD_SEP, "").replace(RECORD_SEP, " ") }

    private fun decode(line: String): TodoMode? {
        if (line.isBlank()) return null
        val parts = line.split(FIELD_SEP)
        if (parts.size < 4) return null
        val label = parts[0].ifBlank { return null }
        return TodoMode(
            label = label,
            project = parts[1].ifBlank { null },
            context = parts[2].ifBlank { null },
            invert = parts[3] == "1",
        )
    }
}
