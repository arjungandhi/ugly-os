package com.uglyos.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** A launchable app, as surfaced by PackageManager. */
data class AppInfo(val label: String, val packageName: String)

/** Query every app that exposes a MAIN/LAUNCHER activity, sorted by name. */
fun loadApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** Launch an app by package name via its default launch intent. */
fun launchApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent != null) {
        context.startActivity(intent)
    } else {
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
 * The swipeable pages, left to right. The first is the home screen; add or
 * reorder entries here and the pager and indicator follow automatically.
 */
private const val PATTERN_CONTEXT = "pattern"

private val pages: List<@Composable () -> Unit> = listOf(
    { HomePage() },
    { TodoPage("todo") { PATTERN_CONTEXT !in it.contexts } },
    { TodoPage("work") { PATTERN_CONTEXT in it.contexts } },
    { SettingsPage() },
)

@Composable
fun Home() {
    val pagerState = rememberPagerState { pages.size }

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
                pages[page]()
            }
            PageIndicator(
                currentPage = pagerState.currentPage,
                pageCount = pages.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

/** The center page: clock up top, quick-launch shortcuts along the bottom. */
@Composable
fun HomePage() {
    Box(modifier = Modifier.fillMaxSize()) {
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
                .padding(bottom = 96.dp)
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
