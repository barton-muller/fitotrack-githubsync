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
import de.tadris.fitness.sync.RemoteSnapshot
import de.tadris.fitness.sync.RemoteStore
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.IOException

/**
 * A git repo on any host that speaks git over HTTPS, reached with an access token.
 * The files are kept in a bare repo in [cacheDir], so nothing is checked out and only what changed is transferred.
 *
 * @param url address of the remote repo, for example https://codeberg.org/someone/data.git
 * @param branch branch that holds the files
 * @param token access token, used as the password
 */
class GitRemote(
    private val url: String,
    private val branch: String,
    token: String?,
    private val cacheDir: File,
    private val identity: PersonIdent = PersonIdent("FitoTrack", "sync@fitotrack.invalid"),
) : RemoteStore {

    private val credentials: CredentialsProvider? =
        token?.takeIf { it.isNotBlank() }?.let { UsernamePasswordCredentialsProvider(USER, it) }

    private val trackingRef = "refs/remotes/origin/$branch"

    private fun open(): Repository =
        RepositoryBuilder().setGitDir(cacheDir).setBare().build().also { if (!cacheDir.resolve("HEAD").exists()) it.create(true) }

    override fun fetch(): RemoteSnapshot = open().use { repo ->
        try {
            Git(repo).fetch()
                .setRemote(url)
                .setRefSpecs(RefSpec("+refs/heads/$branch:$trackingRef"))
                .setCredentialsProvider(credentials)
                .call()
        } catch (e: TransportException) {
            // JGit words it the same for every host: the branch is not advertised, so the remote is still empty
            if (e.message?.contains("does not have") == true) return RemoteSnapshot.EMPTY
            throw e
        }
        snapshotOf(repo, repo.resolve(trackingRef))
    }

    override fun read(contentId: String): ByteArray = open().use { repo ->
        repo.open(ObjectId.fromString(contentId), Constants.OBJ_BLOB).bytes
    }

    override fun commit(expected: String?, changes: Map<String, ByteArray?>, message: String): String = open().use { repo ->
        val parent = expected?.let { ObjectId.fromString(it) }
        val inserter = repo.newObjectInserter()
        val commitId = inserter.use { ins ->
            val cache = DirCache.newInCore()
            parent?.let { p ->
                val builder = cache.builder()
                RevWalk(repo).use { walk ->
                    TreeWalk(repo).use { tree ->
                        tree.addTree(walk.parseCommit(p).tree)
                        tree.isRecursive = true
                        while (tree.next()) {
                            builder.add(DirCacheEntry(tree.pathString).apply {
                                fileMode = tree.getFileMode(0)
                                setObjectId(tree.getObjectId(0))
                            })
                        }
                    }
                }
                builder.finish()
            }
            val editor = cache.editor()
            for ((path, content) in changes) {
                if (content == null) {
                    editor.add(DirCacheEditor.DeletePath(path))
                } else {
                    val blob = ins.insert(Constants.OBJ_BLOB, content)
                    editor.add(object : DirCacheEditor.PathEdit(path) {
                        override fun apply(entry: DirCacheEntry) {
                            entry.fileMode = FileMode.REGULAR_FILE
                            entry.setObjectId(blob)
                        }
                    })
                }
            }
            editor.finish()
            val commit = CommitBuilder().apply {
                setTreeId(cache.writeTree(ins))
                if (parent != null) setParentId(parent)
                setAuthor(identity)
                setCommitter(identity)
                setMessage(message)
            }
            ins.insert(commit).also { ins.flush() }
        }
        push(repo, commitId)
        commitId.name
    }

    private fun push(repo: Repository, commitId: ObjectId) {
        val results = Git(repo).push()
            .setRemote(url)
            .setRefSpecs(RefSpec("${commitId.name}:refs/heads/$branch"))
            .setCredentialsProvider(credentials)
            .call()
        for (result in results) {
            val update = result.getRemoteUpdate("refs/heads/$branch") ?: continue
            when (update.status) {
                RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE ->
                    repo.updateRef(trackingRef).apply { setNewObjectId(commitId); setForceUpdate(true); update() }
                RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> throw RemoteConflict()
                else -> throw IOException("Push failed: ${update.status} ${update.message.orEmpty()}")
            }
        }
    }

    private fun snapshotOf(repo: Repository, head: ObjectId?): RemoteSnapshot {
        if (head == null) return RemoteSnapshot.EMPTY
        val files = linkedMapOf<String, String>()
        RevWalk(repo).use { walk ->
            TreeWalk(repo).use { tree ->
                tree.addTree(walk.parseCommit(head).tree)
                tree.isRecursive = true
                while (tree.next()) files[tree.pathString] = tree.getObjectId(0).name
            }
        }
        return RemoteSnapshot(head.name, files)
    }

    private companion object {
        /** Hosts accept any user name with a token as the password. */
        const val USER = "token"
    }
}
