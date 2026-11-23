/*
 * Copyright (c) 2026 barton-muller
 *
 * This file is part of FitoTrack
 *
 * FitoTrack is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     FitoTrack is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tadris.fitness.sync.github

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.TreeMap

/**
 * The app preferences, including the personal measurements (weight, step length), as the text of settings.json.
 * Every entry keeps its type, so it can be written back as it was.
 */
object PreferencesSnapshot {

    /** Keys that belong to this device or are secret: tokens, content URIs, directories, Bluetooth devices, first-run flags. */
    private val excluded = Regex("(?i)token|secret|auth|uri|director|path|file|bluetooth|device|lastversion|showing|firststart|prompt|autostarted|^_")

    fun isSynced(key: String) = !excluded.containsMatchIn(key)

    fun take(preferences: SharedPreferences): String {
        val sorted = TreeMap<String, Any>()
        for ((key, value) in preferences.all) if (value != null && isSynced(key)) sorted[key] = value
        val out = JSONObject()
        for ((key, value) in sorted) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> entry.put(TYPE, "b").put(VALUE, value)
                is Int -> entry.put(TYPE, "i").put(VALUE, value)
                is Long -> entry.put(TYPE, "l").put(VALUE, value)
                is Float -> entry.put(TYPE, "f").put(VALUE, value.toDouble())
                is String -> entry.put(TYPE, "s").put(VALUE, value)
                is Set<*> -> entry.put(TYPE, "S").put(VALUE, JSONArray(value.map { it.toString() }.sorted()))
                else -> continue
            }
            out.put(key, entry)
        }
        return out.toString(2)
    }

    /** Writes the entries of [text] into [preferences]. Entries that are not in [text] stay as they are. */
    fun apply(preferences: SharedPreferences, text: String) {
        val json = JSONObject(text)
        val editor = preferences.edit()
        for (key in json.keys()) {
            if (!isSynced(key)) continue
            val entry = json.getJSONObject(key)
            when (entry.getString(TYPE)) {
                "b" -> editor.putBoolean(key, entry.getBoolean(VALUE))
                "i" -> editor.putInt(key, entry.getInt(VALUE))
                "l" -> editor.putLong(key, entry.getLong(VALUE))
                "f" -> editor.putFloat(key, entry.getDouble(VALUE).toFloat())
                "s" -> editor.putString(key, entry.getString(VALUE))
                "S" -> editor.putStringSet(key, entry.getJSONArray(VALUE).let { a -> (0 until a.length()).map(a::getString).toSet() })
            }
        }
        editor.apply()
    }

    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val TYPE = "t"
    private const val VALUE = "v"
}
