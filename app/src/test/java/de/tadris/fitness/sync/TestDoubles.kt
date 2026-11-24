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
import java.io.IOException
import java.security.MessageDigest
import java.util.TreeMap

class MemoryRemote : RemoteStore {
    val files = linkedMapOf<String, ByteArray>()
    val messages = mutableListOf<String>()
    private val blobs = mutableMapOf<String, ByteArray>()
    private var revisions = 0

    /** Runs once, right before the next commit is checked. Lets a test move the remote at the worst moment. */
    var beforeCommit: (() -> Unit)? = null

    val revision get() = if (revisions == 0) null else "r$revisions"

    private fun id(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    override fun fetch(): RemoteSnapshot {
        val ids = files.mapValues { id(it.value) }
        for ((path, bytes) in files) blobs[ids.getValue(path)] = bytes
        return RemoteSnapshot(revision, ids)
    }

    override fun read(contentId: String) = blobs.getValue(contentId)

    override fun commit(expected: String?, changes: Map<String, ByteArray?>, message: String): String {
        beforeCommit?.let { beforeCommit = null; it() }
        if (revision != expected) throw RemoteConflict()
        for ((path, content) in changes) if (content == null) files.remove(path) else files[path] = content
        messages += message
        revisions++
        return revision!!
    }

    /** A commit by somebody else. */
    fun commitElsewhere(changes: Map<String, ByteArray?>) = commit(revision, changes, "elsewhere")
}

class MemoryBase : BaseStore {
    var base = SyncBase()
    override fun load() = base
    override fun save(base: SyncBase) {
        this.base = base
    }
}

/** Workouts are numbers (their start), settings are int preferences. */
class FakeDevice : DeviceSide {
    val workouts = sortedSetOf<Long>()
    val values = TreeMap<String, Int>()

    fun gpx(start: Long) = "gpx:$start".toByteArray()

    override fun workoutStarts(): Set<Long> = workouts.toSet()
    override fun export(start: Long) = WorkoutFile("workout-$start.gpx", gpx(start))

    override fun import(gpx: ByteArray): Pair<Long, Boolean> {
        val text = String(gpx)
        if (!text.startsWith("gpx:")) throw IOException("not a GPX file")
        val start = text.removePrefix("gpx:").toLong()
        return start to workouts.add(start)
    }

    override fun delete(start: Long) {
        workouts.remove(start)
    }

    override fun settings(): String {
        val json = JSONObject()
        for ((k, v) in values) json.put(k, JSONObject().put("t", "i").put("v", v))
        return json.toString(2)
    }

    override fun applySettings(text: String) {
        val json = JSONObject(text)
        for (k in json.keys()) values[k] = json.getJSONObject(k).getInt("v")
    }
}
