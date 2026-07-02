package com.uglyos.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uglyos.common.theme.UglyTheme

/** No standard app category fits these, so they're launched by package. */
private const val BEEPER_PACKAGE = "com.beeper.android"
private const val GRAYJAY_PACKAGE = "com.futo.platformplayer"
private const val WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"
private const val HOME_ASSISTANT_PACKAGE = "io.homeassistant.companion.android"

/**
 * A home-screen quick-launch entry. Each resolves to an *implicit* intent
 * (an app category or well-known action) so it opens whatever app the user
 * has set as default, rather than a hard-coded package.
 */
enum class Shortcut(val label: String) {
    PHONE("phone"),
    MESSAGES("messages"),
    EMAIL("email"),
    BROWSER("internet"),
    MUSIC("music"),
    VIDEOS("videos"),
    CAMERA("camera"),
    WALLET("wallet"),
    HOME_ASSISTANT("ha"),
}

/** Build the launch intent for a shortcut, or null if none can be resolved. */
private fun Shortcut.intent(context: Context): Intent? = when (this) {
    Shortcut.PHONE -> Intent(Intent.ACTION_DIAL)
    Shortcut.MESSAGES -> context.packageManager.getLaunchIntentForPackage(BEEPER_PACKAGE)
    Shortcut.EMAIL -> appCategory(Intent.CATEGORY_APP_EMAIL)
    Shortcut.BROWSER -> appCategory(Intent.CATEGORY_APP_BROWSER)
    Shortcut.MUSIC -> appCategory(Intent.CATEGORY_APP_MUSIC)
    Shortcut.VIDEOS -> context.packageManager.getLaunchIntentForPackage(GRAYJAY_PACKAGE)
    Shortcut.CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
    Shortcut.WALLET -> context.packageManager.getLaunchIntentForPackage(WALLET_PACKAGE)
    Shortcut.HOME_ASSISTANT -> context.packageManager.getLaunchIntentForPackage(HOME_ASSISTANT_PACKAGE)
}

/** A MAIN intent restricted to a well-known launcher app category. */
private fun appCategory(category: String) =
    Intent(Intent.ACTION_MAIN).addCategory(category)

/** Launch a shortcut, toasting if no app on the device can handle it. */
fun launchShortcut(context: Context, shortcut: Shortcut) {
    val intent = shortcut.intent(context)
    if (intent == null) {
        Toast.makeText(context, "No ${shortcut.label} app found", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No ${shortcut.label} app found", Toast.LENGTH_SHORT).show()
    }
}

/**
 * The quiet quick-launch row: an UPPERCASE signpost label over a wrap of
 * hairline-bordered chips. Deliberately lighter than a filled card grid — the
 * clock owns the loud slot, so this recedes to structure (a border, a label)
 * rather than a second block of color.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickLaunch(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "quick launch".uppercase(),
            color = colors.mutedForeground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Shortcut.entries.forEach { shortcut ->
                ShortcutChip(
                    shortcut = shortcut,
                    onClick = { launchShortcut(context, shortcut) },
                )
            }
        }
    }
}

@Composable
private fun ShortcutChip(shortcut: Shortcut, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, colors.subtle, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = shortcut.label,
            color = colors.foreground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
