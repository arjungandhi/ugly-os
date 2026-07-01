package com.uglyos.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The soonest upcoming — or currently ongoing — timed calendar event. */
data class NextEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val location: String?,
) {
    /** True while the event is happening now rather than still ahead. */
    fun isOngoing(now: LocalDateTime): Boolean = !now.isBefore(start) && now.isBefore(end)
}

/** Whether calendar read access is granted, used to surface the next event. */
fun hasCalendarAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

/** One calendar on the device, as offered in the settings picker. */
data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
)

/**
 * Every calendar the device knows about, so the user can choose which ones feed
 * the next-event line. Ordered by account then name to group a person's
 * calendars together. Empty when calendar access isn't granted.
 */
fun calendars(context: Context): List<CalendarInfo> {
    if (!hasCalendarAccess(context)) return emptyList()
    val cols = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME,
    )
    return context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        cols,
        null,
        null,
        "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
    )?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
        val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val accountIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
        buildList {
            while (cursor.moveToNext()) {
                add(
                    CalendarInfo(
                        id = cursor.getLong(idIdx),
                        displayName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: "calendar",
                        accountName = cursor.getString(accountIdx)?.takeIf { it.isNotBlank() } ?: "device",
                    )
                )
            }
        }
    } ?: emptyList()
}

/**
 * The next event within [lookaheadHours], or null. Queries
 * [CalendarContract.Instances] — which expands recurring events into concrete
 * occurrences, unlike [CalendarContract.Events] — over a window from [now]
 * requires this); we skip all-day rows and calendars the user turned off, then
 * return up to [limit] instances that haven't ended, soonest first — so an
 * in-progress meeting leads and the next couple follow. The window is a rolling
 * [lookaheadMinutes] ahead (the next hour by default), so only what's imminent
 * shows; anything further out stays off the home screen.
 */
fun upcomingEvents(
    context: Context,
    now: LocalDateTime,
    limit: Int = 3,
    lookaheadMinutes: Long = 60,
): List<NextEvent> {
    if (!hasCalendarAccess(context)) return emptyList()
    val zone = ZoneId.systemDefault()
    val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
    val endMillis = now.plusMinutes(lookaheadMinutes).atZone(zone).toInstant().toEpochMilli()

    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .let { ContentUris.appendId(it, nowMillis); ContentUris.appendId(it, endMillis); it }
        .build()
    val cols = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.EVENT_LOCATION,
    )
    // The URI already bounds the time range, so selection only drops all-day
    // rows and any calendars the user excluded; ordering by BEGIN puts the
    // soonest (or ongoing) instance first.
    val excluded = Settings.excludedCalendars(context)
    var selection = "${CalendarContract.Instances.ALL_DAY} = 0"
    var args: Array<String>? = null
    if (excluded.isNotEmpty()) {
        val placeholders = excluded.joinToString(",") { "?" }
        selection += " AND ${CalendarContract.Instances.CALENDAR_ID} NOT IN ($placeholders)"
        args = excluded.toTypedArray()
    }
    return context.contentResolver.query(
        uri,
        cols,
        selection,
        args,
        "${CalendarContract.Instances.BEGIN} ASC",
    )?.use { cursor ->
        val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
        val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        buildList {
            while (size < limit && cursor.moveToNext()) {
                val endAt = cursor.getLong(endIdx)
                if (endAt <= nowMillis) continue // already over; only at the window edge
                add(
                    NextEvent(
                        title = cursor.getString(titleIdx)?.takeIf { it.isNotBlank() } ?: "busy",
                        start = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(beginIdx)), zone),
                        end = LocalDateTime.ofInstant(Instant.ofEpochMilli(endAt), zone),
                        location = cursor.getString(locIdx)?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }
    } ?: emptyList()
}
