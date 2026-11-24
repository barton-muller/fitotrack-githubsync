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

import org.json.JSONObject
import java.util.TreeMap

/**
 * Three-way merge of two settings snapshots (see PreferencesSnapshot) against the snapshot of the last sync.
 *
 * Per key: a change on one side wins over no change on the other. When both sides changed a key, [preferRemote]
 * decides. A key that only one side has is kept, because a preference that was never written is not a deletion.
 */
object SettingsMerge {

    /**
     * @param base the merged text of the last sync, null when this device has not synced with this remote before
     * @param remote the text in the remote, null when there is none yet
     * @param preferRemote who wins a key that both sides changed. The device wins its own change, except on its first sync
     */
    fun merge(base: String?, local: String, remote: String?, preferRemote: Boolean): String {
        if (remote == null) return local
        val b = entries(base)
        val l = entries(local)
        val r = entries(remote)
        val out = TreeMap<String, String>()
        for (key in l.keys + r.keys) {
            val lv = l[key]
            val rv = r[key]
            out[key] = when {
                lv == null -> rv!!
                rv == null || lv == rv -> lv
                lv == b[key] -> rv
                rv == b[key] -> lv
                preferRemote -> rv
                else -> lv
            }
        }
        val json = JSONObject()
        for ((key, entry) in out) json.put(key, JSONObject(entry))
        return json.toString(2)
    }

    private fun entries(text: String?): Map<String, String> {
        if (text == null) return emptyMap()
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { json.getJSONObject(it).toString() }
    }
}
