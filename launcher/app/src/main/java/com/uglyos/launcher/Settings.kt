package com.uglyos.launcher

import android.Manifest
import android.content.ComponentName
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.uglyos.common.theme.UglyTheme
import java.io.File

/**
 * Persistent launcher settings, backed by SharedPreferences.
 *
 * The "todo dir" is the user-chosen directory holding todo.txt and done.txt.
 * It's stored as a plain filesystem path so we can read it directly and watch
 * it with FileObserver; Syncthing keeps it in sync across devices. It starts
 * unset. Reading arbitrary paths needs all-files access.
 */
object Settings {
    private const val PREFS = "ugly_launcher"
    private const val KEY_TODO_DIR = "todo_dir"
    private const val KEY_EXCLUDED_CALENDARS = "excluded_calendars"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The todo dir path, or null if the user hasn't set one. */
    fun todoDir(context: Context): File? =
        prefs(context).getString(KEY_TODO_DIR, null)?.let(::File)

    /** The todo.txt inside the todo dir, or null if no dir is set. */
    fun todoFile(context: Context): File? =
        todoDir(context)?.let { File(it, "todo.txt") }

    /** The done.txt inside the todo dir, where completed tasks are archived. */
    fun doneFile(context: Context): File? =
        todoDir(context)?.let { File(it, "done.txt") }

    /** Persist the chosen todo dir. */
    fun setTodoDir(context: Context, path: String) {
        prefs(context).edit().putString(KEY_TODO_DIR, path).apply()
    }

    /**
     * Calendar ids the user has turned *off* for the home-screen next-event
     * line. We store the excluded set rather than the included one so a newly
     * added calendar defaults on — better to briefly show an unwanted event than
     * to silently miss a real meeting on a calendar we didn't know about.
     */
    fun excludedCalendars(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXCLUDED_CALENDARS, emptySet())?.toSet() ?: emptySet()

    /** Turn a calendar on or off for the next-event line. */
    fun setCalendarEnabled(context: Context, id: String, enabled: Boolean) {
        val excluded = excludedCalendars(context).toMutableSet()
        if (enabled) excluded.remove(id) else excluded.add(id)
        prefs(context).edit().putStringSet(KEY_EXCLUDED_CALENDARS, excluded).apply()
    }

    /** Whether we hold all-files access, required to read the todo dir. */
    fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()
}

/** Whether contacts read access is granted, used to search device contacts. */
private fun hasContactsAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Whether the runtime permission dialog can still be shown. Once a permission is
 * permanently denied the system silently drops the request, so we route to the
 * app's settings page instead.
 */
private fun Context.canRequestPermission(permission: String, askedKey: String): Boolean {
    val activity = this as? android.app.Activity ?: return true
    return activity.shouldShowRequestPermissionRationale(permission) ||
        !hasBeenAsked(activity, askedKey)
}

/** True once we've shown [askedKey]'s permission request at least once. */
private fun hasBeenAsked(context: Context, askedKey: String): Boolean {
    val prefs = context.getSharedPreferences("ugly_launcher", Context.MODE_PRIVATE)
    return prefs.getBoolean(askedKey, false)
}

/** Remember that we've now shown [askedKey]'s request at least once. */
private fun markAsked(context: Context, askedKey: String) {
    context.getSharedPreferences("ugly_launcher", Context.MODE_PRIVATE)
        .edit().putBoolean(askedKey, true).apply()
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
    var todoDir by remember { mutableStateOf(Settings.todoDir(context)) }
    // Recheck on resume: the user grants these on system screens, outside our ui.
    var hasAccess by remember { mutableStateOf(Settings.hasStorageAccess()) }
    var hasContacts by remember { mutableStateOf(hasContactsAccess(context)) }
    var hasCalendar by remember { mutableStateOf(hasCalendarAccess(context)) }
    var hasMedia by remember { mutableStateOf(hasMediaAccess(context)) }
    var calendarList by remember { mutableStateOf(calendars(context)) }
    var excludedCals by remember { mutableStateOf(Settings.excludedCalendars(context)) }
    var dockRows by remember { mutableStateOf(QuickLaunchStore.rows(context)) }
    var dockCols by remember { mutableStateOf(QuickLaunchStore.cols(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = Settings.hasStorageAccess()
                hasContacts = hasContactsAccess(context)
                hasCalendar = hasCalendarAccess(context)
                hasMedia = hasMediaAccess(context)
                calendarList = calendars(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasContacts = granted }

    val calendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCalendar = granted
        if (granted) calendarList = calendars(context)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val path = uri?.toFilesystemPath()
        if (path != null) {
            Settings.setTodoDir(context, path)
            todoDir = File(path)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 96.dp),
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
        SettingSection("todo") {
            SettingRow(
                label = "todo dir",
                value = todoDir?.path ?: "tap to choose a folder",
                configured = todoDir != null,
                onClick = { picker.launch(null) },
            )
        }
        SettingSection("quick launch") {
            SettingStepper(
                label = "rows",
                value = dockRows,
                range = QuickLaunchStore.ROW_RANGE,
                onChange = { QuickLaunchStore.setRows(context, it); dockRows = it },
            )
            SettingDivider()
            SettingStepper(
                label = "columns",
                value = dockCols,
                range = QuickLaunchStore.COL_RANGE,
                onChange = { QuickLaunchStore.setCols(context, it); dockCols = it },
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
                    if (hasContacts ||
                        !context.canRequestPermission(Manifest.permission.READ_CONTACTS, "contacts_asked")
                    ) {
                        context.startActivity(
                            Intent(
                                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    } else {
                        markAsked(context, "contacts_asked")
                        contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    }
                },
            )
            SettingDivider()
            SettingRow(
                label = "calendar access",
                value = if (hasCalendar) "granted" else "tap to grant",
                configured = hasCalendar,
                onClick = {
                    if (hasCalendar ||
                        !context.canRequestPermission(Manifest.permission.READ_CALENDAR, "calendar_asked")
                    ) {
                        context.startActivity(
                            Intent(
                                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    } else {
                        markAsked(context, "calendar_asked")
                        calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                    }
                },
            )
            SettingDivider()
            SettingRow(
                label = "media controls",
                value = if (hasMedia) "granted" else "tap to grant",
                configured = hasMedia,
                onClick = {
                    // Notification access is a system toggle, not a runtime
                    // permission; deep-link straight to our listener's page, and
                    // fall back to the plain list screen on ROMs without the detail
                    // activity so a tap never crashes the settings page.
                    val detail = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(
                            AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ComponentName(context, UglyNotificationListenerService::class.java)
                                .flattenToString(),
                        )
                    runCatching { context.startActivity(detail) }.onFailure {
                        runCatching {
                            context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }
                },
            )
        }
        if (hasCalendar && calendarList.isNotEmpty()) {
            SettingSection("next event") {
                CalendarPicker(
                    calendars = calendarList,
                    excludedCals = excludedCals,
                    onToggle = { calendar, enabled ->
                        Settings.setCalendarEnabled(context, calendar.id.toString(), enabled)
                        excludedCals = Settings.excludedCalendars(context)
                    },
                )
            }
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

/**
 * A settings row that steps an integer between [range] bounds with − / + taps.
 * The label reads on the left; the value sits between the two buttons, which dim
 * to [subtle] at the ends of the range so you can see there's no further to go.
 */
@Composable
private fun SettingStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.foreground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        StepButton(symbol = "−", enabled = value > range.first) { onChange(value - 1) }
        Text(
            text = value.toString(),
            color = colors.foreground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(32.dp),
        )
        StepButton(symbol = "+", enabled = value < range.last) { onChange(value + 1) }
    }
}

/** A square − / + tap target for [SettingStepper], dimmed when disabled. */
@Composable
private fun StepButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            color = if (enabled) colors.foreground else colors.subtle,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * The calendars setting: a standard settings row naming the calendars that feed
 * the home-screen next-event line, with an "edit" affordance opening a picker
 * dialog. Calendars are set rarely, so the full list stays behind the popup
 * rather than sitting open as noise.
 */
@Composable
private fun CalendarPicker(
    calendars: List<CalendarInfo>,
    excludedCals: Set<String>,
    onToggle: (CalendarInfo, Boolean) -> Unit,
) {
    val colors = UglyTheme.colors
    var editing by remember { mutableStateOf(false) }
    val onNames = calendars.filter { it.id.toString() !in excludedCals }.map { it.displayName }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { editing = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "calendars",
                color = colors.foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = if (onNames.isEmpty()) "none selected" else onNames.joinToString(", "),
                color = colors.mutedForeground,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "edit",
            color = colors.mutedForeground,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
    if (editing) {
        CalendarPickerDialog(
            calendars = calendars,
            excludedCals = excludedCals,
            onToggle = onToggle,
            onDismiss = { editing = false },
        )
    }
}

/**
 * The picker popup: calendars as full-width toggle rows grouped by account, so
 * the whole row is the tap target and its state reads at a glance — name lit
 * with a filled dot when on, dimmed with a hollow dot when off.
 */
@Composable
private fun CalendarPickerDialog(
    calendars: List<CalendarInfo>,
    excludedCals: Set<String>,
    onToggle: (CalendarInfo, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = UglyTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = colors.surface, shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(vertical = 24.dp)) {
                Text(
                    text = "calendars",
                    color = colors.foreground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    calendars.groupBy { it.accountName }.forEach { (account, cals) ->
                        Box(Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)) {
                            AccountLabel(account)
                        }
                        cals.forEach { calendar ->
                            val on = calendar.id.toString() !in excludedCals
                            CalendarDialogRow(
                                name = calendar.displayName,
                                on = on,
                                onClick = { onToggle(calendar, !on) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "done",
                    color = colors.foreground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** One toggle row in the picker popup: full-width tap, dot shows on/off state. */
@Composable
private fun CalendarDialogRow(name: String, on: Boolean, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = if (on) colors.foreground else colors.mutedForeground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (on) colors.success else colors.subtle)
        )
    }
}

/** A quiet sub-signpost for an account, one size down from [SectionHeader]. */
@Composable
private fun AccountLabel(account: String) {
    val colors = UglyTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp),
    ) {
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(colors.subtle)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = account.uppercase(),
            color = colors.mutedForeground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

