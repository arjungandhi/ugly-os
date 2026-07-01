package com.uglyos.launcher

import android.content.Context

/*
 * Per-app user overrides — a custom name and searchable tags — kept in
 * SharedPreferences alongside the launcher's other prefs. The system label from
 * PackageManager stays the source of truth; this only records what the user
 * changed, so clearing an override falls straight back to the real label.
 *
 * Keyed by package: "name:<pkg>" -> custom label, "tags:<pkg>" -> a single
 * space-separated string. Blank values are removed rather than stored empty, so
 * "is this app customized?" is just "does a key exist?".
 */
object AppMeta {
    private const val PREFS = "app_meta"
    private const val NAME = "name:"
    private const val TAGS = "tags:"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Custom names by package, read in one pass for merging into the app list. */
    fun names(context: Context): Map<String, String> =
        prefs(context).all.mapNotNull { (k, v) ->
            if (k.startsWith(NAME) && v is String && v.isNotBlank())
                k.removePrefix(NAME) to v else null
        }.toMap()

    /** Tags by package, split from their stored space-separated form. */
    fun tags(context: Context): Map<String, List<String>> =
        prefs(context).all.mapNotNull { (k, v) ->
            if (k.startsWith(TAGS) && v is String) {
                val tags = v.split(" ").filter { it.isNotBlank() }
                if (tags.isNotEmpty()) k.removePrefix(TAGS) to tags else null
            } else null
        }.toMap()

    /** Set (or clear, when blank) the custom name for a package. */
    fun setName(context: Context, pkg: String, name: String) {
        val v = name.trim()
        prefs(context).edit().apply {
            if (v.isEmpty()) remove(NAME + pkg) else putString(NAME + pkg, v)
        }.apply()
    }

    /** Set (or clear, when empty) the tags for a package, normalizing whitespace. */
    fun setTags(context: Context, pkg: String, tags: String) {
        val v = tags.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        prefs(context).edit().apply {
            if (v.isEmpty()) remove(TAGS + pkg) else putString(TAGS + pkg, v.joinToString(" "))
        }.apply()
    }

    /** Drop everything stored for a package, e.g. once it's been uninstalled. */
    fun clear(context: Context, pkg: String) {
        prefs(context).edit().remove(NAME + pkg).remove(TAGS + pkg).apply()
    }
}
