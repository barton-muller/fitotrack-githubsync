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

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tadris.fitness.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class GitHubSyncUiState(
    val signedIn: Boolean = false,
    val login: String? = null,
    val repo: String? = null,
    /** Repos the GitHub App can reach; null until they have been loaded. */
    val repos: List<RepoChoice>? = null,
    val loadingRepos: Boolean = false,
    /** Set while the person has to enter the code on GitHub. */
    val deviceCode: DeviceCode? = null,
    val pollStatus: String = "",
    val busy: Boolean = false,
    val message: String = "",
    val diagnostics: String = "",
    val deleteFromRepo: Boolean = true,
    val syncSettings: Boolean = true,
)

class GitHubSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val sync = GitHubSync(application)

    private val _uiState = MutableStateFlow(
        GitHubSyncUiState(
            signedIn = sync.auth.signedIn(),
            login = sync.auth.login(),
            repo = sync.repo,
            message = sync.lastSyncMessage.orEmpty(),
            deleteFromRepo = sync.deleteFromRepo,
            syncSettings = sync.syncSettings,
        )
    )
    val uiState: StateFlow<GitHubSyncUiState> = _uiState.asStateFlow()

    private var signInJob: Job? = null

    init {
        if (_uiState.value.signedIn && _uiState.value.repo == null) loadRepos()
    }

    /**
     * Starts the device flow. [openBrowser] shows the GitHub page where the code is entered,
     * [onSignedIn] is called once GitHub has handed out a token.
     */
    fun signIn(openBrowser: (String) -> Unit, onSignedIn: () -> Unit) {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(pollStatus = "", message = "") }
                val code = try {
                    withContext(Dispatchers.IO) { sync.auth.startDeviceFlow() }
                } catch (e: IOException) {
                    throw IOException(getString(R.string.githubSyncStartFailed, describe(e)), e)
                }
                _uiState.update { it.copy(deviceCode = code) }
                copyToClipboard(code.userCode)
                openBrowser(code.verificationUri)
                val token = sync.auth.awaitToken(code) { status -> _uiState.update { it.copy(pollStatus = status) } }
                val login = withContext(Dispatchers.IO) { runCatching { GitHubApi(token).login() }.getOrNull() }
                sync.auth.save(token, login)
                _uiState.update { it.copy(signedIn = true, login = login) }
                onSignedIn()
                loadRepos()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                _uiState.update { it.copy(deviceCode = null) }
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _uiState.update { it.copy(deviceCode = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sync.signOut() }
            _uiState.update { GitHubSyncUiState(deleteFromRepo = it.deleteFromRepo, syncSettings = it.syncSettings) }
        }
    }

    fun loadRepos() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, loadingRepos = true, repos = null, message = "") }
            try {
                val repos = withContext(Dispatchers.IO) { sync.api().repos() }
                _uiState.update {
                    it.copy(repos = repos, message = if (repos.isEmpty()) getString(R.string.githubSyncNoRepos) else "")
                }
            } catch (e: Exception) {
                fail(e)
            } finally {
                _uiState.update { it.copy(busy = false, loadingRepos = false) }
            }
        }
    }

    fun chooseRepo(choice: RepoChoice) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sync.chooseRepo(choice) }
            _uiState.update { it.copy(repo = choice.full, message = "") }
        }
    }

    /** Lets the person pick another repo. */
    fun changeRepo() {
        _uiState.update { it.copy(repo = null) }
        loadRepos()
    }

    /**
     * Creates a private repo and uses it. When the GitHub App cannot reach the new repo yet,
     * [openInstallPage] sends the person to GitHub to add it.
     */
    fun createRepo(openInstallPage: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = getString(R.string.githubSyncCreating)) }
            try {
                val made = withContext(Dispatchers.IO) { createFreeRepo() }
                val reachable = withContext(Dispatchers.IO) { sync.api().repos() }.any { it.full == made.full }
                if (reachable) {
                    chooseRepo(made)
                } else {
                    _uiState.update { it.copy(message = getString(R.string.githubSyncInstallNew, made.full)) }
                    openInstallPage()
                }
            } catch (e: Exception) {
                fail(e)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun createFreeRepo(): RepoChoice {
        val api = sync.api()
        for (n in 1..MAX_NAME_ATTEMPTS) {
            val name = if (n == 1) GitHubConfig.NEW_REPO_NAME else "${GitHubConfig.NEW_REPO_NAME}-$n"
            try {
                return api.createRepo(name, getString(R.string.githubSyncRepoDescription))
            } catch (e: GitHubHttpException) {
                if (e.code != HTTP_UNPROCESSABLE) throw e
            }
        }
        throw IOException(getString(R.string.githubSyncNoFreeName))
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = getString(R.string.githubSyncRunning)) }
            try {
                val r = withContext(Dispatchers.IO) { sync.sync() }
                val message = getString(R.string.githubSyncDone, r.imported, r.uploaded, r.deleted, r.failed, r.settings) +
                        (r.firstError?.let { "\n$it" } ?: "")
                sync.lastSyncMessage = message
                _uiState.update { it.copy(message = message) }
            } catch (e: Exception) {
                fail(e)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, diagnostics = getString(R.string.githubSyncRunning)) }
            val report = withContext(Dispatchers.IO) { NetworkDiagnostics.run(getApplication()) }
            _uiState.update { it.copy(busy = false, diagnostics = report) }
        }
    }

    fun setDeleteFromRepo(value: Boolean) {
        sync.deleteFromRepo = value
        _uiState.update { it.copy(deleteFromRepo = value) }
    }

    fun setSyncSettings(value: Boolean) {
        sync.syncSettings = value
        _uiState.update { it.copy(syncSettings = value) }
    }

    private fun fail(e: Throwable) {
        _uiState.update { it.copy(message = describe(e)) }
    }

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException, is SocketTimeoutException, is ConnectException ->
            getString(R.string.githubSyncNetworkError, e.javaClass.simpleName, e.message.orEmpty())
        else -> e.message ?: e.toString()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GitHub code", text))
    }

    private fun getString(id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)

    private companion object {
        const val MAX_NAME_ATTEMPTS = 5
        const val HTTP_UNPROCESSABLE = 422
    }
}
