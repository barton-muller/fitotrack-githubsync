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

package de.tadris.fitness.sync.git

import de.tadris.fitness.sync.FakeDevice
import de.tadris.fitness.sync.MemoryBase
import de.tadris.fitness.sync.SyncEngine
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The merge engine over the git transport: two devices sharing one bare repo. */
class GitSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Device(val remote: GitRemote) {
        val device = FakeDevice()
        val base = MemoryBase()
        fun sync() = SyncEngine(remote, device, base).run()
    }

    @Test
    fun twoDevicesConvergeThroughAGitRepo() {
        val dir = tmp.newFolder("remote")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(dir).call().close()
        val url = dir.toURI().toString()
        val a = Device(GitRemote(url, "main", null, tmp.newFolder("cache-a")))
        val b = Device(GitRemote(url, "main", null, tmp.newFolder("cache-b")))

        a.device.workouts += listOf(1L, 2L)
        a.device.values["weight"] = 70
        a.sync()
        b.device.workouts += 3L
        val second = b.sync()
        a.device.workouts.remove(1L)
        b.device.values["unit"] = 2
        a.sync()
        b.sync()
        a.sync()

        assertEquals(2, second.imported)
        assertEquals(setOf(2L, 3L), a.device.workouts)
        assertEquals(setOf(1L, 2L, 3L), b.device.workouts) // a's deletion removed the file, b keeps its copy
        assertEquals(70, b.device.values["weight"])
        assertEquals(2, a.device.values["unit"])
    }
}
