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
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMergeTest {

    private fun text(vararg values: Pair<String, Any>): String {
        val json = JSONObject()
        for ((key, value) in values.sortedBy { it.first }) {
            val entry = JSONObject()
            when (value) {
                is Int -> entry.put("t", "i").put("v", value)
                is Boolean -> entry.put("t", "b").put("v", value)
                else -> entry.put("t", "s").put("v", value.toString())
            }
            json.put(key, entry)
        }
        return json.toString(2)
    }

    @Test
    fun withoutARemoteTheLocalSettingsStay() {
        val local = text("weight" to 70)

        assertEquals(local, SettingsMerge.merge(null, local, null, preferRemote = false))
    }

    @Test
    fun aChangeOnOneSideWinsOverNoChangeOnTheOther() {
        val base = text("weight" to 70, "unit" to "metric")

        val merged = SettingsMerge.merge(base, text("weight" to 72, "unit" to "metric"), text("weight" to 70, "unit" to "imperial"), false)

        assertEquals(text("weight" to 72, "unit" to "imperial"), merged)
    }

    @Test
    fun aKeyThatBothSidesChangedGoesByThePreference() {
        val base = text("weight" to 70)
        val local = text("weight" to 71)
        val remote = text("weight" to 72)

        assertEquals(local, SettingsMerge.merge(base, local, remote, preferRemote = false))
        assertEquals(remote, SettingsMerge.merge(base, local, remote, preferRemote = true))
    }

    @Test
    fun aKeyOnlyOneSideHasIsKept() {
        val merged = SettingsMerge.merge(null, text("a" to 1), text("b" to 2), preferRemote = true)

        assertEquals(text("a" to 1, "b" to 2), merged)
    }

    @Test
    fun withoutABaseTheRemoteWinsWhenAsked() {
        val merged = SettingsMerge.merge(null, text("weight" to 99), text("weight" to 70), preferRemote = true)

        assertEquals(text("weight" to 70), merged)
    }

    @Test
    fun mergingTwiceChangesNothing() {
        val base = text("weight" to 70)
        val local = text("weight" to 72, "x" to true)
        val remote = text("weight" to 70, "y" to "z")

        val once = SettingsMerge.merge(base, local, remote, false)

        assertEquals(once, SettingsMerge.merge(once, once, once, false))
    }
}
