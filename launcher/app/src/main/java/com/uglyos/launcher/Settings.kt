package com.uglyos.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uglyos.common.theme.UglyTheme

/**
 * Persistent launcher settings, backed by SharedPreferences.
 *
 * The "monkey dir" is the user-chosen directory the launcher reads its data
 * from (todo.txt, etc.). It's stored as a Storage Access Framework tree URI so
 * the grant survives reboots and app updates; it starts unset.
 */
object Settings {
    private const val PREFS = "ugly_launcher"
    private const val KEY_MONKEY_DIR = "monkey_dir"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The monkey dir tree URI, or null if the user hasn't set one. */
    fun monkeyDir(context: Context): Uri? =
        prefs(context).getString(KEY_MONKEY_DIR, null)?.let(Uri::parse)

    /** Persist the chosen monkey dir and take a durable permission grant. */
    fun setMonkeyDir(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs(context).edit().putString(KEY_MONKEY_DIR, uri.toString()).apply()
    }
}

/** Turn a tree URI into something readable, e.g. "primary:monkey". */
private fun Uri.displayPath(): String =
    lastPathSegment ?: toString()

/** The settings page: launcher configuration. */
@Composable
fun SettingsPage() {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    var monkeyDir by remember { mutableStateOf(Settings.monkeyDir(context)) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            Settings.setMonkeyDir(context, uri)
            monkeyDir = uri
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "settings",
            color = colors.foreground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
        SettingRow(
            label = "monkey dir",
            value = monkeyDir?.displayPath() ?: "not set",
            onClick = { picker.launch(monkeyDir) },
        )
    }
}

/** A tappable settings entry: label on top, current value below. */
@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Surface(
        color = colors.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                color = colors.foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = value,
                color = colors.mutedForeground,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
