package com.uglyos.launcher

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/*
 * Settings backup: dumps the launcher's SharedPreferences files to one JSON
 * file in Documents, and loads them back. The file lives at a fixed path so it
 * survives an uninstall (all-files access lets us write there) and Syncthing
 * can carry it between devices.
 *
 * Format: {"version":1,"prefs":{"<file>":{"<key>":{"type":"...","value":...}}}}
 * where type is string|int|long|float|boolean|stringset. Types are kept so the
 * values read back exactly as the stores wrote them. home_widget is left out on
 * purpose: its widget id means nothing after a reinstall.
 */
object BackupStore {
    private const val VERSION = 1

    /** The pref files that make up a backup. */
    private val PREF_FILES =
        listOf("ugly_launcher", "app_meta", "frecency", "quick_launch", "todo_modes")

    /** Where the backup lives. */
    val file: File =
        File(Environment.getExternalStorageDirectory(), "Documents/ugly-os-backup.json")

    /** Write every pref file to [file] atomically (tmp + rename). */
    fun export(context: Context) {
        val prefs = JSONObject()
        for (name in PREF_FILES) {
            val entries = JSONObject()
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            for ((key, value) in sp.all) {
                entries.put(key, encode(value) ?: continue)
            }
            prefs.put(name, entries)
        }
        val json = JSONObject().put("version", VERSION).put("prefs", prefs)

        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("could not write ${file.path}")
        }
    }

    /**
     * Replace every pref file with the contents of [file], then kill the process
     * so every store reloads from disk. The whole file is parsed before anything
     * is cleared, so a bad backup changes nothing. Throws if it's missing or
     * malformed.
     */
    fun import(context: Context) {
        if (!file.isFile) throw java.io.FileNotFoundException("no backup at ${file.path}")
        val parsed = try {
            val json = JSONObject(file.readText())
            if (json.getInt("version") != VERSION) error("unsupported backup version")
            val prefs = json.getJSONObject("prefs")
            PREF_FILES.filter { prefs.has(it) }.associateWith { name ->
                val entries = prefs.getJSONObject(name)
                entries.keys().asSequence().associateWith { decode(entries.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("malformed backup: ${e.message}", e)
        }

        for ((name, values) in parsed) {
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in values) {
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value as Set<String>)
                }
            }
            editor.commit()
        }
        exitProcess(0)
    }

    /** A pref value as a {"type","value"} object, or null for an unknown type. */
    private fun encode(value: Any?): JSONObject? {
        val (type, json) = when (value) {
            is String -> "string" to value
            is Int -> "int" to value
            is Long -> "long" to value
            // Via the shortest decimal string so 1.1780831f isn't widened to noise.
            is Float -> "float" to value.toString().toDouble()
            is Boolean -> "boolean" to value
            is Set<*> -> "stringset" to JSONArray(value.map { it.toString() })
            else -> return null
        }
        return JSONObject().put("type", type).put("value", json)
    }

    /** The inverse of [encode]. */
    private fun decode(obj: JSONObject): Any = when (val type = obj.getString("type")) {
        "string" -> obj.getString("value")
        "int" -> obj.getInt("value")
        "long" -> obj.getLong("value")
        "float" -> obj.getDouble("value").toFloat()
        "boolean" -> obj.getBoolean("value")
        "stringset" -> obj.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        else -> error("unknown type $type")
    }
}
