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

import de.tadris.fitness.sync.RemoteConflict
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitRemoteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun remoteRepo(): String {
        val dir = tmp.newFolder("remote")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(dir).call().close()
        return dir.toURI().toString()
    }

    private fun client(url: String, name: String) = GitRemote(url, "main", null, tmp.newFolder(name))

    @Test
    fun emptyRemoteHasNoRevision() {
        val snapshot = client(remoteRepo(), "a").fetch()

        assertNull(snapshot.revision)
        assertTrue(snapshot.files.isEmpty())
    }

    @Test
    fun commitIsVisibleToAnotherClient() {
        val url = remoteRepo()
        val a = client(url, "a")
        val b = client(url, "b")

        val rev = a.commit(null, mapOf("fitness/one.gpx" to "one".toByteArray(), "fitness/settings.json" to "{}".toByteArray()), "first")
        val snapshot = b.fetch()

        assertEquals(rev, snapshot.revision)
        assertEquals(setOf("fitness/one.gpx", "fitness/settings.json"), snapshot.files.keys)
        assertArrayEquals("one".toByteArray(), b.read(snapshot.files.getValue("fitness/one.gpx")))
    }

    @Test
    fun changesAddReplaceAndDeleteFiles() {
        val a = client(remoteRepo(), "a")
        val first = a.commit(null, mapOf("x/a.gpx" to "a".toByteArray(), "x/b.gpx" to "b".toByteArray()), "first")

        val second = a.commit(first, mapOf("x/a.gpx" to "a2".toByteArray(), "x/b.gpx" to null, "x/c.gpx" to "c".toByteArray()), "second")
        val snapshot = a.fetch()

        assertEquals(second, snapshot.revision)
        assertEquals(setOf("x/a.gpx", "x/c.gpx"), snapshot.files.keys)
        assertArrayEquals("a2".toByteArray(), a.read(snapshot.files.getValue("x/a.gpx")))
    }

    @Test
    fun equalContentHasEqualId() {
        val a = client(remoteRepo(), "a")
        val rev = a.commit(null, mapOf("p.gpx" to "same".toByteArray(), "q.gpx" to "same".toByteArray()), "two files")
        val files = a.fetch().files

        assertEquals(rev, a.fetch().revision)
        assertEquals(files.getValue("p.gpx"), files.getValue("q.gpx"))
    }

    @Test
    fun commitOnAMovedRemoteIsAConflict() {
        val url = remoteRepo()
        val a = client(url, "a")
        val b = client(url, "b")
        val first = a.commit(null, mapOf("one.gpx" to "1".toByteArray()), "first")
        b.fetch()
        a.commit(first, mapOf("two.gpx" to "2".toByteArray()), "second from a")

        try {
            b.commit(first, mapOf("three.gpx" to "3".toByteArray()), "from b, based on the old state")
            throw AssertionError("expected a conflict")
        } catch (expected: RemoteConflict) {
            // b has to fetch and merge first
        }
        val latest = b.fetch()
        b.commit(latest.revision, mapOf("three.gpx" to "3".toByteArray()), "from b, merged")

        assertEquals(setOf("one.gpx", "two.gpx", "three.gpx"), a.fetch().files.keys)
    }
}
