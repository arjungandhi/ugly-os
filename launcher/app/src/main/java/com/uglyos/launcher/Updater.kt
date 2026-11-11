package com.uglyos.launcher

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/*
 * Self-update from GitHub releases. A release is a tag vX.Y.Z carrying an APK
 * asset named [ASSET]; its versionCode is X*10000+Y*100+Z. [check] (blocking,
 * throttled, off the main thread) caches the latest release in SharedPreferences,
 * [pending] reads that cache, and [install] downloads the APK and hands it to the
 * system package installer. Nothing is automatic: the user picks "update launcher".
 */
object Updater {
    private const val PREFS = "updater"
    private const val URL_LATEST = "https://api.github.com/repos/arjungandhi/ugly-os/releases/latest"
    private const val ASSET = "uglyos-launcher.apk"
    private const val MIME_APK = "application/vnd.android.package-archive"

    /** Re-check GitHub at most this often. */
    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val downloading = AtomicBoolean(false)

    /** A release newer than the installed build. */
    data class Release(val tag: String, val url: String)

    /** "v1.2.3" -> 10203, or null if the tag isn't in that shape. */
    private fun versionCode(tag: String): Long? {
        val m = Regex("""v?(\d+)\.(\d+)\.(\d+)""").matchEntire(tag.trim()) ?: return null
        val (x, y, z) = m.destructured
        return x.toLong() * 10000 + y.toLong() * 100 + z.toLong()
    }

    private fun ownVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /**
     * Ask GitHub for the latest release and cache it, unless we checked within
     * [CHECK_INTERVAL_MS]. Blocking network call: run it off the main thread.
     * Returns true if a fresh result was stored. Failures leave the cache alone.
     */
    fun check(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now - prefs.getLong("checked", 0L) < CHECK_INTERVAL_MS) return false
        return try {
            val conn = URL(URL_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (conn.responseCode != 200) return false
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val assets = json.getJSONArray("assets")
                var url = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name") == ASSET) url = a.getString("browser_download_url")
                }
                prefs.edit()
                    .putString("tag", json.getString("tag_name"))
                    .putString("url", url)
                    .putLong("checked", now)
                    .apply()
                true
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** The cached release if it's newer than the running build, else null. */
    fun pending(context: Context): Release? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString("tag", null) ?: return null
        val url = prefs.getString("url", null)?.takeIf { it.isNotEmpty() } ?: return null
        val remote = versionCode(tag) ?: return null
        return if (remote > ownVersionCode(context)) Release(tag, url) else null
    }

    /**
     * Download [release]'s APK to the cache dir on a background thread, then open
     * the system installer on it. Silent on failure; ignored while one is running.
     */
    fun install(context: Context, release: Release) {
        val app = context.applicationContext
        if (!downloading.compareAndSet(false, true)) return
        Thread {
            try {
                val file = File(app.cacheDir, "update.apk")
                val conn = URL(release.url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    if (conn.responseCode != 200) return@Thread
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, MIME_APK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // Best effort: the row stays, so the user can just tap it again.
            } finally {
                downloading.set(false)
            }
        }.start()
    }
}
