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

import java.io.IOException

/** One workout as a GPX file. */
class WorkoutFile(val name: String, val content: ByteArray)

/** What the engine needs from this device. Workouts are identified by their start time. */
interface DeviceSide {

    fun workoutStarts(): Set<Long>

    /** The workout that started at [start] as GPX. */
    fun export(start: Long): WorkoutFile

    /** Adds the workout in [gpx] unless a workout with the same start exists. Returns its start and whether it was added. */
    @Throws(IOException::class)
    fun import(gpx: ByteArray): Pair<Long, Boolean>

    fun delete(start: Long)

    /** Settings and measurements as text, see PreferencesSnapshot. */
    fun settings(): String

    fun applySettings(text: String)
}

/** What the last sync left behind, so that the next one can tell a deletion from something new. */
class SyncBase(
    /** repo path -> start of the workout in that file */
    val files: Map<String, Long> = emptyMap(),
    /** the merged settings text of the last sync */
    val settings: String? = null,
)

interface BaseStore {
    fun load(): SyncBase
    fun save(base: SyncBase)
}

class SyncOptions(
    /** Remove a workout's file from the remote when the workout was deleted on this device. */
    val deleteFromRemote: Boolean = true,
    /** Delete a workout here when its file was deleted from the remote. Otherwise the workout stays here and is not uploaded again. */
    val deleteHereWhenRemoteDeleted: Boolean = false,
    val syncSettings: Boolean = true,
)

enum class SettingsOutcome { OFF, UNCHANGED, SAVED, APPLIED, MERGED }

class SyncReport(
    val imported: Int,
    val uploaded: Int,
    val removedFromRemote: Int,
    val removedHere: Int,
    val failed: Int,
    val firstError: String?,
    val settings: SettingsOutcome,
)

/**
 * Brings the workouts and settings of this device and a [RemoteStore] to the same state.
 *
 * A three-way merge of what the last sync left behind (base), this device and the remote:
 * - a workout that is only here, and was not in the base, is new: it is uploaded
 * - a workout that is only in the remote, and was not in the base, is new: it is imported
 * - a workout that was in the base and is missing on one side was deleted there. The deletion follows to the other
 *   side when the options say so, otherwise the workout stays where it is and is not uploaded or imported again
 * - settings are merged per key, see [SettingsMerge]
 *
 * The result goes to the remote in one commit that is based on the state the merge started from. When the remote moved on in
 * the meantime the commit is refused, and the merge starts again from the new state. Merging twice gives the same result, so a
 * repeat is safe.
 */
class SyncEngine(
    private val remote: RemoteStore,
    private val device: DeviceSide,
    private val baseStore: BaseStore,
    private val options: SyncOptions = SyncOptions(),
    private val folder: String = "fitness",
) {

    private val settingsPath get() = "$folder/settings.json"

    private class Errors {
        var count = 0
        var first: String? = null

        fun add(what: String, e: Exception) {
            count++
            if (first == null) first = "$what: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Throws(IOException::class)
    fun run(): SyncReport {
        var imported = 0
        repeat(MAX_ATTEMPTS) {
            val errors = Errors()
            try {
                val outcome = attempt(errors) { imported += it }
                return SyncReport(
                    imported, outcome.uploaded, outcome.removedFromRemote, outcome.removedHere,
                    errors.count, errors.first, outcome.settings,
                )
            } catch (e: RemoteConflict) {
                // someone else committed in between: fetch and merge again
            }
        }
        throw RemoteConflict("The remote kept changing. Try again.")
    }

    private class Outcome(val uploaded: Int, val removedFromRemote: Int, val removedHere: Int, val settings: SettingsOutcome)

    private fun attempt(errors: Errors, onImported: (Int) -> Unit): Outcome {
        val base = baseStore.load()
        val snapshot = remote.fetch()
        val changes = linkedMapOf<String, ByteArray?>()

        // remote workouts: path -> start
        val remoteStarts = linkedMapOf<String, Long>()
        for ((path, contentId) in snapshot.files) {
            if (!isWorkoutFile(path)) continue
            val known = base.files[path]
            if (known != null) {
                remoteStarts[path] = known
                continue
            }
            try {
                val (start, added) = device.import(remote.read(contentId))
                if (added) onImported(1)
                remoteStarts[path] = start
            } catch (e: Exception) {
                errors.add("import $path", e)
            }
        }
        val remotePathOf = remoteStarts.entries.associate { it.value to it.key }
        val here = device.workoutStarts().toMutableSet()

        val kept = linkedMapOf<String, Long>() // the new base
        var removedFromRemote = 0
        var removedHere = 0
        for ((path, start) in remoteStarts) {
            val inBase = base.files[path] == start
            val inHere = start in here
            when {
                inHere -> kept[path] = start
                inBase && options.deleteFromRemote -> {
                    changes[path] = null
                    removedFromRemote++
                }
                else -> kept[path] = start // deleted here, but the remote file stays and is not imported again
            }
        }
        val orphans = mutableSetOf<Long>()
        for ((path, start) in base.files) {
            if (path in snapshot.files) continue
            // in the base, gone from the remote: deleted there
            when {
                start !in here -> Unit // gone on both sides
                options.deleteHereWhenRemoteDeleted -> {
                    device.delete(start)
                    here.remove(start)
                    removedHere++
                }
                else -> {
                    orphans += start // stays here, and is not uploaded again
                    kept[path] = start
                }
            }
        }

        var uploaded = 0
        val taken = snapshot.files.keys.toMutableSet()
        for (start in here) {
            if (start in remotePathOf || start in orphans) continue
            try {
                val file = device.export(start)
                var path = "$folder/${file.name}"
                if (path in taken) path = path.removeSuffix(".gpx") + "-$start.gpx"
                taken += path
                changes[path] = file.content
                kept[path] = start
                uploaded++
            } catch (e: Exception) {
                errors.add("upload $start", e)
            }
        }

        var mergedSettings = base.settings
        var settingsOutcome = SettingsOutcome.OFF
        if (options.syncSettings) {
            val local = device.settings()
            val remoteText = snapshot.files[settingsPath]?.let { String(remote.read(it)) }
            val merged = SettingsMerge.merge(base.settings, local, remoteText, preferRemote = base.settings == null)
            val toDevice = merged != local
            val toRemote = merged != remoteText
            if (toDevice) device.applySettings(merged)
            if (toRemote) changes[settingsPath] = merged.toByteArray()
            mergedSettings = merged
            settingsOutcome = when {
                toDevice && toRemote -> SettingsOutcome.MERGED
                toDevice -> SettingsOutcome.APPLIED
                toRemote -> SettingsOutcome.SAVED
                else -> SettingsOutcome.UNCHANGED
            }
        }

        if (changes.isNotEmpty()) remote.commit(snapshot.revision, changes, commitMessage(uploaded, removedFromRemote, settingsOutcome))
        baseStore.save(SyncBase(kept, mergedSettings))
        return Outcome(uploaded, removedFromRemote, removedHere, settingsOutcome)
    }

    private fun isWorkoutFile(path: String) = path.startsWith("$folder/") && path.endsWith(".gpx") && path.count { it == '/' } == 1

    private fun commitMessage(uploaded: Int, removed: Int, settings: SettingsOutcome): String {
        val parts = mutableListOf<String>()
        if (uploaded > 0) parts += "add $uploaded workout" + if (uploaded == 1) "" else "s"
        if (removed > 0) parts += "remove $removed workout" + if (removed == 1) "" else "s"
        if (settings == SettingsOutcome.SAVED || settings == SettingsOutcome.MERGED) parts += "update settings"
        return "Sync: " + parts.joinToString(", ")
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
