package com.uglyos.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
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

    /** The done.txt inside the monkey dir, where completed tasks are archived. */
    fun doneFile(context: Context): File? =
        monkeyDir(context)?.let { File(it, "atp/todo/done.txt") }

    /** Persist the chosen monkey dir. */
    fun setMonkeyDir(context: Context, path: String) {
        prefs(context).edit().putString(KEY_MONKEY_DIR, path).apply()
    }

    /** Whether we hold all-files access, required to read the monkey dir. */
    fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()
}

/** Whether contacts read access is granted, used to search device contacts. */
private fun hasContactsAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Whether the runtime permission dialog can still be shown. Once contacts is
 * permanently denied the system silently drops the request, so we route to the
 * app's settings page instead.
 */
private fun Context.canRequestContacts(): Boolean {
    val activity = this as? android.app.Activity ?: return true
    return activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) ||
        !hasBeenAsked(activity)
}

/** True once we've asked for contacts at least once, tracked in prefs. */
private fun hasBeenAsked(context: Context): Boolean {
    val prefs = context.getSharedPreferences("ugly_launcher", Context.MODE_PRIVATE)
    return prefs.getBoolean("contacts_asked", false)
}

/** Remember that we've now shown the contacts request at least once. */
private fun markContactsAsked(context: Context) {
    context.getSharedPreferences("ugly_launcher", Context.MODE_PRIVATE)
        .edit().putBoolean("contacts_asked", true).apply()
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
    // Recheck on resume: the user grants these on system screens, outside our ui.
    var hasAccess by remember { mutableStateOf(Settings.hasStorageAccess()) }
    var hasContacts by remember { mutableStateOf(hasContactsAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = Settings.hasStorageAccess()
                hasContacts = hasContactsAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasContacts = granted }

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
        SettingSection("data") {
            SettingRow(
                label = "monkey dir",
                value = monkeyDir?.path ?: "tap to choose a folder",
                configured = monkeyDir != null,
                onClick = { picker.launch(null) },
            )
        }
        SettingSection("permissions") {
            SettingRow(
                label = "all-files access",
                value = if (hasAccess) "granted" else "tap to grant",
                configured = hasAccess,
                onClick = {
                    val intent = Intent(
                        AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                },
            )
            SettingDivider()
            SettingRow(
                label = "contacts access",
                value = if (hasContacts) "granted" else "tap to grant",
                configured = hasContacts,
                onClick = {
                    // First ask normally; once permanently denied the dialog
                    // no longer shows, so fall back to the app's settings page.
                    if (hasContacts || !context.canRequestContacts()) {
                        context.startActivity(
                            Intent(
                                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    } else {
                        markContactsAsked(context)
                        contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    }
                },
            )
        }
    }
}

/** A titled group: a [SectionHeader] signpost above a rounded card of rows. */
@Composable
private fun SettingSection(title: String, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title)
        SettingGroup(content = rows)
    }
}

/**
 * A structural signpost above a group: a bullet dot and a tracked, uppercase
 * label, echoing the calendar card and search sections so every screen reads as
 * one phone. The dot is [subtle] — pure structure, never the accent.
 */
@Composable
private fun SectionHeader(label: String) {
    val colors = UglyTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.subtle)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label.uppercase(),
            color = colors.mutedForeground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
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

/** A hairline between rows in a group, inset to align under the labels. */
@Composable
private fun SettingDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp)
            .height(1.dp)
            .background(UglyTheme.colors.subtle)
    )
}

/**
 * A tappable settings entry: lowercase label on top, current value below in muted
 * grey. A trailing status dot is the row's one meaningful color — [success] when
 * [configured], else [subtle] — so the screen stays flat and reads its state at a
 * glance without shouting.
 */
@Composable
private fun SettingRow(label: String, value: String, configured: Boolean, onClick: () -> Unit) {
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
                color = colors.mutedForeground,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (configured) colors.success else colors.subtle)
        )
    }
}
