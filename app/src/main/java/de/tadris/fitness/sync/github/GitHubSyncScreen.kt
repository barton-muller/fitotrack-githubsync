package de.tadris.fitness.sync.github

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.tadris.fitness.R
import de.tadris.fitness.ui.settings.ClickableSettingsItem
import de.tadris.fitness.ui.settings.SettingsCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sync = remember { GitHubSync(context) }
    val scope = rememberCoroutineScope()

    var signedIn by remember { mutableStateOf(sync.auth.signedIn()) }
    var login by remember { mutableStateOf(sync.auth.login()) }
    var repo by remember { mutableStateOf(sync.repo) }
    var repos by remember { mutableStateOf<List<RepoChoice>?>(null) }
    var code by remember { mutableStateOf<DeviceCode?>(null) }
    var signInJob by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf("") }
    var pollStatus by remember { mutableStateOf("") }
    var message by remember { mutableStateOf(sync.lastSyncMessage ?: "") }

    fun fail(e: Throwable) {
        message = when (e) {
            is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
                "Cannot reach GitHub (${e.javaClass.simpleName}: ${e.message}). Check the internet connection and that this app is allowed to use the network."
            else -> e.message ?: e.toString()
        }
    }

    fun loadRepos() {
        scope.launch {
            busy = true
            try {
                repos = withContext(Dispatchers.IO) { sync.api().repos() }
                if (repos.isNullOrEmpty()) message = context.getString(R.string.githubSyncNoRepos)
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(signedIn) { if (signedIn && repo == null) loadRepos() }

    fun signIn() {
        signInJob?.cancel()
        signInJob = scope.launch {
            try {
                pollStatus = ""
                val c = try {
                    withContext(Dispatchers.IO) { sync.auth.startDeviceFlow() }
                } catch (e: java.io.IOException) {
                    throw java.io.IOException("Could not start sign-in (${e.javaClass.simpleName}: ${e.message})", e)
                }
                code = c
                copy(context, c.userCode)
                openUrl(context, c.verificationUri)
                val token = sync.auth.awaitToken(c) { pollStatus = it }
                val name = withContext(Dispatchers.IO) { runCatching { GitHubApi(token).login() }.getOrNull() }
                sync.auth.save(token, name)
                bringAppToFront(context)
                login = name
                signedIn = true
                message = ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                code = null
            }
        }
    }

    fun choose(choice: RepoChoice) {
        scope.launch {
            withContext(Dispatchers.IO) { sync.chooseRepo(choice) }
            repo = choice.full
            message = ""
        }
    }

    fun createRepo() {
        scope.launch {
            busy = true
            message = context.getString(R.string.githubSyncCreating)
            try {
                var made: RepoChoice? = null
                withContext(Dispatchers.IO) {
                    for (n in 1..5) {
                        val name = if (n == 1) GitHubConfig.NEW_REPO_NAME else "${GitHubConfig.NEW_REPO_NAME}-$n"
                        try {
                            made = sync.api().createRepo(name, "FitoTrack workouts")
                            break
                        } catch (e: GitHubHttpException) {
                            if (e.code != 422) throw e
                        }
                    }
                }
                val m = made ?: throw IllegalStateException("No free repo name")
                val reachable = withContext(Dispatchers.IO) { sync.api().repos() }.any { it.full == m.full }
                if (reachable) {
                    choose(m)
                } else {
                    message = context.getString(R.string.githubSyncInstallNew, m.full)
                    openUrl(context, GitHubConfig.INSTALL_URL)
                }
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    fun syncNow() {
        scope.launch {
            busy = true
            message = context.getString(R.string.githubSyncRunning)
            try {
                val r = withContext(Dispatchers.IO) { sync.pull() }
                message = context.getString(R.string.githubSyncDone, r.imported, r.uploaded, r.failed) + (r.firstError?.let { "\n$it" } ?: "")
                sync.lastSyncMessage = message
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.githubSyncTitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Text(
                    stringResource(R.string.githubSyncExplanation, GitHubConfig.FOLDER),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (!signedIn) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        val c = code
                        if (c == null) {
                            Button(
                                onClick = { signIn() },
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24292F), contentColor = Color.White),
                            ) {
                                Icon(painterResource(R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.githubSyncSignIn), fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Text(stringResource(R.string.githubSyncEnterCode), style = MaterialTheme.typography.bodyMedium)
                            Text(c.userCode, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
                            Text(c.verificationUri, style = MaterialTheme.typography.bodySmall)
                            if (pollStatus.isNotEmpty()) Text(pollStatus, style = MaterialTheme.typography.bodySmall)
                            CircularProgressIndicator(Modifier.padding(top = 12.dp))
                            TextButton(onClick = { signInJob?.cancel(); code = null }) { Text(stringResource(R.string.githubSyncCancel)) }
                        }
                    }
                }
            } else {
                item { SettingsCategory(stringResource(R.string.githubSyncAccount)) }
                item {
                    ClickableSettingsItem(
                        title = login?.let { stringResource(R.string.githubSyncSignedInAs, it) } ?: stringResource(R.string.githubSyncSignedIn),
                        supporting = stringResource(R.string.githubSyncSignOut),
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { sync.signOut() }
                                signedIn = false; login = null; repo = null; repos = null; message = ""
                            }
                        },
                    )
                }
                item { SettingsCategory(stringResource(R.string.githubSyncRepo)) }
                if (repo != null) {
                    item {
                        ClickableSettingsItem(
                            title = repo!!,
                            supporting = stringResource(R.string.githubSyncChangeRepo),
                            onClick = { repo = null; loadRepos() },
                        )
                    }
                    item {
                        ClickableSettingsItem(
                            title = stringResource(R.string.githubSyncNow),
                            supporting = stringResource(R.string.githubSyncNowSummary),
                            enabled = !busy,
                            onClick = { syncNow() },
                        )
                    }
                } else {
                    items(repos.orEmpty()) { r ->
                        ClickableSettingsItem(title = r.full, onClick = { choose(r) })
                    }
                    item {
                        ClickableSettingsItem(
                            title = stringResource(R.string.githubSyncNewRepo),
                            supporting = stringResource(R.string.githubSyncNewRepoSummary),
                            enabled = !busy,
                            onClick = { createRepo() },
                        )
                    }
                    item {
                        ClickableSettingsItem(
                            title = stringResource(R.string.githubSyncAddRepos),
                            supporting = stringResource(R.string.githubSyncAddReposSummary),
                            onClick = {
                                openUrl(context, GitHubConfig.INSTALL_URL)
                            },
                        )
                    }
                    item {
                        ClickableSettingsItem(
                            title = stringResource(R.string.githubSyncRefresh),
                            enabled = !busy,
                            onClick = { loadRepos() },
                        )
                    }
                }
            }
            item {
                ClickableSettingsItem(
                    title = stringResource(R.string.githubSyncTestConnection),
                    supporting = stringResource(R.string.githubSyncTestConnectionSummary),
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            diagnostics = context.getString(R.string.githubSyncRunning)
                            diagnostics = withContext(Dispatchers.IO) { NetworkDiagnostics.run(context) }
                            busy = false
                        }
                    },
                )
            }
            if (diagnostics.isNotEmpty()) {
                item {
                    SelectionContainer {
                        Text(
                            diagnostics,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            if (message.isNotEmpty()) {
                item {
                    SelectionContainer {
                        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

/** Closes the Custom Tab on top of the app by re-launching the app's own activity over it. Best effort. */
private fun bringAppToFront(context: Context) {
    var c: Context? = context
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    val activity = c as? Activity ?: return
    runCatching {
        activity.startActivity(Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}

/** Opens the page in an in-app Custom Tab, or the default browser when none is available. */
private fun openUrl(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    } catch (e: Exception) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("GitHub code", text))
}
