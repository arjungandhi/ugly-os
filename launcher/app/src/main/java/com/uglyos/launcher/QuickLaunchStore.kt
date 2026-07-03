package com.uglyos.launcher

import android.content.Context

/*
 * Home-screen quick-launch state: an ordered list of pinned app packages, plus
 * the dock's grid size. Kept in its own SharedPreferences file.
 *
 * The pin order is the on-screen order, so a slot stays put once filled — that's
 * the whole point of the dock. You learn where an app sits and reach for it by
 * position, without reading.
 *
 * On first run the dock is empty until [seedDefaults] runs (off the main thread,
 * from the ui), which resolves the built-in shortcuts to whatever concrete apps
 * handle them. Once seeded, clearing every pin is respected — an empty (but
 * present) list won't re-seed, since [isSeeded] keys off the value existing.
 */
object QuickLaunchStore {
    private const val PREFS = "quick_launch"
    private const val KEY_PINS = "pins"
    private const val KEY_ROWS = "rows"
    private const val KEY_COLS = "cols"

    const val DEFAULT_ROWS = 2
    const val DEFAULT_COLS = 5
    val ROW_RANGE = 1..4
    val COL_RANGE = 3..6

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pinned packages in on-screen order; empty until the dock has been seeded. */
    fun pins(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PINS, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    /** Whether the dock has ever been populated (so we don't re-seed a cleared dock). */
    fun isSeeded(context: Context): Boolean = prefs(context).contains(KEY_PINS)

    /**
     * Populate a fresh dock from [defaultPins] and persist it. Touches
     * PackageManager, so call this off the main thread.
     */
    fun seedDefaults(context: Context): List<String> {
        val pins = defaultPins(context)
        setPins(context, pins)
        return pins
    }

    /** Persist the pinned packages, preserving order. */
    fun setPins(context: Context, pkgs: List<String>) {
        prefs(context).edit().putString(KEY_PINS, pkgs.joinToString("\n")).apply()
    }

    /** Append a package to the dock if it isn't already pinned. */
    fun add(context: Context, pkg: String) {
        val cur = pins(context)
        if (pkg !in cur) setPins(context, cur + pkg)
    }

    /** Drop a package from the dock. */
    fun remove(context: Context, pkg: String) {
        setPins(context, pins(context).filterNot { it == pkg })
    }

    fun rows(context: Context): Int =
        prefs(context).getInt(KEY_ROWS, DEFAULT_ROWS).coerceIn(ROW_RANGE)

    fun cols(context: Context): Int =
        prefs(context).getInt(KEY_COLS, DEFAULT_COLS).coerceIn(COL_RANGE)

    fun setRows(context: Context, rows: Int) {
        prefs(context).edit().putInt(KEY_ROWS, rows.coerceIn(ROW_RANGE)).apply()
    }

    fun setCols(context: Context, cols: Int) {
        prefs(context).edit().putInt(KEY_COLS, cols.coerceIn(COL_RANGE)).apply()
    }
}
