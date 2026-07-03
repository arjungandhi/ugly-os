package com.uglyos.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.uglyos.common.theme.UglyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The app drawer: a swipe-up grid of every installed app for launching and
 * managing. It's an overlay rather than a pager page — [Home] parks it just off
 * the bottom and a vertical drag on the home screen pulls it up. Tap launches,
 * long-press opens a sheet to rename, tag, or uninstall.
 *
 * Apps show as a single-color glyph so the grid stays in the Nord palette and
 * nothing shouts: an app's OS monochrome icon layer where it ships one, else a
 * monogram of its first letter. See [AppGlyph].
 */

/** Past the drawer's midpoint counts as open when a drag ends without a flick. */
private const val OPEN_THRESHOLD = 0.5f
/** A short drag — this fraction of the screen — commits even without a flick. */
private const val COMMIT_FRACTION = 0.15f
/** Upward/downward speed (px/s) that flicks the drawer open/closed on release. */
private const val FLING_VELOCITY = 700f

/**
 * A vertical drag that opens or closes the drawer, driving [offset] to follow
 * the finger. On release it commits on either a flick (velocity) or a short drag
 * (~15% of the screen) in the drag's direction, so neither opening nor closing
 * needs a long haul; a small, slow nudge just snaps back. Shared by the home
 * page (to pull the drawer up) and the drawer's own grab handle (to push it
 * down) so both feel identical.
 */
fun Modifier.drawerDrag(
    offset: Animatable<Float, AnimationVector1D>,
    heightPx: Float,
    scope: CoroutineScope,
): Modifier = this.pointerInput(heightPx) {
    val tracker = VelocityTracker()
    var start = offset.value
    detectVerticalDragGestures(
        onDragStart = {
            start = offset.value
            tracker.resetTracking()
        },
        onVerticalDrag = { change, dragAmount ->
            tracker.addPointerInputChange(change)
            scope.launch { offset.snapTo((offset.value + dragAmount).coerceIn(0f, heightPx)) }
            change.consume()
        },
        onDragEnd = {
            val velocity = tracker.calculateVelocity().y
            val moved = offset.value - start // positive = dragged down
            val open = when {
                velocity < -FLING_VELOCITY -> true
                velocity > FLING_VELOCITY -> false
                moved < -heightPx * COMMIT_FRACTION -> true
                moved > heightPx * COMMIT_FRACTION -> false
                else -> start < heightPx * OPEN_THRESHOLD // no real intent: stay put
            }
            scope.launch { offset.animateTo(if (open) 0f else heightPx, tween(200)) }
        },
    )
}

/** A drawer row: either a letter signpost or one app. */
private sealed interface DrawerItem {
    data class Header(val letter: String) : DrawerItem
    data class App(val app: AppInfo) : DrawerItem
}

/** Flatten the (already name-sorted) apps into letter-headed groups. */
private fun drawerItems(apps: List<AppInfo>): List<DrawerItem> {
    val out = mutableListOf<DrawerItem>()
    var letter: String? = null
    for (app in apps) {
        val l = monogramOf(app.label)
        if (l != letter) {
            out += DrawerItem.Header(l)
            letter = l
        }
        out += DrawerItem.App(app)
    }
    return out
}

/**
 * The drawer overlay. Always in the tree so its content (and icons) are ready
 * before it's needed; positioned by [offset] via a graphics layer so the slide
 * doesn't recompose the grid. Parked off-screen it neither draws nor takes
 * touches, so home stays fully interactive underneath.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(
    offset: Animatable<Float, AnimationVector1D>,
    heightPx: Float,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    val colors = UglyTheme.colors

    var reloadKey by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val glyphs = remember { mutableStateMapOf<String, AppGlyph>() }
    var managing by remember { mutableStateOf<AppInfo?>(null) }

    LaunchedEffect(reloadKey) { apps = withContext(Dispatchers.IO) { loadApps(context) } }

    // Load each app's glyph once, off the main thread, filling tiles in as they
    // resolve. Keyed by package so a reload (after a rename) doesn't re-fetch.
    LaunchedEffect(apps) {
        withContext(Dispatchers.IO) {
            for (app in apps) {
                if (!glyphs.containsKey(app.packageName)) {
                    glyphs[app.packageName] = cachedGlyph(context, app)
                }
            }
        }
    }

    // An uninstall is confirmed on a system screen, so refresh the list (and
    // drop stale overrides) when we come back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reloadKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Settled-open state, derived from the offset without recomposing per frame:
    // only flips when the slide crosses the midpoint.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(heightPx) {
        snapshotFlow { offset.value }.collect { opened = it < heightPx * OPEN_THRESHOLD }
    }

    fun close() = scope.launch { offset.animateTo(heightPx, tween(200)) }

    BackHandler(enabled = opened) { close() }

    // Pull/flick down to close: once the grid is scrolled to the top it has no
    // more to give, so its leftover downward drag (and a downward fling) drives
    // the drawer shut instead — the same feel as the grab handle, from anywhere.
    val gridState = rememberLazyGridState()

    // ...but only when the *drag began* at the top. A drag that scrolls the list
    // up to the top stops there; closing is a fresh downward pull from the top,
    // so a scroll-to-top flick never sails on into a dismiss.
    var dragFromTop by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        gridState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) dragFromTop = !gridState.canScrollBackward
        }
    }

    val dismissScroll = remember(heightPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Drawer already pulled down a bit + dragging back up: re-open first.
                if (available.y < 0 && offset.value > 0f) {
                    val target = (offset.value + available.y).coerceIn(0f, heightPx)
                    val used = target - offset.value
                    scope.launch { offset.snapTo(target) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Downward drag the grid couldn't use (it's at the top) closes —
                // but only a live finger drag, never leftover fling momentum, so a
                // flick that scrolls the list to the top stops there instead of
                // sailing on into a dismiss.
                if (available.y > 0 && source == NestedScrollSource.UserInput && dragFromTop && !gridState.canScrollBackward) {
                    val target = (offset.value + available.y).coerceIn(0f, heightPx)
                    val used = target - offset.value
                    scope.launch { offset.snapTo(target) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset.value > 0f) {
                    val open = when {
                        available.y < -FLING_VELOCITY -> true
                        available.y > FLING_VELOCITY -> false
                        else -> offset.value < heightPx * OPEN_THRESHOLD
                    }
                    offset.animateTo(if (open) 0f else heightPx, tween(200))
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = offset.value }
            .background(colors.background),
    ) {
        // Drawn edge-to-edge and outside the Scaffold, so inset the content
        // ourselves to clear the status and navigation bars.
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            DrawerHandle(offset, heightPx, scope)
            Text(
                text = "apps",
                color = colors.foreground,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val items = remember(apps) { drawerItems(apps) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(dismissScroll),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp,
                ),
            ) {
                items.forEach { item ->
                    when (item) {
                        is DrawerItem.Header -> item(
                            key = "h:${item.letter}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) { LetterHeader(item.letter) }
                        is DrawerItem.App -> item(key = item.app.packageName) {
                            AppCell(
                                app = item.app,
                                glyph = glyphs[item.app.packageName],
                                onClick = {
                                    close()
                                    launchApp(context, item.app.packageName)
                                },
                                onLongClick = { managing = item.app },
                            )
                        }
                    }
                }
            }
        }
    }

    managing?.let { app ->
        ManageSheet(
            app = app,
            onDismiss = { managing = null; reloadKey++ },
            onUninstall = {
                context.startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                )
                managing = null
            },
        )
    }
}

/** The grab bar: a drag here pulls the open drawer back down (or re-settles it). */
@Composable
private fun DrawerHandle(
    offset: Animatable<Float, AnimationVector1D>,
    heightPx: Float,
    scope: CoroutineScope,
) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawerDrag(offset, heightPx, scope)
            .padding(top = 12.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(colors.subtle)
        )
    }
}

/** A full-width letter signpost between groups: dot + tracked uppercase label. */
@Composable
private fun LetterHeader(letter: String) {
    val colors = UglyTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.subtle)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = letter,
            color = colors.mutedForeground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** One app: glyph tile with a customized pip, its name below. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(
    app: AppInfo,
    glyph: AppGlyph?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = UglyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            GlyphTile(glyph)
            if (app.customized) {
                // A quiet pip in the corner: "you've renamed or tagged this".
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.accentMuted)
                )
            }
        }
        Text(
            text = app.label,
            color = colors.foreground,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The long-press sheet: rename, tag, or uninstall one app. Name and tags commit
 * on dismiss (fewer buttons); leaving the name blank or equal to the real label
 * clears the override. Uninstall is the sheet's one colored thing — [error],
 * because it's destructive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageSheet(
    app: AppInfo,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    val sheetState = rememberModalBottomSheetState()

    var name by remember(app.packageName) { mutableStateOf(app.label) }
    var tags by remember(app.packageName) { mutableStateOf(app.tags.joinToString(" ")) }

    fun commitAndDismiss() {
        // A name matching the real label is no override at all.
        AppMeta.setName(context, app.packageName, if (name.trim() == app.systemLabel) "" else name)
        AppMeta.setTags(context, app.packageName, tags)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { commitAndDismiss() },
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
            // The real app label, so a renamed app still says what it actually is.
            Text(
                text = app.systemLabel,
                color = colors.mutedForeground,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            LabeledField(
                label = "name",
                value = name,
                onValueChange = { name = it },
                placeholder = app.systemLabel,
            )
            LabeledField(
                label = "tags",
                value = tags,
                onValueChange = { tags = it },
                placeholder = "space separated",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.subtle)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onUninstall)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.error)
                )
                Text(
                    text = "uninstall",
                    color = colors.error,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** A micro-label signpost above a monospace input styled like the search field. */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val colors = UglyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label.uppercase(),
            color = colors.mutedForeground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surfaceElevated)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
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
                        text = placeholder,
                        color = colors.mutedForeground,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                inner()
            }
        }
    }
}
