package com.uglyos.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import com.uglyos.common.theme.UglyTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * A launchable app, as surfaced by PackageManager and overlaid with the user's
 * own [AppMeta]: [label] is what we show and search (their custom name if set,
 * else the [systemLabel]), plus any searchable [tags].
 */
data class AppInfo(
    val label: String,
    val packageName: String,
    val systemLabel: String = label,
    val tags: List<String> = emptyList(),
) {
    /** True once the user has renamed or tagged this app — worth a status pip. */
    val customized: Boolean get() = label != systemLabel || tags.isNotEmpty()
}

/**
 * Query every app that exposes a MAIN/LAUNCHER activity, overlay each with its
 * [AppMeta] override, and sort by the resulting display name.
 */
fun loadApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val names = AppMeta.names(context)
    val tags = AppMeta.tags(context)
    return pm.queryIntentActivities(intent, 0)
        .map {
            val pkg = it.activityInfo.packageName
            val system = it.loadLabel(pm).toString()
            AppInfo(
                label = names[pkg] ?: system,
                packageName = pkg,
                systemLabel = system,
                tags = tags[pkg] ?: emptyList(),
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** Launch an app by package name via its default launch intent. */
fun launchApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        Toast.makeText(context, "Can't launch $packageName", Toast.LENGTH_SHORT).show()
        return
    }
    // The intent can still fail to start if the activity vanished or is protected.
    try {
        context.startActivity(intent)
        Frecency.record(context, "app:$packageName") // feed launch history into search ranking
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Can't launch $packageName", Toast.LENGTH_SHORT).show()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UglyTheme {
                Home()
            }
        }
    }
}

/**
 * The swipeable pages, left to right. Search sits to the *left* of home, so the
 * launcher opens on [HOME_PAGE] and a swipe left reveals search. Add or reorder
 * pages in [Home]'s dispatch below and bump [PAGE_COUNT] to match.
 */
private const val PATTERN_CONTEXT = "pattern"
private const val SEARCH_PAGE = 0
private const val HOME_PAGE = 1
private const val PAGE_COUNT = 5

@Composable
fun Home() {
    val pagerState = rememberPagerState(initialPage = HOME_PAGE) { PAGE_COUNT }
    val scope = rememberCoroutineScope()

    // The app drawer is an overlay, not a page: it slides up over whatever's
    // below. [drawerOffset] is its vertical shift in px — full height is closed
    // (parked just off the bottom), 0 is fully open. Keyed to the measured
    // height so a rotation re-parks it correctly.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat()
        val drawerOffset = remember(heightPx) { Animatable(heightPx) }

        Scaffold(
            containerColor = UglyTheme.colors.background,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        SEARCH_PAGE -> SearchPage(isActive = pagerState.currentPage == SEARCH_PAGE)
                        HOME_PAGE -> HomePage(
                            modifier = Modifier.drawerDrag(drawerOffset, heightPx, scope)
                        )
                        2 -> TodoPage("todo") { PATTERN_CONTEXT !in it.contexts }
                        3 -> TodoPage("work", hiddenContext = PATTERN_CONTEXT) { PATTERN_CONTEXT in it.contexts }
                        else -> SettingsPage()
                    }
                }
                PageIndicator(
                    currentPage = pagerState.currentPage,
                    pageCount = PAGE_COUNT,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )
            }
        }

        AppDrawer(offset = drawerOffset, heightPx = heightPx, scope = scope)
    }
}

/** The center page: clock up top, quick-launch shortcuts along the bottom. */
@Composable
fun HomePage(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        DateTimeWidget(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 48.dp)
        )
        QuickLaunch(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 56.dp)
        )
    }
}

/** A row of dots marking which page is currently in view. */
@Composable
fun PageIndicator(currentPage: Int, pageCount: Int, modifier: Modifier = Modifier) {
    val colors = UglyTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(pageCount) { page ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (page == currentPage) colors.foreground else colors.surface)
            )
        }
    }
}
