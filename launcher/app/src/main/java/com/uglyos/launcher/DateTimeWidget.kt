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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.uglyos.common.theme.UglyTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    }
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
