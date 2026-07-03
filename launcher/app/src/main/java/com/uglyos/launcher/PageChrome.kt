package com.uglyos.launcher

import android.os.FileObserver
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uglyos.common.theme.UglyTheme
import java.io.File

/**
 * Shared chrome for the file-backed list pages (todo, notes): the structural
 * bits both wear identically, kept in one place so a tweak to the hairline, the
 * arm-to-confirm delete, or the dot gutter can't drift between the two pages.
 */

/** The gutter the list dots (and the add "+") sit in, so rows align. */
internal val DOT_GUTTER = 20.dp

/** The app's structural divider: a 1dp `subtle` hairline. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(UglyTheme.colors.subtle))
}

/**
 * A dimmed message for empty/unconfigured states. A calm empty state ("no tasks")
 * is plain grey; a setup state names where to act, and that [highlight] word is
 * picked out in `accentMuted` so the two read as different in kind — one is fine,
 * one wants a tap somewhere else.
 */
@Composable
internal fun Hint(text: String, highlight: String? = null) {
    val colors = UglyTheme.colors
    val annotated = buildAnnotatedString {
        val at = highlight?.let { text.indexOf(it) } ?: -1
        if (highlight == null || at < 0) {
            append(text)
        } else {
            append(text.substring(0, at))
            withStyle(SpanStyle(color = colors.accentMuted)) { append(highlight) }
            append(text.substring(at + highlight.length))
        }
    }
    Text(
        text = annotated,
        color = colors.mutedForeground,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace,
    )
}

/**
 * A tappable "+ [label]" affordance. Its "+" sits in the same [DOT_GUTTER] as the
 * list dots, so the column of markers lines up.
 */
@Composable
internal fun AddRow(label: String, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(DOT_GUTTER), contentAlignment = Alignment.Center) {
            Text(
                text = "+",
                color = colors.accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = label,
            color = colors.mutedForeground,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The primary commit action, right side of a sheet/editor action row: a bold
 * `accent` label — the one loud thing, kept to the text line so nothing pokes past
 * the row's other elements. Goes `subtle` and inert when [enabled] is false.
 */
@Composable
internal fun SaveAction(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = UglyTheme.colors
    Text(
        text = "save",
        color = if (enabled) colors.accent else colors.subtle,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    )
}

/**
 * The destructive action, deliberately quiet — small, dotless, `error`-colored — so
 * it can't be mistaken for the loud save on the other end of the row. The first tap
 * arms it (the label flips to a confirm prompt); only a second tap deletes, and it
 * disarms after a beat if you don't. One deliberate confirm, no modal-on-modal.
 * [resetKey] re-arms from scratch when the sheet moves to a different item.
 */
@Composable
internal fun DeleteAction(onDelete: () -> Unit, resetKey: Any?, modifier: Modifier = Modifier) {
    val colors = UglyTheme.colors
    var armed by remember(resetKey) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            kotlinx.coroutines.delay(2500)
            armed = false
        }
    }
    Text(
        text = if (armed) "confirm" else "delete",
        color = colors.error,
        fontSize = 13.sp,
        fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            // Tween, not spring — the label swaps like a segment flipping, no bounce.
            .animateContentSize(animationSpec = tween(durationMillis = 120))
            .clip(RoundedCornerShape(12.dp))
            .clickable { if (armed) onDelete() else armed = true }
            .padding(vertical = 12.dp, horizontal = 4.dp),
    )
}

/** Watch [dir] and call [onChange] whenever a file in it is written or renamed. */
internal fun watchDir(dir: File, onChange: () -> Unit): FileObserver {
    // Syncthing writes a temp file then renames it in, so watch for the rename
    // (MOVED_TO) as well as direct writes (CLOSE_WRITE) and deletes.
    val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
        FileObserver.MOVED_FROM or FileObserver.DELETE or FileObserver.CREATE
    return object : FileObserver(dir, mask) {
        override fun onEvent(event: Int, path: String?) = onChange()
    }
}
