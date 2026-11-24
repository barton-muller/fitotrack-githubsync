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

import de.tadris.fitness.sync.RemoteConflict
import de.tadris.fitness.sync.RemoteSnapshot
import de.tadris.fitness.sync.RemoteStore

/**
 * A GitHub repo reached through GitHub's API with the token of the signed-in person.
 * A commit is built from blobs, a tree and a commit, and moves the branch only when that is a fast-forward,
 * which is how a change made by someone else in the meantime shows up.
 */
class GitHubRemote(private val api: GitHubApi, private val repo: String, private val branch: String) : RemoteStore {

    override fun fetch(): RemoteSnapshot {
        val head = api.branchHead(repo, branch) ?: return RemoteSnapshot.EMPTY
        return RemoteSnapshot(head, api.treeFiles(repo, api.commitTree(repo, head)))
    }

    override fun read(contentId: String): ByteArray = api.readBlob(repo, contentId)

    override fun commit(expected: String?, changes: Map<String, ByteArray?>, message: String): String {
        if (expected == null) return commitToEmptyRepo(changes, message)
        val current = api.branchHead(repo, branch)
        if (current != expected) throw RemoteConflict()
        val blobs = changes.mapValues { (_, content) -> content?.let { api.createBlob(repo, it) } }
        val tree = api.createTree(repo, api.commitTree(repo, expected), blobs)
        val commit = api.createCommit(repo, message, tree, expected)
        if (!api.fastForward(repo, branch, commit)) throw RemoteConflict()
        return commit
    }

    /** The git data API cannot make the first commit of an empty repo, so the contents API makes it with the first file. */
    private fun commitToEmptyRepo(changes: Map<String, ByteArray?>, message: String): String {
        val first = changes.entries.firstOrNull { it.value != null } ?: throw IllegalArgumentException("Nothing to commit")
        api.putFile(repo, branch, first.key, first.value!!, message)
        val head = api.branchHead(repo, branch) ?: throw java.io.IOException("The first commit did not arrive")
        val rest = changes - first.key
        return if (rest.isEmpty()) head else commit(head, rest, message)
    }
}
