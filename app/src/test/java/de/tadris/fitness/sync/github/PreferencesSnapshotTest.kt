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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesSnapshotTest {

    @Test
    fun roundTripKeepsTypesAndValues() {
        val source = FakePreferences(
            "weight" to 72,
            "stepLength" to 0.75f,
            "autoPause" to true,
            "dateFormat" to "ISO",
            "lastTimestamp" to 1234567890123L,
            "infoSlots" to setOf("b", "a"),
        )
        val target = FakePreferences()

        PreferencesSnapshot.apply(target, PreferencesSnapshot.take(source))

        assertEquals(source.all, target.all)
    }

    @Test
    fun deviceSpecificAndSecretKeysAreLeftOut() {
        val source = FakePreferences(
            "weight" to 72,
            "accessToken" to "secret",
            "offlineMapDirectoryName" to "maps",
            "exportDirectoryUri" to "content://x",
            "bluetoothDevice" to "AA:BB",
            "firstStart" to false,
            "lastVersion" to 42,
        )

        val target = FakePreferences()
        PreferencesSnapshot.apply(target, PreferencesSnapshot.take(source))

        assertEquals(mapOf<String, Any?>("weight" to 72), target.all)
    }

    @Test
    fun applyKeepsEntriesThatAreNotInTheSnapshot() {
        val target = FakePreferences("weight" to 80, "autoPause" to false)

        PreferencesSnapshot.apply(target, PreferencesSnapshot.take(FakePreferences("weight" to 70)))

        assertEquals(70, target.all["weight"])
        assertEquals(false, target.all["autoPause"])
    }

    @Test
    fun snapshotDoesNotDependOnInsertionOrder() {
        val a = FakePreferences("weight" to 70, "autoPause" to true)
        val b = FakePreferences("autoPause" to true, "weight" to 70)

        assertEquals(PreferencesSnapshot.take(a), PreferencesSnapshot.take(b))
    }

    @Test
    fun hashChangesWithContent() {
        assertEquals(PreferencesSnapshot.hash("a"), PreferencesSnapshot.hash("a"))
        assertNotEquals(PreferencesSnapshot.hash("a"), PreferencesSnapshot.hash("b"))
        assertTrue(PreferencesSnapshot.isSynced("weight"))
        assertFalse(PreferencesSnapshot.isSynced("refreshToken"))
    }

    private class FakePreferences(vararg initial: Pair<String, Any>) : SharedPreferences {

        private val values = linkedMapOf<String, Any?>(*initial)

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()

            private fun put(key: String, value: Any?) = apply { pending[key] = value }

            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String) = put(key, null)
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                pending.clear()
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
