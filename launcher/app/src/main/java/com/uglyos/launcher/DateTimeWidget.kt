package com.uglyos.launcher

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.uglyos.common.theme.UglyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.format.TextStyle
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy")

/** How many days sit on either side of today in the rolling day strip. */
private const val DAY_STRIP_RADIUS = 3

/**
 * A [State] holding the current wall-clock time, kept fresh by the system's
 * minute tick. We listen for [Intent.ACTION_TIME_TICK] (fires every minute on
 * the minute) plus explicit time/zone changes, so the display flips exactly
 * when the clock does rather than drifting on a polling interval.
 */
@Composable
fun rememberNow(): State<LocalDateTime> {
    val context = LocalContext.current
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                now.value = LocalDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Re-sync on (re)composition in case time moved while we weren't listening.
        now.value = LocalDateTime.now()
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return now
}

/**
 * The home-screen time & date block: a large dot-matrix clock above a card
 * showing the month and a rolling week centered on today.
 */
@Composable
fun DateTimeWidget(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by rememberNow()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        DotMatrixClock(
            text = now.format(TIME_FORMAT),
            modifier = Modifier.fillMaxWidth(0.72f),
        )
        Spacer(Modifier.height(28.dp))
        CalendarCard(
            today = now.toLocalDate(),
            onClick = { openCalendar(context) },
            modifier = Modifier.fillMaxWidth(),
        )
        NextEvents(
            now = now,
            onClick = { openCalendar(context) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Starts within this many minutes count as imminent and earn the accent dot. */
private const val IMMINENT_MINUTES = 5L

/**
 * A quiet stack under the calendar card: up to three of the next hour's events,
 * each with a live countdown. The first is the anchor — a bullet dot (accent
 * only when imminent) and lit text; the rest align under it in muted grey, so
 * one thing leads without the block shouting. The calendar read runs off the
 * main thread and refreshes on each minute tick and when the calendar selection
 * changes. Renders nothing when the next hour is clear — an empty home screen
 * stays calm.
 */
@Composable
private fun NextEvents(now: LocalDateTime, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val minute = now.truncatedTo(ChronoUnit.MINUTES)
    val excluded = Settings.excludedCalendars(context)
    var events by remember { mutableStateOf(emptyList<NextEvent>()) }
    LaunchedEffect(minute, excluded) {
        events = withContext(Dispatchers.IO) { upcomingEvents(context, now) }
    }
    if (events.isEmpty()) return

    Spacer(Modifier.height(18.dp))
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        events.forEachIndexed { index, event ->
            EventRow(now = now, event = event, primary = index == 0)
        }
    }
}

/** One line in the [NextEvents] stack: countdown then title, dot on the anchor. */
@Composable
private fun EventRow(now: LocalDateTime, event: NextEvent, primary: Boolean) {
    val colors = UglyTheme.colors
    val lead = if (event.isOngoing(now)) {
        "ends in " + humanizeMinutes(Duration.between(now, event.end).toMinutes())
    } else {
        val mins = Duration.between(now, event.start).toMinutes()
        if (mins <= 0) "now" else "in " + humanizeMinutes(mins)
    }
    val imminent = primary && !event.isOngoing(now) &&
        Duration.between(now, event.start).toMinutes() <= IMMINENT_MINUTES

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (primary) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (imminent) colors.accent else colors.subtle)
            )
        } else {
            Spacer(Modifier.size(6.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = lead,
            color = if (primary) colors.foreground else colors.mutedForeground,
            fontSize = 14.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "· ${event.title}",
            color = colors.mutedForeground,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Terse lowercase duration: "45 min", "1h", "2h 05m". */
private fun humanizeMinutes(mins: Long): String = when {
    mins < 60 -> "$mins min"
    mins % 60 == 0L -> "${mins / 60}h"
    else -> "%dh %02dm".format(mins / 60, mins % 60)
}

/**
 * Open the user's calendar app to today's date via the standard
 * [CalendarContract] "view time" URI, which any calendar app registers to
 * handle. Falls back to a toast if the device has no calendar app.
 */
private fun openCalendar(context: Context) {
    val uri = CalendarContract.CONTENT_URI.buildUpon()
        .appendPath("time")
        .let { ContentUris.appendId(it, System.currentTimeMillis()); it }
        .build()
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
    }
}

/** The rounded card: a "MONTH YEAR" header and a 7-day strip around today. */
@Composable
private fun CalendarCard(
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = UglyTheme.colors
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = today.format(MONTH_FORMAT).uppercase(Locale.getDefault()),
                    color = colors.foreground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (offset in -DAY_STRIP_RADIUS..DAY_STRIP_RADIUS) {
                    val day = today.plusDays(offset.toLong())
                    DayCell(
                        day = day,
                        isToday = offset == 0,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One column of the day strip: weekday initial over a circled day-of-month. */
@Composable
private fun DayCell(day: LocalDate, isToday: Boolean, modifier: Modifier = Modifier) {
    val colors = UglyTheme.colors
    val initial = day.dayOfWeek
        .getDisplayName(TextStyle.NARROW, Locale.getDefault())
        .uppercase(Locale.getDefault())
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = initial,
            color = if (isToday) colors.foreground else colors.mutedForeground,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(12.dp))
        val circle = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .let {
                if (isToday) it.background(colors.foreground)
                else it.border(1.dp, colors.subtle, CircleShape)
            }
        Box(circle, contentAlignment = Alignment.Center) {
            Text(
                text = day.dayOfMonth.toString(),
                color = if (isToday) colors.background else colors.foreground,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}
