package com.uglyos.launcher

import android.appwidget.AppWidgetHost
import android.content.Context

/**
 * Our one [AppWidgetHost], lazily created against the application context so
 * it outlives any single activity instance. [MainActivity] drives
 * [AppWidgetHost.startListening]/[AppWidgetHost.stopListening] off its own
 * lifecycle — that's what lets a hosted widget's RemoteViews updates reach us.
 */
object WidgetHost {
    private const val HOST_ID = 1

    @Volatile
    private var instance: AppWidgetHost? = null

    fun host(context: Context): AppWidgetHost =
        instance ?: synchronized(this) {
            instance ?: AppWidgetHost(context.applicationContext, HOST_ID).also { instance = it }
        }
}
