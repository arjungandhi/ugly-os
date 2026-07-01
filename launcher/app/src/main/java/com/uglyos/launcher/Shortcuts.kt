package com.uglyos.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** A two-column grid of quick-launch shortcut cards. */
@Composable
fun QuickLaunch(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Shortcut.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { shortcut ->
                    ShortcutCard(
                        shortcut = shortcut,
                        onClick = { launchShortcut(context, shortcut) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the final odd item aligned to its column.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShortcutCard(
    shortcut: Shortcut,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = UglyTheme.colors
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .height(64.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shortcut.label,
                color = colors.foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
