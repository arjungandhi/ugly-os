package com.uglyos.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installable widget, as offered in [WidgetChooserSheet]. */
data class WidgetChoice(
    val info: AppWidgetProviderInfo,
    val appLabel: String,
    val widgetLabel: String,
)

/** The installing app's own label, or its package name if that lookup fails. */
private fun appLabelFor(context: Context, packageName: String): String {
    val pm = context.packageManager
    return runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }
        .getOrDefault(packageName)
}

/**
 * The best human label for [info]: its own widget label, else its app's.
 * Touches [android.content.pm.PackageManager], so call this off the main
 * thread — see [WidgetSettingsRow].
 */
fun widgetDisplayLabel(context: Context, info: AppWidgetProviderInfo): String {
    val label = info.loadLabel(context.packageManager)
    return label.takeIf { it.isNotBlank() } ?: appLabelFor(context, info.provider.packageName)
}

/**
 * Every widget any installed app offers, grouped by app then widget name.
 * Touches [android.content.pm.PackageManager], so call this off the main
 * thread — see [rememberWidgetPicker].
 */
private fun installedWidgets(context: Context): List<WidgetChoice> {
    return AppWidgetManager.getInstance(context).installedProviders
        .map { info ->
            val appLabel = appLabelFor(context, info.provider.packageName)
            WidgetChoice(info = info, appLabel = appLabel, widgetLabel = widgetDisplayLabel(context, info))
        }
        .sortedWith(compareBy({ it.appLabel.lowercase() }, { it.widgetLabel.lowercase() }))
}

/**
 * Drives the whole pick-a-widget flow and the state behind [WidgetChooserSheet].
 *
 * We deliberately don't rely on [AppWidgetManager.ACTION_APPWIDGET_PICK]: on
 * modern Android that intent is only ever resolved by whichever app is the
 * *current default launcher* (if it resolves at all), not by a shared system
 * component — a non-default launcher, or a device whose launcher doesn't
 * implement it, would crash with an uncaught `ActivityNotFoundException`. So
 * we build the picker ourselves: enumerate [AppWidgetManager.getInstalledProviders],
 * bind the chosen provider directly via [AppWidgetManager.bindAppWidgetIdIfAllowed],
 * and only fall back to the system's [AppWidgetManager.ACTION_APPWIDGET_BIND]
 * confirmation screen when that pre-grant is refused. [onDone] fires after
 * every terminal outcome (bound, configured, or abandoned) so the caller can
 * re-read [WidgetStore].
 */
@Composable
fun rememberWidgetPicker(onDone: () -> Unit): WidgetPickerState {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<WidgetChoice>>(emptyList()) }
    // The id (and its provider's configure activity, if any) awaiting a
    // system confirmation/configure screen; null when nothing's pending.
    val pendingId = remember { mutableIntStateOf(-1) }
    var pendingInfo by remember { mutableStateOf<AppWidgetProviderInfo?>(null) }

    LaunchedEffect(expanded) {
        if (expanded) {
            choices = withContext(Dispatchers.IO) { installedWidgets(context) }
        }
    }

    val configureLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val id = pendingId.intValue
        pendingId.intValue = -1
        pendingInfo = null
        if (id == -1) return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            WidgetStore.setWidgetId(context, id)
        } else {
            WidgetHost.host(context).deleteAppWidgetId(id)
        }
        onDone()
    }

    fun proceed(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            pendingId.intValue = id
            pendingInfo = info
            configureLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = info.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                }
            )
        } else {
            WidgetStore.setWidgetId(context, id)
            onDone()
        }
    }

    val bindLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val id = pendingId.intValue
        val info = pendingInfo
        pendingId.intValue = -1
        pendingInfo = null
        if (result.resultCode != Activity.RESULT_OK || info == null) {
            if (id != -1) WidgetHost.host(context).deleteAppWidgetId(id)
            onDone()
        } else {
            proceed(id, info)
        }
    }

    fun choose(choice: WidgetChoice) {
        expanded = false
        val id = WidgetHost.host(context).allocateAppWidgetId()
        val bound = AppWidgetManager.getInstance(context)
            .bindAppWidgetIdIfAllowed(id, choice.info.provider)
        if (bound) {
            proceed(id, choice.info)
        } else {
            pendingId.intValue = id
            pendingInfo = choice.info
            bindLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, choice.info.provider)
                }
            )
        }
    }

    return WidgetPickerState(
        expanded = expanded,
        choices = choices,
        open = { expanded = true },
        dismiss = { expanded = false },
        choose = ::choose,
    )
}

/** The chooser sheet's state and actions, returned by [rememberWidgetPicker]. */
data class WidgetPickerState(
    val expanded: Boolean,
    val choices: List<WidgetChoice>,
    val open: () -> Unit,
    val dismiss: () -> Unit,
    val choose: (WidgetChoice) -> Unit,
)
