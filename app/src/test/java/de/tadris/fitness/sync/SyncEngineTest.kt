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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    private val remote = MemoryRemote()

    private inner class Device(val options: SyncOptions = SyncOptions()) {
        val device = FakeDevice()
        val base = MemoryBase()
        fun sync() = SyncEngine(remote, device, base, options).run()
    }

    private fun remoteWorkouts() = remote.files.keys.filter { it.endsWith(".gpx") }.toSet()

    @Test
    fun firstDeviceUploadsAndSecondDeviceImports() {
        val a = Device().apply { device.workouts += listOf(1L, 2L) }
        val b = Device()

        val first = a.sync()
        val second = b.sync()

        assertEquals(2, first.uploaded)
        assertEquals(setOf("fitness/workout-1.gpx", "fitness/workout-2.gpx"), remoteWorkouts())
        assertEquals(2, second.imported)
        assertEquals(setOf(1L, 2L), b.device.workouts)
        assertEquals(0, second.uploaded)
    }

    @Test
    fun aSyncWithNothingToDoDoesNotCommit() {
        val a = Device().apply { device.workouts += 1L }
        a.sync()
        val commits = remote.messages.size

        val report = a.sync()

        assertEquals(commits, remote.messages.size)
        assertEquals(0, report.uploaded + report.imported + report.removedFromRemote)
    }

    @Test
    fun aWorkoutThatExistsOnBothSidesIsNotDuplicated() {
        remote.commitElsewhere(mapOf("fitness/workout-1.gpx" to "gpx:1".toByteArray()))
        val a = Device().apply { device.workouts += 1L }

        val report = a.sync()

        assertEquals(0, report.imported)
        assertEquals(0, report.uploaded)
        assertEquals(setOf(1L), a.device.workouts)
    }

    @Test
    fun deletingHereRemovesTheFileAndItIsNotImportedBack() {
        val a = Device().apply { device.workouts += listOf(1L, 2L) }
        val b = Device()
        a.sync(); b.sync()

        a.device.workouts.remove(1L)
        val report = a.sync()
        val other = b.sync()

        assertEquals(1, report.removedFromRemote)
        assertEquals(setOf("fitness/workout-2.gpx"), remoteWorkouts())
        assertEquals(0, other.imported)
        assertEquals(setOf(1L, 2L), b.device.workouts) // not deleted there
        assertEquals(setOf("fitness/workout-2.gpx"), remoteWorkouts()) // and not uploaded again
    }

    @Test
    fun deletingHereKeepsTheFileWhenTheOptionIsOff() {
        val a = Device(SyncOptions(deleteFromRemote = false)).apply { device.workouts += listOf(1L, 2L) }
        a.sync()

        a.device.workouts.remove(1L)
        a.sync()
        a.sync()

        assertEquals(setOf("fitness/workout-1.gpx", "fitness/workout-2.gpx"), remoteWorkouts())
        assertEquals(setOf(2L), a.device.workouts)
    }

    @Test
    fun deletionInTheRepoDeletesHereWhenTheOptionIsOn() {
        val a = Device().apply { device.workouts += listOf(1L, 2L) }
        val b = Device(SyncOptions(deleteHereWhenRemoteDeleted = true))
        a.sync(); b.sync()

        a.device.workouts.remove(1L)
        a.sync()
        val report = b.sync()

        assertEquals(1, report.removedHere)
        assertEquals(setOf(2L), b.device.workouts)
    }

    @Test
    fun deletionInTheRepoLeavesTheWorkoutHereAndDoesNotUploadItAgain() {
        val a = Device().apply { device.workouts += listOf(1L, 2L) }
        val b = Device()
        a.sync(); b.sync()

        a.device.workouts.remove(1L)
        a.sync()
        b.sync()
        b.sync()

        assertEquals(setOf(1L, 2L), b.device.workouts)
        assertEquals(setOf("fitness/workout-2.gpx"), remoteWorkouts())
    }

    @Test
    fun aCommitThatLosesTheRaceIsMergedAgainAndSucceeds() {
        val a = Device().apply { device.workouts += 1L }
        remote.beforeCommit = { remote.commitElsewhere(mapOf("fitness/workout-9.gpx" to "gpx:9".toByteArray())) }

        val report = a.sync()

        assertEquals(setOf("fitness/workout-1.gpx", "fitness/workout-9.gpx"), remoteWorkouts())
        assertEquals(setOf(1L, 9L), a.device.workouts)
        assertEquals(1, report.imported)
    }

    @Test
    fun anUnreadableFileIsCountedAndTheRestStillSyncs() {
        remote.commitElsewhere(mapOf("fitness/bad.gpx" to "garbage".toByteArray(), "fitness/workout-3.gpx" to "gpx:3".toByteArray()))
        val a = Device().apply { device.workouts += 1L }

        val report = a.sync()

        assertEquals(1, report.failed)
        assertTrue(report.firstError!!.contains("fitness/bad.gpx"))
        assertEquals(setOf(1L, 3L), a.device.workouts)
        assertTrue("fitness/workout-1.gpx" in remote.files)
    }

    @Test
    fun otherFilesInTheFolderAreLeftAlone() {
        remote.commitElsewhere(mapOf("fitness/notes.txt" to "hi".toByteArray(), "other/x.gpx" to "gpx:5".toByteArray()))
        val a = Device().apply { device.workouts += 1L }

        a.sync()

        assertEquals(setOf(1L), a.device.workouts)
        assertTrue("fitness/notes.txt" in remote.files)
        assertTrue("other/x.gpx" in remote.files)
    }

    @Test
    fun twoWorkoutsWithTheSameFileNameDoNotOverwriteEachOther() {
        remote.commitElsewhere(mapOf("fitness/workout-1.gpx" to "gpx:7".toByteArray()))
        val a = Device().apply { device.workouts += 1L }

        a.sync()

        assertEquals(setOf(1L, 7L), a.device.workouts)
        assertEquals(setOf("fitness/workout-1.gpx", "fitness/workout-1-1.gpx"), remoteWorkouts())
    }

    @Test
    fun theFirstSyncOfADeviceTakesTheSettingsOfTheRepo() {
        val a = Device().apply { device.values["weight"] = 70; device.values["unit"] = 1 }
        a.sync()
        val b = Device().apply { device.values["weight"] = 99 }

        val report = b.sync()

        assertEquals(70, b.device.values["weight"])
        assertEquals(1, b.device.values["unit"])
        assertEquals(SettingsOutcome.APPLIED, report.settings)
    }

    @Test
    fun settingsChangedOnDifferentDevicesAreMergedPerKey() {
        val a = Device().apply { device.values["weight"] = 70; device.values["unit"] = 1 }
        val b = Device()
        a.sync(); b.sync()

        a.device.values["weight"] = 72
        b.device.values["unit"] = 2
        a.sync(); b.sync(); a.sync()

        for (d in listOf(a, b)) {
            assertEquals(72, d.device.values["weight"])
            assertEquals(2, d.device.values["unit"])
        }
    }

    @Test
    fun aKeyChangedOnBothSidesTakesTheValueOfTheDeviceThatSyncsLast() {
        val a = Device().apply { device.values["weight"] = 70 }
        val b = Device()
        a.sync(); b.sync()

        a.device.values["weight"] = 71
        b.device.values["weight"] = 72
        a.sync()
        b.sync()
        a.sync()

        assertEquals(72, a.device.values["weight"])
        assertEquals(72, b.device.values["weight"])
    }

    @Test
    fun settingsStayOutOfTheWayWhenSwitchedOff() {
        val a = Device(SyncOptions(syncSettings = false)).apply { device.values["weight"] = 70; device.workouts += 1L }

        val report = a.sync()

        assertEquals(SettingsOutcome.OFF, report.settings)
        assertTrue("fitness/settings.json" !in remote.files)
    }
}
