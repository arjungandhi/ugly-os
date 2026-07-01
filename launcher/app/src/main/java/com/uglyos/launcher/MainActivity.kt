package com.uglyos.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            MaterialTheme {
                Home()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home() {
    val context = LocalContext.current
    var showDrawer by remember { mutableStateOf(false) }
    // Loaded once; we'll make this refresh on package changes in a later pass.
    val apps = remember { loadApps(context) }
    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(onClick = { showDrawer = true }) {
                Text("+", fontSize = 28.sp)
            }
        }
    ) { padding ->
        // Blank home for now — the canvas we'll build the real UI on.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }

    if (showDrawer) {
        ModalBottomSheet(
            onDismissRequest = { showDrawer = false },
            sheetState = sheetState
        ) {
            LazyColumn {
                items(apps) { app ->
                    Text(
                        text = app.label,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                launchApp(context, app.packageName)
                                showDrawer = false
                            }
                            .padding(horizontal = 24.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}
