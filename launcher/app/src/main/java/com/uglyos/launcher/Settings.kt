package com.uglyos.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.uglyos.common.theme.UglyTheme
import java.io.File

/**
 * Persistent launcher settings, backed by SharedPreferences.
 *
 * The "monkey dir" is the user-chosen directory the launcher reads its data
 * from (todo.txt, etc.). It's stored as a plain filesystem path so we can read
 * it directly and watch it with FileObserver; Syncthing keeps it in sync across
 * devices. It starts unset. Reading arbitrary paths needs all-files access.
 */
object Settings {
    private const val PREFS = "ugly_launcher"
    private const val KEY_MONKEY_DIR = "monkey_dir"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The monkey dir path, or null if the user hasn't set one. */
    fun monkeyDir(context: Context): File? =
        prefs(context).getString(KEY_MONKEY_DIR, null)?.let(::File)

    /** The todo.txt inside the monkey dir, or null if no dir is set. */
    fun todoFile(context: Context): File? =
        monkeyDir(context)?.let { File(it, "atp/todo/todo.txt") }

    /** Persist the chosen monkey dir. */
    fun setMonkeyDir(context: Context, path: String) {
        prefs(context).edit().putString(KEY_MONKEY_DIR, path).apply()
    }

    /** Whether we hold all-files access, required to read the monkey dir. */
    fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()
}

/**
 * Best-effort conversion of a Storage Access Framework tree URI to a real
 * filesystem path. Handles primary storage and named volumes (SD cards); the
 * user only picks the folder once, and we store the resulting path.
 */
private fun Uri.toFilesystemPath(): String? {
    val docId = try {
        DocumentsContract.getTreeDocumentId(this)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val (volume, relative) = docId.split(":", limit = 2).let {
        it[0] to it.getOrElse(1) { "" }
    }
    val base = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$volume"
    }
    return if (relative.isEmpty()) base else "$base/$relative"
}

/** The settings page: launcher configuration. */
@Composable
fun SettingsPage() {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    var monkeyDir by remember { mutableStateOf(Settings.monkeyDir(context)) }
    // Recheck on resume: the user grants all-files access on a system screen.
    var hasAccess by remember { mutableStateOf(Settings.hasStorageAccess()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasAccess = Settings.hasStorageAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val path = uri?.toFilesystemPath()
        if (path != null) {
            Settings.setMonkeyDir(context, path)
            monkeyDir = File(path)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "settings",
            color = colors.foreground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
        SettingGroup {
            SettingRow(
                label = "monkey dir",
                value = monkeyDir?.path ?: "tap to choose a folder",
                isSet = monkeyDir != null,
                onClick = { picker.launch(null) },
            )
            SettingRow(
                label = "all-files access",
                value = if (hasAccess) "granted" else "tap to grant",
                isSet = hasAccess,
                onClick = {
                    val intent = Intent(
                        AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                },
            )
        }
    }
}

/** A rounded card that groups related settings rows. */
@Composable
private fun SettingGroup(content: @Composable () -> Unit) {
    Surface(
        color = UglyTheme.colors.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/**
 * A tappable settings entry: bold label on top, current value below. The trailing
 * chevron and the "tap to…" hint when unset signal that the row opens a picker.
 */
@Composable
private fun SettingRow(label: String, value: String, isSet: Boolean, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
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
                color = if (isSet) colors.accent else colors.mutedForeground,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "›",
            color = colors.mutedForeground,
            fontSize = 22.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
