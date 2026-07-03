package com.uglyos.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uglyos.common.theme.UglyTheme
import java.util.concurrent.ConcurrentHashMap

/*
 * App icons, the ugly-os way: a single-color glyph, never a full-color launcher
 * icon, so every screen stays in the Nord palette and nothing shouts. We prefer
 * the app's OS monochrome (themed-icon) layer where it ships one, and fall back
 * to a monogram of its first letter. Shared by the app drawer and the home dock
 * so an app looks identical wherever it appears.
 */

/** A single-color stand-in for an app icon, tinted to the theme when drawn. */
sealed interface AppGlyph {
    /** The app's OS monochrome layer, rasterized; drawn tinted via [ColorFilter]. */
    data class Mono(val bitmap: ImageBitmap) : AppGlyph
    /** Fallback for apps without a monochrome layer: their first letter. */
    data class Monogram(val letter: String) : AppGlyph
}

/** The first letter/digit of a label, uppercased, for the monogram fallback. */
fun monogramOf(label: String): String =
    label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"

/**
 * Build an [AppGlyph] for [app]. On API 33+ we pull the adaptive icon's
 * monochrome layer — the OS themed-icon glyph — and rasterize it untinted so the
 * ui can recolor it to any Nord role. Apps that never shipped one fall back to a
 * monogram. Runs off the main thread (it touches PackageManager and a Canvas).
 */
fun loadGlyph(context: Context, app: AppInfo): AppGlyph {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val icon = runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        val mono = (icon as? AdaptiveIconDrawable)?.monochrome
        if (mono != null) return AppGlyph.Mono(rasterize(mono))
    }
    return AppGlyph.Monogram(monogramOf(app.label))
}

/**
 * Process-wide glyph cache keyed by package. Rasterizing an icon into a bitmap
 * isn't free, and the same app shows up in the drawer, the dock, and the picker;
 * caching here means each icon is built once instead of once per surface. A
 * package's glyph is stable for the process lifetime, so we never invalidate.
 */
private val glyphCache = ConcurrentHashMap<String, AppGlyph>()

/** [loadGlyph], memoized across every surface. Call off the main thread. */
fun cachedGlyph(context: Context, app: AppInfo): AppGlyph =
    glyphCache.getOrPut(app.packageName) { loadGlyph(context, app) }

/** Draw a drawable into a square bitmap; its alpha carries the glyph to tint. */
private fun rasterize(drawable: Drawable, sizePx: Int = 144): ImageBitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(Canvas(bmp))
    return bmp.asImageBitmap()
}

/**
 * A bare glyph tinted to [tint] and sized to [size], no background. The monogram
 * fallback scales its letter to the box. Use this where an icon floats (the home
 * dock); wrap it in [GlyphTile] for the drawer's surface tiles.
 */
@Composable
fun Glyph(glyph: AppGlyph?, size: Dp, tint: Color = UglyTheme.colors.foreground) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        when (glyph) {
            is AppGlyph.Mono -> Image(
                bitmap = glyph.bitmap,
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.fillMaxSize(),
            )
            is AppGlyph.Monogram -> Text(
                text = glyph.letter,
                color = tint,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            null -> {} // still loading: nothing yet, the slot holds its space
        }
    }
}

/** The drawer's 56dp surface tile holding a monochrome glyph or a monogram. */
@Composable
fun GlyphTile(glyph: AppGlyph?) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(UglyTheme.colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, size = 56.dp)
    }
}
