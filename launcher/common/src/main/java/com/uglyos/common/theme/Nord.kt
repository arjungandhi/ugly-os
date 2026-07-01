package com.uglyos.common.theme

import androidx.compose.ui.graphics.Color

/**
 * The official Nord palette (https://www.nordtheme.com/), by group.
 * Kept as raw swatches so themes can remap roles without redefining hexes.
 */
private object NordPalette {
    // Polar Night — dark backgrounds.
    val nord0 = Color(0xFF2E3440)
    val nord1 = Color(0xFF3B4252)
    val nord2 = Color(0xFF434C5E)
    val nord3 = Color(0xFF4C566A)

    // Snow Storm — light text.
    val nord4 = Color(0xFFD8DEE9)
    val nord5 = Color(0xFFE5E9F0)
    val nord6 = Color(0xFFECEFF4)

    // Frost — the classic Nord accents.
    val nord7 = Color(0xFF8FBCBB)
    val nord8 = Color(0xFF88C0D0)
    val nord9 = Color(0xFF81A1C1)
    val nord10 = Color(0xFF5E81AC)

    // Aurora — semantic pops of color.
    val nord11 = Color(0xFFBF616A) // red
    val nord12 = Color(0xFFD08770) // orange
    val nord13 = Color(0xFFEBCB8B) // yellow
    val nord14 = Color(0xFFA3BE8C) // green
    val nord15 = Color(0xFFB48EAD) // purple
}

/** Nord — a dark, arctic palette. The launcher's default theme. */
object Nord : Theme {
    override val name = "nord"
    override val label = "Nord"
    override val isDark = true

    override val colors = ThemeColors(
        background = NordPalette.nord0,
        surface = NordPalette.nord1,
        surfaceElevated = NordPalette.nord2,
        foreground = NordPalette.nord6,
        mutedForeground = NordPalette.nord4,
        subtle = NordPalette.nord3,
        accent = NordPalette.nord8,
        accentMuted = NordPalette.nord9,
        success = NordPalette.nord14,
        warning = NordPalette.nord13,
        error = NordPalette.nord11,
    )
}
