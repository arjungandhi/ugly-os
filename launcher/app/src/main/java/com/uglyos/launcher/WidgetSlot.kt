package com.uglyos.launcher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * The currently-bound widget's id/[AppWidgetProviderInfo], re-read whenever
 * [refresh] is called or the host resumes (same `ON_RESUME`-refresh pattern
 * as [NotesPage]/[TodoPage] — [WidgetSlot] and [WidgetSettingsRow] each hold
 * their own instance of this, on different pager pages, with no state
 * shared between them, so a change made in one only reaches the other via
 * this resume hook, not automatically). Also detects and clears a stale id
 * — its provider's app got uninstalled — so every reader sees the empty
 * state, not a crash.
 */
private class BoundWidget(val widgetId: Int, val info: AppWidgetProviderInfo?, val refresh: () -> Unit)

@Composable
private fun rememberBoundWidget(): BoundWidget {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val widgetId = remember(refreshKey) { WidgetStore.widgetId(context) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // AppWidgetManager.getAppWidgetInfo is a Binder call to system_server —
    // off the main thread, like every other IPC-backed read in this app.
    var info by remember(widgetId) { mutableStateOf<AppWidgetProviderInfo?>(null) }
    var loaded by remember(widgetId) { mutableStateOf(widgetId == NONE) }
    LaunchedEffect(widgetId) {
        info = if (widgetId == NONE) null else withContext(Dispatchers.IO) {
            AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)
        }
        loaded = true
    }

    // The provider vanished (its app was uninstalled) — drop the stale id.
    val stale = loaded && widgetId != NONE && info == null
    LaunchedEffect(stale) {
        if (stale) {
            WidgetStore.clear(context)
            refreshKey++
        }
    }

    return BoundWidget(widgetId, info = if (stale) null else info) { refreshKey++ }
}

/**
 * The hosted third-party widget on the home page — nothing shown until one's
 * been picked in settings, then its live view in a card matching
 * [DateTimeWidget]'s `CalendarCard`. Long-press to swap or remove it.
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

    var showManage by remember { mutableStateOf(false) }
    val bound = rememberBoundWidget()
    val picker = rememberWidgetPicker(onDone = { bound.refresh() })

    val info = bound.info
    if (info != null) {
        BoundWidgetView(
            widgetId = bound.widgetId,
            info = info,
            modifier = modifier,
            onLongPress = { showManage = true },
        )
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
                bound.refresh()
            },
        )
    }

    if (picker.expanded) {
        WidgetChooserSheet(picker = picker)
    }
}

/**
 * The "widget" row in settings: current widget's name (or "tap to add"),
 * tapping either opens the picker (nothing bound) or the manage sheet to
 * swap/remove (something is). This is the only place a *new* widget gets
 * added — the home page itself only ever shows what's already picked here,
 * no standing "add widget" affordance cluttering it.
 */
@Composable
fun WidgetSettingsRow() {
    val context = LocalContext.current
    var showManage by remember { mutableStateOf(false) }
    val bound = rememberBoundWidget()
    val picker = rememberWidgetPicker(onDone = { bound.refresh() })

    var label by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bound.info) {
        val info = bound.info
        label = if (info == null) null else withContext(Dispatchers.IO) { widgetDisplayLabel(context, info) }
    }

    SettingRow(
        label = "widget",
        value = label ?: "tap to add",
        configured = label != null,
        onClick = { if (bound.info != null) showManage = true else picker.open() },
    )

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
                bound.refresh()
            },
        )
    }

    if (picker.expanded) {
        WidgetChooserSheet(picker = picker)
    }
}

/**
 * The bound widget's view. Long-press is wired straight onto the hosted
 * [AppWidgetHostView] (an [android.view.View.OnLongClickListener], not a
 * Compose `combinedClickable`) so ordinary taps still reach the widget's own
 * buttons and links instead of being swallowed by a Compose click handler
 * sitting on top of it.
 *
 * Sized at the provider's own declared default footprint
 * ([AppWidgetProviderInfo.minWidth]/`minHeight`), not stretched to fill the
 * page — [AppWidgetProviderInfo.resizeMode] only says a widget *can* be
 * resized by dragging its handles on a real launcher (most Google widgets
 * set it even when their default is a small square), it isn't a request to
 * be maximized on add. A widget whose own default already spans the full
 * page (a calendar, a weather strip) naturally ends up full-width and gets
 * our card treatment, matching [DateTimeWidget]'s `CalendarCard`; a smaller
 * one (a toggle, a photo frame — most already draw their own rounded card
 * and background) keeps its natural size, centered, with no painted surface
 * of ours behind it — otherwise it reads as a mismatched card floating
 * inside a bigger, emptier one.
 */
@Composable
private fun BoundWidgetView(
    widgetId: Int,
    info: AppWidgetProviderInfo,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // The real, hard ceiling — unlike the old fillMaxWidth() rendering, width
    // is now a literal Modifier.width() below, so this must never exceed
    // what's actually on screen (a provider declaring a huge minWidth can't
    // be allowed to overflow the page padding).
    val widthBudget = (screenWidthDp - PAGE_HORIZONTAL_PADDING).coerceAtLeast(WIDGET_MIN_HEIGHT.value.toInt())
    val fillsPage = info.minWidth >= widthBudget

    val width: Dp
    val height: Dp
    if (fillsPage) {
        width = widthBudget.dp
        height = info.minHeight.dp.coerceIn(WIDGET_MIN_HEIGHT, WIDGET_MAX_HEIGHT)
    } else {
        // Scale the widget's natural footprint uniformly to fit our box,
        // instead of clamping width and height independently — that would
        // squash a square (or any non-4:1-ish) widget's aspect ratio, which
        // most widgets fight by padding their own content back to square,
        // reintroducing the empty space we're trying to avoid. Scales up a
        // tiny widget to stay tappable, and down a large one to fit.
        val minSide = WIDGET_MIN_HEIGHT.value
        val maxSide = WIDGET_MAX_HEIGHT.value
        val naturalMax = maxOf(info.minWidth, info.minHeight).toFloat()
        val scale = when {
            naturalMax > maxSide -> maxSide / naturalMax
            naturalMax < minSide -> minSide / naturalMax
            else -> 1f
        }
        width = (info.minWidth * scale).dp.coerceAtMost(widthBudget.dp)
        height = (info.minHeight * scale).dp
    }

    val hostViewModifier = Modifier.width(width).height(height).let {
        if (fillsPage) {
            it.clip(RoundedCornerShape(28.dp)).background(UglyTheme.colors.surface)
        } else {
            it.clip(RoundedCornerShape(20.dp))
        }
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Rekeyed on widgetId so swapping widgets tears down the old host
        // view and creates a fresh one, rather than reusing a stale binding.
        key(widgetId) {
            AndroidView(
                modifier = hostViewModifier,
                factory = { context ->
                    val host = WidgetHost.host(context)
                    host.createView(context, widgetId, info).apply {
                        setAppWidget(widgetId, info)
                        setOnLongClickListener { onLongPress(); true }
                        // We don't support drag-resizing, so tell the
                        // provider its bounds are exactly what we're
                        // actually giving it, in both dimensions.
                        AppWidgetManager.getInstance(context).updateAppWidgetOptions(
                            widgetId,
                            Bundle().apply {
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width.value.toInt())
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width.value.toInt())
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
