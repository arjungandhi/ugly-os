package com.uglyos.common.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The semantic color roles every theme must provide.
 *
 * These are intentionally *semantic* (what a color is for) rather than raw
 * Material slots (`primary`, `secondary`, `tertiary`, …). A launcher paints
 * with a small vocabulary — a background, some text, one accent — so themes
 * stay easy to author and swap. [UglyTheme] maps these onto Material 3's
 * ColorScheme so stock components (FAB, bottom sheet, Text) still work.
 */
@Immutable
data class ThemeColors(
    /** The window background — the wallpaper canvas / home screen. */
    val background: Color,
    /** Raised containers: the app drawer, sheets, cards. */
    val surface: Color,
    /** A step above [surface] for nested/elevated elements. */
    val surfaceElevated: Color,
    /** Primary text and icons. */
    val foreground: Color,
    /** Secondary/dimmed text — labels, captions, hints. */
    val mutedForeground: Color,
    /** Dividers, outlines, disabled states. */
    val subtle: Color,
    /** The primary interactive/highlight color. */
    val accent: Color,
    /** A secondary accent for less prominent highlights. */
    val accentMuted: Color,
    /** Positive / success state. */
    val success: Color,
    /** Caution / warning state. */
    val warning: Color,
    /** Error / destructive state. */
    val error: Color,
)

/**
 * A named, switchable theme. Implement this to add a new palette, then register
 * it in [Themes] so it can be pulled by name and offered in a theme picker.
 */
@Immutable
interface Theme {
    /** Stable identifier, used for persistence and lookup (e.g. "nord"). */
    val name: String

    /** Human-readable name for a theme picker (e.g. "Nord"). */
    val label: String

    /** Whether this is a dark palette; drives the Material light/dark base. */
    val isDark: Boolean

    /** The palette itself. */
    val colors: ThemeColors
}
