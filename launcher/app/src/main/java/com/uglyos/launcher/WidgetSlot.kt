package com.uglyos.launcher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.uglyos.common.theme.UglyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val WIDGET_MIN_HEIGHT = 64.dp
private val WIDGET_MAX_HEIGHT = 160.dp
private const val PAGE_HORIZONTAL_PADDING = 40 // 20dp each side, see HomePage
private const val NONE = -1

/**
 * The one hosted third-party widget on the home page: an "add widget" row
 * when none is picked, else its live view in a card matching [DateTimeWidget]'s
 * `CalendarCard`. Long-press the bound card to swap or remove it.
 *
 * Owns the [WidgetHost] listening lifecycle itself (start/stop alongside
 * [LocalLifecycleOwner], the same pattern every other lifecycle-sensitive
 * feature in this app uses — see [DateTimeWidget]'s clock tick receiver)
 * rather than [MainActivity] reaching into it directly.
 */
@Composable
fun WidgetSlot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> WidgetHost.host(context).startListening()
                Lifecycle.Event.ON_STOP -> WidgetHost.host(context).stopListening()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var refreshKey by remember { mutableIntStateOf(0) }
    var showManage by remember { mutableStateOf(false) }
    val widgetId = remember(refreshKey) { WidgetStore.widgetId(context) }

    // AppWidgetManager.getAppWidgetInfo is a Binder call to system_server —
    // off the main thread, like every other IPC-backed read in this app.
    var info by remember(widgetId) { mutableStateOf<AppWidgetProviderInfo?>(null) }
    var infoLoaded by remember(widgetId) { mutableStateOf(widgetId == NONE) }
    LaunchedEffect(widgetId) {
        if (widgetId != NONE) {
            info = withContext(Dispatchers.IO) {
                AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)
            }
        }
        infoLoaded = true
    }

    val picker = rememberWidgetPicker(onDone = { refreshKey++ })

    // The provider vanished (its app was uninstalled) — drop the stale id and
    // fall back to the empty state.
    val stale = infoLoaded && widgetId != NONE && info == null
    LaunchedEffect(stale) {
        if (stale) {
            WidgetStore.clear(context)
            refreshKey++
        }
    }

    if (infoLoaded && !stale) {
        val boundInfo = info
        if (boundInfo == null) {
            AddRow(label = "add widget", onClick = picker.open)
        } else {
            BoundWidget(
                widgetId = widgetId,
                info = boundInfo,
                modifier = modifier,
                onLongPress = { showManage = true },
            )
        }
    }

    if (showManage) {
        WidgetManageSheet(
            onDismiss = { showManage = false },
            onChange = {
                showManage = false
                picker.open()
            },
            onRemove = {
                showManage = false
                WidgetStore.clear(context)
                refreshKey++
            },
        )
    }

    if (picker.expanded) {
        WidgetChooserSheet(picker = picker)
    }
}

/**
 * The bound widget itself, in a card matching [DateTimeWidget]'s
 * `CalendarCard`. Long-press is wired straight onto the hosted
 * [AppWidgetHostView] (an [android.view.View.OnLongClickListener], not a
 * Compose `combinedClickable`) so ordinary taps still reach the widget's own
 * buttons and links instead of being swallowed by a Compose click handler
 * sitting on top of it.
 */
@Composable
private fun BoundWidget(
    widgetId: Int,
    info: AppWidgetProviderInfo,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val height = info.minHeight.dp.coerceIn(WIDGET_MIN_HEIGHT, WIDGET_MAX_HEIGHT)
    // The card fills the page width (fillMaxWidth, 20dp page padding each
    // side) — tell the provider that up front so responsive-layout widgets
    // pick a RemoteViews variant that actually fits, instead of their
    // narrowest one.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val widthBudget = (screenWidthDp - PAGE_HORIZONTAL_PADDING).coerceAtLeast(info.minWidth)

    Surface(
        color = UglyTheme.colors.surface,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        // Rekeyed on widgetId so swapping widgets tears down the old host
        // view and creates a fresh one, rather than reusing a stale binding.
        key(widgetId) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().heightIn(min = height, max = WIDGET_MAX_HEIGHT),
                factory = { context ->
                    val host = WidgetHost.host(context)
                    host.createView(context, widgetId, info).apply {
                        setAppWidget(widgetId, info)
                        setOnLongClickListener { onLongPress(); true }
                        AppWidgetManager.getInstance(context).updateAppWidgetOptions(
                            widgetId,
                            Bundle().apply {
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, info.minWidth)
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthBudget)
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height.value.toInt())
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height.value.toInt())
                            },
                        )
                    }
                },
                update = { hostView -> hostView.setOnLongClickListener { onLongPress(); true } },
            )
        }
    }
}

/** The long-press sheet: swap the widget or drop it entirely. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetManageSheet(
    onDismiss: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = UglyTheme.colors
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SheetRow(label = "change widget", color = colors.accent, onClick = onChange)
            SheetRow(label = "remove", color = colors.error, onClick = onRemove)
        }
    }
}

@Composable
private fun SheetRow(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 16.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** The picker itself: every installed widget, grouped by its app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetChooserSheet(picker: WidgetPickerState) {
    val colors = UglyTheme.colors
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = picker.dismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
    ) {
        if (picker.choices.isEmpty()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Hint(text = "no widgets installed")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                items(picker.choices, key = { it.info.provider.flattenToString() }) { choice ->
                    WidgetChoiceRow(choice = choice, onClick = { picker.choose(choice) })
                }
            }
        }
    }
}

@Composable
private fun WidgetChoiceRow(choice: WidgetChoice, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = choice.widgetLabel,
            color = colors.foreground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = choice.appLabel,
            color = colors.mutedForeground,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
