package com.uglyos.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.uglyos.common.theme.UglyTheme

/**
 * A 5×7 dot-matrix font for the characters the clock needs (digits + colon).
 * Each glyph is 7 rows of a fixed-width bitmask string; '1' is a lit dot. The
 * colon is deliberately 1 column wide so it reads as a thin separator rather
 * than a full-width digit.
 */
private val DOT_FONT: Map<Char, List<String>> = mapOf(
    '0' to listOf(
        "01110",
        "10001",
        "10011",
        "10101",
        "11001",
        "10001",
        "01110",
    ),
    '1' to listOf(
        "00100",
        "01100",
        "00100",
        "00100",
        "00100",
        "00100",
        "01110",
    ),
    '2' to listOf(
        "01110",
        "10001",
        "00001",
        "00010",
        "00100",
        "01000",
        "11111",
    ),
    '3' to listOf(
        "11111",
        "00010",
        "00100",
        "00010",
        "00001",
        "10001",
        "01110",
    ),
    '4' to listOf(
        "00010",
        "00110",
        "01010",
        "10010",
        "11111",
        "00010",
        "00010",
    ),
    '5' to listOf(
        "11111",
        "10000",
        "11110",
        "00001",
        "00001",
        "10001",
        "01110",
    ),
    '6' to listOf(
        "00110",
        "01000",
        "10000",
        "11110",
        "10001",
        "10001",
        "01110",
    ),
    '7' to listOf(
        "11111",
        "00001",
        "00010",
        "00100",
        "01000",
        "01000",
        "01000",
    ),
    '8' to listOf(
        "01110",
        "10001",
        "10001",
        "01110",
        "10001",
        "10001",
        "01110",
    ),
    '9' to listOf(
        "01110",
        "10001",
        "10001",
        "01111",
        "00001",
        "00010",
        "01100",
    ),
    ':' to listOf(
        "0",
        "0",
        "1",
        "0",
        "1",
        "0",
        "0",
    ),
)

private const val DOT_ROWS = 7

/** A single lit dot in absolute grid coordinates across the whole string. */
private data class Dot(val col: Int, val row: Int)

/** The lit dots for a string plus the total column span they occupy. */
private class DotLayout(val dots: List<Dot>, val cols: Int)

/** Lay glyphs left-to-right, separated by [gapCols] blank columns. */
private fun layoutOf(text: String, gapCols: Int): DotLayout {
    val dots = ArrayList<Dot>()
    var x = 0
    text.forEachIndexed { i, c ->
        val glyph = DOT_FONT[c] ?: return@forEachIndexed
        val width = glyph[0].length
        for (row in glyph.indices) {
            val line = glyph[row]
            for (col in line.indices) {
                if (line[col] == '1') dots.add(Dot(x + col, row))
            }
        }
        x += width
        if (i != text.lastIndex) x += gapCols
    }
    return DotLayout(dots, x.coerceAtLeast(1))
}

/**
 * Renders [text] (expected to be a `HH:MM` clock string) as a dot-matrix
 * display. The dot pitch is derived from the available width, so the clock
 * scales to fill its container while keeping square dots.
 *
 * @param dotRatio dot diameter as a fraction of the cell pitch (0..1).
 * @param gapCols blank columns inserted between adjacent characters.
 */
@Composable
fun DotMatrixClock(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = UglyTheme.colors.foreground,
    dotRatio: Float = 0.62f,
    gapCols: Int = 1,
) {
    val layout = remember(text, gapCols) { layoutOf(text, gapCols) }
    BoxWithConstraints(modifier) {
        val pitch = maxWidth / layout.cols
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(pitch * DOT_ROWS)
        ) {
            val p = pitch.toPx()
            val radius = p * dotRatio / 2f
            for (dot in layout.dots) {
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(dot.col * p + p / 2f, dot.row * p + p / 2f),
                )
            }
        }
    }
}
