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

/**
 * The state of the remote at one moment.
 *
 * @property revision identifies this state (a git commit id), null while the remote is empty
 * @property files path -> content id of every file. The content id is the git blob id, so equal ids mean equal content
 */
class RemoteSnapshot(val revision: String?, val files: Map<String, String>) {

    companion object {
        val EMPTY = RemoteSnapshot(null, emptyMap())
    }
}

/** The remote moved on since the snapshot a commit was based on. Fetch again, merge again, retry. */
class RemoteConflict(message: String = "The remote changed in the meantime") : IOException(message)

/**
 * Where the synced files live. A remote offers snapshots and atomic commits, so the merge rules
 * above it do not depend on how the files travel: git over HTTPS, GitHub's API or something else.
 */
interface RemoteStore {

    /** Brings the latest state of the remote here and describes it. */
    @Throws(IOException::class)
    fun fetch(): RemoteSnapshot

    /** Content of a file of any fetched snapshot, by content id. */
    @Throws(IOException::class)
    fun read(contentId: String): ByteArray

    /**
     * Applies [changes] (path -> new content, null deletes the file) on top of [expected] in one commit and returns the new revision.
     *
     * @throws RemoteConflict when the remote is no longer at [expected]
     */
    @Throws(IOException::class)
    fun commit(expected: String?, changes: Map<String, ByteArray?>, message: String): String
}
