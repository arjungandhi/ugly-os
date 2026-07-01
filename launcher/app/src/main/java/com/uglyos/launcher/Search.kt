package com.uglyos.launcher

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.uglyos.common.theme.UglyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Global, Spotlight-style search.
 *
 * Search is built around independent *providers*: each turns the query string
 * into a list of ranked [SearchResult]s. To add a new searchable source (todos,
 * files, ...) write another provider function and add it to [runSearch] — the UI
 * groups and renders whatever comes back, so nothing else has to change.
 */

/** Where a result came from; also the section header it's grouped under. */
enum class ResultSource(val label: String) {
    APP("apps"),
    SETTING("settings"),
    CONTACT("contacts"),
    WEB("web"),
}

/** One ranked hit. [score] orders results within a source; [onSelect] acts on it. */
data class SearchResult(
    val title: String,
    val subtitle: String?,
    val source: ResultSource,
    val score: Int,
    val onSelect: (Context) -> Unit,
)

private val WORD_SEP = charArrayOf(' ', '.', '-', '_', '/')

/**
 * Score how well [text] matches [query], 0 meaning no match. Tiers (exact >
 * prefix > word-start > substring > loose subsequence) are spaced far apart so
 * match *quality* dominates. Within a tier a shorter target wins (a full-word
 * "maps" beats "map my walk" for "map"), and loose subsequence hits are graded
 * by how tightly they land so acronyms like "gm" → "Google Maps" rank high.
 */
private fun matchScore(text: String, query: String): Int {
    val t = text.lowercase()
    val q = query.lowercase()
    if (q.isEmpty()) return 0

    val tier = when {
        t == q -> 1000
        t.startsWith(q) -> 800
        wordStarts(t).any { it.startsWith(q) } -> 600
        t.contains(q) -> 400
        else -> {
            val sub = subsequenceScore(t, q)
            if (sub < 0) return 0
            200 + sub
        }
    }
    // Prefer shorter targets: the more of the title the query covers, the tighter.
    val lengthBonus = (20.0 * q.length / t.length).toInt()
    return tier + lengthBonus
}

/** The word-start substrings of [t]: the whole string plus each char after a separator. */
private fun wordStarts(t: String): List<String> {
    val starts = mutableListOf(t)
    for (i in 1 until t.length) {
        if (t[i - 1] in WORD_SEP) starts += t.substring(i)
    }
    return starts
}

/**
 * Graded subsequence match: -1 if not every char of [q] appears in [t] in order,
 * otherwise a small bonus (higher = tighter) rewarding consecutive runs and
 * chars landing on word boundaries, so acronyms and initials rank near the top
 * of the loose-match tier.
 */
private fun subsequenceScore(t: String, q: String): Int {
    var qi = 0
    var score = 0
    var prevMatch = -2
    for (ti in t.indices) {
        if (qi >= q.length) break
        if (t[ti] == q[qi]) {
            if (ti == prevMatch + 1) score += 8 // consecutive with previous match
            if (ti == 0 || t[ti - 1] in WORD_SEP) score += 10 // word boundary
            prevMatch = ti
            qi++
        }
    }
    if (qi < q.length) return -1
    return score.coerceAtMost(60)
}

/**
 * Installed apps, matched against their launcher label. A bounded frecency bonus
 * (log-scaled so heavy use can't run away, capped below one tier gap) lifts apps
 * you actually open among comparable matches without crossing a match tier.
 */
private fun searchApps(
    apps: List<AppInfo>,
    query: String,
    frecency: Map<String, Double>,
): List<SearchResult> =
    apps.mapNotNull { app ->
        val score = matchScore(app.label, query)
        if (score == 0) null
        else SearchResult(app.label, "app", ResultSource.APP, score + Frecency.boost(frecency["app:${app.packageName}"])) {
            launchApp(it, app.packageName)
        }
    }

/** A jump into a system settings screen. */
private data class SettingEntry(val label: String, val action: String, val keywords: List<String>)

private val SETTING_ENTRIES = listOf(
    SettingEntry("wi-fi", AndroidSettings.ACTION_WIFI_SETTINGS, listOf("wifi", "internet", "network")),
    SettingEntry("bluetooth", AndroidSettings.ACTION_BLUETOOTH_SETTINGS, listOf()),
    SettingEntry("mobile data", AndroidSettings.ACTION_DATA_ROAMING_SETTINGS, listOf("data", "cellular", "sim")),
    SettingEntry("airplane mode", AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("flight")),
    SettingEntry("display", AndroidSettings.ACTION_DISPLAY_SETTINGS, listOf("brightness", "screen")),
    SettingEntry("sound", AndroidSettings.ACTION_SOUND_SETTINGS, listOf("volume", "audio", "ringtone")),
    SettingEntry("battery", AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS, listOf("power")),
    SettingEntry("location", AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("gps")),
    SettingEntry("apps", AndroidSettings.ACTION_APPLICATION_SETTINGS, listOf("applications")),
    SettingEntry("all settings", AndroidSettings.ACTION_SETTINGS, listOf("system")),
)

/** System settings screens, matched against label and keyword aliases. */
private fun searchSettings(query: String, frecency: Map<String, Double>): List<SearchResult> =
    SETTING_ENTRIES.mapNotNull { entry ->
        val score = (listOf(entry.label) + entry.keywords).maxOf { matchScore(it, query) }
        if (score == 0) null
        else SearchResult(entry.label, "setting", ResultSource.SETTING, score + Frecency.boost(frecency["setting:${entry.action}"])) { ctx ->
            try {
                ctx.startActivity(Intent(entry.action))
            } catch (e: ActivityNotFoundException) {
                ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
            }
            Frecency.record(ctx, "setting:${entry.action}")
        }
    }

private fun hasContactsPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

/** Device contacts, matched by name via the contacts provider's own filter. */
private fun searchContacts(context: Context, query: String, frecency: Map<String, Double>): List<SearchResult> {
    if (!hasContactsPermission(context)) return emptyList()
    val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
    val cols = arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME)
    val results = mutableListOf<SearchResult>()
    context.contentResolver.query(uri, cols, null, null, "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT 8")
        ?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            val lookupIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                val lookup = cursor.getString(lookupIdx) ?: continue
                val contactUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookup)
                // The provider already fuzzy-matched; keep its order but rank exact
                // name hits above the rest, then let frecency break ties between
                // equally-good matches by whom you actually open.
                val score = matchScore(name, query).coerceAtLeast(40) + Frecency.boost(frecency["contact:$lookup"])
                results += SearchResult(name, "contact", ResultSource.CONTACT, score) { ctx ->
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, contactUri))
                    Frecency.record(ctx, "contact:$lookup")
                }
            }
        }
    return results
}

/** Always-present escape hatches so the box is never a dead end. */
private fun webFallback(query: String): List<SearchResult> = listOf(
    SearchResult("search the web", "\"$query\"", ResultSource.WEB, 1) { ctx ->
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val url = "https://www.google.com/search?q=" + Uri.encode(query)
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    },
    SearchResult("search play store", "\"$query\"", ResultSource.WEB, 0) { ctx ->
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query))))
        } catch (e: ActivityNotFoundException) {
            val url = "https://play.google.com/store/search?q=" + Uri.encode(query)
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    },
)

/**
 * Fan the query out to every provider, merge, and rank globally so the single
 * best hit — across apps, settings, contacts and web — leads the list. Ties
 * break toward shorter (more specific) titles, then alphabetically for stability.
 */
private fun runSearch(context: Context, apps: List<AppInfo>, query: String): List<SearchResult> {
    val frecency = Frecency.scores(context)
    return (searchApps(apps, query, frecency) +
        searchSettings(query, frecency) +
        searchContacts(context, query, frecency) +
        webFallback(query))
        .sortedWith(
            compareByDescending<SearchResult> { it.score }
                .thenBy { it.title.length }
                .thenBy { it.title.lowercase() }
        )
}

/**
 * The search page, sitting to the left of home. When it scrolls into view
 * ([isActive]) it grabs focus and opens the keyboard; when it leaves it clears
 * the query so it's empty next time.
 */
@Composable
fun SearchPage(isActive: Boolean) {
    val context = LocalContext.current
    val colors = UglyTheme.colors
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var hasContacts by remember { mutableStateOf(hasContactsPermission(context)) }

    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasContacts = granted }

    // Enumerating apps hits PackageManager, so do it once off the main thread.
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(context) } }

    // Focus + keyboard follow whether the page is the one on screen. Also ask
    // for contacts access the first time the user actually opens search.
    LaunchedEffect(isActive) {
        if (isActive) {
            if (!hasContacts) contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            delay(100)
            runCatching { focusRequester.requestFocus() }
        } else {
            query = ""
            focusManager.clearFocus()
        }
    }

    // Re-run the search when the query (or contacts access, or app list) changes.
    LaunchedEffect(query, hasContacts, apps) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
        } else {
            delay(120) // debounce keystrokes
            results = withContext(Dispatchers.IO) { runSearch(context, apps, q) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SearchField(
            query = query,
            onQueryChange = { query = it },
            onGo = {
                results.firstOrNull()?.let {
                    keyboard?.hide()
                    it.onSelect(context)
                }
            },
            focusRequester = focusRequester,
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val topHit = results.firstOrNull()
            if (topHit != null) {
                item { SectionHeader("top hit") }
                item {
                    ResultRow(topHit.title, topHit.subtitle, highlighted = true) {
                        keyboard?.hide()
                        topHit.onSelect(context)
                    }
                }
            }
            // Remaining hits, grouped by source with the strongest sources first.
            val rest = results.drop(1)
            ResultSource.entries
                .map { source -> source to rest.filter { it.source == source } }
                .filter { (_, group) -> group.isNotEmpty() }
                .sortedByDescending { (_, group) -> group.maxOf { it.score } }
                .forEach { (source, group) ->
                    item { SectionHeader(source.label) }
                    items(group) { result ->
                        ResultRow(result.title, result.subtitle) {
                            keyboard?.hide()
                            result.onSelect(context)
                        }
                    }
                }
        }
    }
}

/** The monospace query input, styled to match the launcher's blocky look. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onGo: () -> Unit,
    focusRequester: FocusRequester,
) {
    val colors = UglyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.foreground,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onGo() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        ) { inner ->
            if (query.isEmpty()) {
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

/** A dimmed, uppercase label separating result groups. */
@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label.uppercase(),
        color = UglyTheme.colors.mutedForeground,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

/**
 * One tappable result: title on top, source/detail below. The [highlighted] top
 * hit gets a filled card and accent title to signal it's what Enter will open.
 */
@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = UglyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlighted) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                } else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (highlighted) 12.dp else 0.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            color = if (highlighted) colors.accent else colors.foreground,
            fontSize = 16.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = colors.mutedForeground,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
