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

package de.tadris.fitness.sync

import android.content.SharedPreferences
import org.json.JSONObject

/** Keeps the [SyncBase] in preferences as JSON. */
class PreferencesBaseStore(private val preferences: SharedPreferences, private val key: String) : BaseStore {

    override fun load(): SyncBase {
        val text = preferences.getString(key, null) ?: return SyncBase()
        return try {
            val json = JSONObject(text)
            val files = json.optJSONObject(FILES)
            SyncBase(
                files = files?.keys()?.asSequence()?.associateWith { files.getLong(it) } ?: emptyMap(),
                settings = json.optString(SETTINGS).ifEmpty { null },
            )
        } catch (e: org.json.JSONException) {
            SyncBase()
        }
    }

    override fun save(base: SyncBase) {
        val files = JSONObject()
        for ((path, start) in base.files) files.put(path, start)
        val json = JSONObject().put(FILES, files)
        base.settings?.let { json.put(SETTINGS, it) }
        preferences.edit().putString(key, json.toString()).apply()
    }

    fun clear() = preferences.edit().remove(key).apply()

    private companion object {
        const val FILES = "files"
        const val SETTINGS = "settings"
    }
}
