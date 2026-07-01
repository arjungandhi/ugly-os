package com.uglyos.common.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Holds the active [ThemeColors] for the tree below [UglyTheme]. `static`
 * because the palette changes rarely (only on a theme switch), so we skip the
 * per-read invalidation tracking a dynamic local would add.
 */
val LocalThemeColors = staticCompositionLocalOf { Themes.Default.colors }

/**
 * Applies [theme] to everything in [content].
 *
 * Exposes the palette two ways:
 *  - `UglyTheme.colors` for launcher-native, semantic colors.
 *  - a bridged Material 3 [MaterialTheme] so stock components (FAB, sheets,
 *    Text) pick up the theme automatically.
 *
 * ### Switching themes
 * Hoist the selected theme in the caller and pass it in; a change recomposes
 * the tree with the new palette:
 * ```
 * var theme by remember { mutableStateOf(Themes.Default) }
 * UglyTheme(theme) { Home() }
 * // later: theme = Themes.byNameOrDefault("nord")
 * ```
 * Persisting the choice (DataStore) is a separate, later concern.
 */
@Composable
fun UglyTheme(
    theme: Theme = Themes.Default,
    content: @Composable () -> Unit,
) {
    val colors = theme.colors
    val base = if (theme.isDark) darkColorScheme() else lightColorScheme()
    val material = base.copy(
        primary = colors.accent,
        onPrimary = colors.background,
        secondary = colors.accentMuted,
        onSecondary = colors.background,
        background = colors.background,
        onBackground = colors.foreground,
        surface = colors.surface,
        onSurface = colors.foreground,
        surfaceVariant = colors.surfaceElevated,
        onSurfaceVariant = colors.mutedForeground,
        outline = colors.subtle,
        outlineVariant = colors.subtle,
        error = colors.error,
        onError = colors.background,
    )

    CompositionLocalProvider(LocalThemeColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

/**
 * Accessor for the active theme, mirroring the `MaterialTheme.colorScheme`
 * pattern. Use `UglyTheme.colors.foreground` inside any composable under a
 * [UglyTheme] provider.
 */
object UglyTheme {
    val colors: ThemeColors
        @Composable
        @ReadOnlyComposable
        get() = LocalThemeColors.current
}
