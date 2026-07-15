package com.uglyos.launcher

import android.content.Context

/**
 * The single hosted third-party widget on the home page: just its
 * [AppWidgetHost]-allocated id. `-1` means no widget has been picked yet.
 *
 * [clear] is the only place an id gets deleted from [WidgetHost] — routing
 * every removal (uninstalled provider, user-initiated remove, a picker that
 * gets abandoned mid-flow) through here keeps the store and the host from
 * drifting apart.
 */
object WidgetStore {
    private const val PREFS = "home_widget"
    private const val KEY_WIDGET_ID = "widget_id"
    private const val NONE = -1

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The hosted widget's id, or [NONE] if none is picked. */
    fun widgetId(context: Context): Int =
        prefs(context).getInt(KEY_WIDGET_ID, NONE)

    /** Persist a newly bound widget, releasing whatever id it's replacing. */
    fun setWidgetId(context: Context, id: Int) {
        val previous = widgetId(context)
        if (previous != NONE && previous != id) {
            runCatching { WidgetHost.host(context).deleteAppWidgetId(previous) }
        }
        prefs(context).edit().putInt(KEY_WIDGET_ID, id).apply()
    }

    /** Drop the hosted widget, deleting its id from the host too. */
    fun clear(context: Context) {
        val id = widgetId(context)
        if (id != NONE) {
            runCatching { WidgetHost.host(context).deleteAppWidgetId(id) }
        }
        prefs(context).edit().remove(KEY_WIDGET_ID).apply()
    }
}
