package com.uglyos.launcher

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.uglyos.common.theme.UglyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No standard app category fits these, so they're launched by package. */
private const val BEEPER_PACKAGE = "com.beeper.android"
private const val GRAYJAY_PACKAGE = "com.futo.platformplayer"
private const val WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"
private const val HOME_ASSISTANT_PACKAGE = "io.homeassistant.companion.android"

/**
 * The built-in shortcuts used only to seed a fresh dock. Each resolves to an
 * *implicit* intent (an app category or well-known action) so the first-run dock
 * picks up whatever app the user has set as default, rather than a hard-coded
 * package. After seeding, the dock is whatever the user has pinned.
 */
private enum class Shortcut {
    PHONE, MESSAGES, EMAIL, BROWSER, MUSIC, VIDEOS, CAMERA, WALLET, HOME_ASSISTANT,
}

/** Build the seeding intent for a shortcut, or null if none can be resolved. */
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

/** Resolve a shortcut to the concrete, launchable package that handles it. */
private fun Shortcut.resolvePackage(context: Context): String? {
    val intent = intent(context) ?: return null
    val pkg = context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    // "android" is the disambiguation ResolverActivity, not a real target; and we
    // only keep packages we can actually launch, so a pin always opens something.
    return pkg?.takeIf {
        it != "android" && context.packageManager.getLaunchIntentForPackage(it) != null
    }
}

/**
 * First-run dock contents: each built-in shortcut resolved to the app that
 * currently handles it, dropping any nothing can handle. Used once to seed
 * [QuickLaunchStore].
 */
fun defaultPins(context: Context): List<String> =
    Shortcut.entries.mapNotNull { it.resolvePackage(context) }.distinct()

/**
 * Resolve pinned packages to their (possibly renamed) label, dropping any that
 * can't be launched — uninstalled, or still installed but no longer exposing a
 * launcher activity. The launchability test matches [launchApp], so every slug
 * the dock keeps actually opens something.
 */
private fun resolvePins(context: Context, pins: List<String>): List<AppInfo> {
    val pm = context.packageManager
    val names = AppMeta.names(context)
    return pins.mapNotNull { pkg ->
        if (pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
        runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            val system = pm.getApplicationLabel(info).toString()
            AppInfo(label = names[pkg] ?: system, packageName = pkg, systemLabel = system)
        }.getOrNull()
    }
}

/**
 * The home-screen dock: a fixed grid of small monochrome glyphs pinned to stable
 * slots, so an app lives in the same spot every time and you reach for it by
 * position. Deliberately quiet — no tiles, no labels — the clock owns the loud
 * slot, so this is just floating icons in generous space. Tap launches;
 * long-press removes; a hairline `+` in the first open slot pins a new app. The
 * grid size is set in [SettingsPage].
 */
@Composable
fun QuickLaunch(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = UglyTheme.colors

    var rows by remember { mutableStateOf(QuickLaunchStore.rows(context)) }
    var cols by remember { mutableStateOf(QuickLaunchStore.cols(context)) }
    var pins by remember { mutableStateOf(QuickLaunchStore.pins(context)) }
    var infos by remember { mutableStateOf<Map<String, AppInfo>>(emptyMap()) }
    val glyphs = remember { mutableStateMapOf<String, AppGlyph>() }
    var picking by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<AppInfo?>(null) }
    // Packages with a live notification, driven by the notification listener;
    // empty when access isn't granted, so the dots just don't appear.
    val badged by NotificationBadges.packages.collectAsState()
    // Bumped on resume to force a re-resolve even when the pin list is unchanged,
    // so an app uninstalled while we were away gets pruned.
    var reloadKey by remember { mutableIntStateOf(0) }

    fun refresh() {
        rows = QuickLaunchStore.rows(context)
        cols = QuickLaunchStore.cols(context)
        pins = QuickLaunchStore.pins(context)
    }

    // First run: seed the dock off the main thread so composition never blocks on
    // PackageManager. A grid-size change on the settings page needs no watcher —
    // the pager disposes this page while settings is open, so remember re-reads
    // the store when you swipe back.
    LaunchedEffect(Unit) {
        if (!QuickLaunchStore.isSeeded(context)) {
            pins = withContext(Dispatchers.IO) { QuickLaunchStore.seedDefaults(context) }
        }
    }

    // On resume, re-read and force a re-resolve to catch app installs/removals.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { refresh(); reloadKey++ }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Resolve pins to label + glyph off the main thread, pruning any that are no
    // longer launchable (uninstalled or lost their launcher activity) so a dead,
    // un-openable slot doesn't linger.
    LaunchedEffect(pins, reloadKey) {
        val resolved = withContext(Dispatchers.IO) { resolvePins(context, pins) }
        val alive = resolved.map { it.packageName }
        if (alive != pins) {
            QuickLaunchStore.setPins(context, alive)
            pins = alive
        }
        infos = resolved.associateBy { it.packageName }
        withContext(Dispatchers.IO) {
            for (info in resolved) {
                if (!glyphs.containsKey(info.packageName)) {
                    glyphs[info.packageName] = cachedGlyph(context, info)
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "quick launch".uppercase(),
            color = colors.mutedForeground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
        DockGrid(
            rows = rows,
            cols = cols,
            pins = pins,
            glyphOf = { glyphs[it] },
            badgedOf = { it in badged },
            onLaunch = { launchApp(context, it) },
            onManage = { pkg -> infos[pkg]?.let { managing = it } },
            onAdd = { picking = true },
        )
    }

    if (picking) {
        AppPickerSheet(
            exclude = pins.toSet(),
            onPick = { QuickLaunchStore.add(context, it); refresh(); picking = false },
            onDismiss = { picking = false },
        )
    }
    managing?.let { app ->
        ManagePinSheet(
            app = app,
            onRemove = { QuickLaunchStore.remove(context, app.packageName); refresh(); managing = null },
            onDismiss = { managing = null },
        )
    }
}

/**
 * The fixed grid itself. Pins fill left-to-right, top-to-bottom; the first open
 * cell is the `+` (while there's room), and we only lay out as many rows as we
 * need, so an under-filled dock doesn't reserve dead space above the nav bar.
 */
@Composable
private fun DockGrid(
    rows: Int,
    cols: Int,
    pins: List<String>,
    glyphOf: (String) -> AppGlyph?,
    badgedOf: (String) -> Boolean,
    onLaunch: (String) -> Unit,
    onManage: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val capacity = rows * cols
    val shown = pins.take(capacity)
    val hasAdd = shown.size < capacity
    val cellCount = shown.size + if (hasAdd) 1 else 0
    val usedRows = ((cellCount + cols - 1) / cols).coerceIn(1, rows)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (r in 0 until usedRows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        when {
                            i < shown.size -> {
                                val pkg = shown[i]
                                DockCell(
                                    glyph = glyphOf(pkg),
                                    badged = badgedOf(pkg),
                                    onClick = { onLaunch(pkg) },
                                    onLongClick = { onManage(pkg) },
                                )
                            }
                            i == shown.size && hasAdd -> AddCell(onClick = onAdd)
                            else -> {} // empty slot: the weight keeps columns aligned
                        }
                    }
                }
            }
        }
    }
}

/**
 * One pinned app: a floating glyph, tap to launch, long-press to manage. A small
 * accent dot rides the glyph's top-right corner when [badged] — the quiet "you
 * have something here" cue, in-palette rather than a loud red count.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockCell(glyph: AppGlyph?, badged: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Glyph(glyph, size = 48.dp)
            if (badged) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        // Pulled in from the box corner: an adaptive icon's
                        // monochrome layer sits inside a safe-zone margin, so the
                        // visible glyph ends short of the 48dp box — this keeps the
                        // dot on the glyph rather than floating above it.
                        .offset(x = (-4).dp, y = 4.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.background)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                )
            }
        }
    }
}

/** The first empty slot: a hairline `+` that opens the app picker. */
@Composable
private fun AddCell(onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, colors.subtle, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                color = colors.mutedForeground,
                fontSize = 22.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The picker sheet: a search field over the full app list, glyph on the left,
 * name on the right. Tapping a row pins it. Already-pinned apps are excluded so
 * you can't double-add.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    exclude: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val glyphs = remember { mutableStateMapOf<String, AppGlyph>() }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            loadApps(context).filter { it.packageName !in exclude }
        }
        apps = loaded
        withContext(Dispatchers.IO) {
            for (app in loaded) {
                if (!glyphs.containsKey(app.packageName)) {
                    glyphs[app.packageName] = cachedGlyph(context, app)
                }
            }
        }
    }

    val filtered = apps.filter {
        query.isBlank() ||
            it.label.contains(query, ignoreCase = true) ||
            it.tags.any { tag -> tag.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "pin an app",
                color = colors.foreground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
            )
            PickerSearch(query) { query = it }
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                filtered.forEach { app ->
                    PickerRow(
                        label = app.label,
                        glyph = glyphs[app.packageName],
                        onClick = { onPick(app.packageName) },
                    )
                }
            }
        }
    }
}

/** The picker's search field, styled like the launcher's search input. */
@Composable
private fun PickerSearch(value: String, onChange: (String) -> Unit) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceElevated)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.foreground,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        ) { inner ->
            if (value.isEmpty()) {
                Text(
                    text = "search",
                    color = colors.mutedForeground,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            inner()
        }
    }
}

/** One app row in the picker: glyph then name. */
@Composable
private fun PickerRow(label: String, glyph: AppGlyph?, onClick: () -> Unit) {
    val colors = UglyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Glyph(glyph, size = 32.dp)
        Text(
            text = label,
            color = colors.foreground,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The long-press sheet for a pinned app: remove it from the dock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagePinSheet(app: AppInfo, onRemove: () -> Unit, onDismiss: () -> Unit) {
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = app.label,
                color = colors.foreground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onRemove)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.subtle)
                )
                Text(
                    text = "remove from dock",
                    color = colors.foreground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
