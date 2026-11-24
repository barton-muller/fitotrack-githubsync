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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tadris.fitness.R
import de.tadris.fitness.ui.settings.ClickableSettingsItem
import de.tadris.fitness.ui.settings.SettingsCategory
import de.tadris.fitness.ui.settings.SwitchSettingsItem

private val GitHubBlack = Color(0xFF24292F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: GitHubSyncViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.githubSyncTitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
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
            if (state.signedIn) {
                item { SettingsCategory(stringResource(R.string.githubSyncAccount)) }
                item {
                    ClickableSettingsItem(
                        title = state.login?.let { stringResource(R.string.githubSyncSignedInAs, it) }
                            ?: stringResource(R.string.githubSyncSignedIn),
                        supporting = stringResource(R.string.githubSyncSignOut),
                        onClick = vm::signOut,
                    )
                }
            }
            when {
                state.repo != null -> {
                    item { SettingsCategory(stringResource(R.string.githubSyncRepo)) }
                    repoSettings(state, vm)
                }
                state.signedIn -> {
                    item { SettingsCategory(stringResource(R.string.githubSyncRepo)) }
                    repoChooser(state, vm, context)
                }
                else -> item { SignIn(state, vm, context) }
            }
            if (!state.isGit) item { AdvancedGit(state, vm) }
            item {
                ClickableSettingsItem(
                    title = stringResource(R.string.githubSyncTestConnection),
                    supporting = stringResource(R.string.githubSyncTestConnectionSummary),
                    enabled = !state.busy,
                    onClick = vm::runDiagnostics,
                )
            }
            if (state.diagnostics.isNotEmpty()) {
                item {
                    SelectionContainer {
                        Text(
                            state.diagnostics,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            if (state.message.isNotEmpty()) {
                item {
                    SelectionContainer {
                        Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SignIn(state: GitHubSyncUiState, vm: GitHubSyncViewModel, context: Context) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        val code = state.deviceCode
        if (code == null) {
            Button(
                onClick = { vm.signIn(openBrowser = { openUrl(context, it) }, onSignedIn = { bringAppToFront(context) }) },
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GitHubBlack, contentColor = Color.White),
            ) {
                Icon(painterResource(R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.githubSyncSignIn), fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(stringResource(R.string.githubSyncEnterCode), style = MaterialTheme.typography.bodyMedium)
            Text(code.userCode, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
            Text(code.verificationUri, style = MaterialTheme.typography.bodySmall)
            if (state.pollStatus.isNotEmpty()) Text(state.pollStatus, style = MaterialTheme.typography.bodySmall)
            CircularProgressIndicator(Modifier.padding(top = 12.dp))
            TextButton(onClick = vm::cancelSignIn) { Text(stringResource(R.string.githubSyncCancel)) }
        }
    }
}

/** Another git host with an access token. Collapsed until it is opened. */
@Composable
private fun AdvancedGit(state: GitHubSyncUiState, vm: GitHubSyncViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    var branch by rememberSaveable { mutableStateOf("main") }
    var token by rememberSaveable { mutableStateOf("") }
    Column {
        ClickableSettingsItem(
            title = stringResource(R.string.githubSyncGitAdvanced),
            supporting = stringResource(R.string.githubSyncGitAdvancedSummary),
            onClick = { open = !open },
        )
        if (open) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, singleLine = true,
                    label = { Text(stringResource(R.string.githubSyncGitUrl)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = branch, onValueChange = { branch = it }, singleLine = true,
                    label = { Text(stringResource(R.string.githubSyncGitBranch)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, singleLine = true,
                    label = { Text(stringResource(R.string.githubSyncGitToken)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.connectGit(url, branch, token) },
                    enabled = !state.busy && url.isNotBlank() && token.isNotBlank(),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) { Text(stringResource(R.string.githubSyncGitConnect)) }
            }
        }
    }
}

private fun LazyListScope.repoSettings(state: GitHubSyncUiState, vm: GitHubSyncViewModel) {
    item {
        ClickableSettingsItem(
            title = state.repo.orEmpty(),
            supporting = stringResource(if (state.isGit) R.string.githubSyncGitSummary else R.string.githubSyncChangeRepo),
            onClick = vm::changeRepo,
        )
    }
    item {
        SwitchSettingsItem(
            title = stringResource(R.string.githubSyncSettingsTitle),
            supporting = stringResource(R.string.githubSyncSettingsSummary),
            checked = state.syncSettings,
            onCheckedChange = vm::setSyncSettings,
        )
    }
    item {
        SwitchSettingsItem(
            title = stringResource(R.string.githubSyncDeleteTitle),
            supporting = stringResource(R.string.githubSyncDeleteSummary),
            checked = state.deleteFromRepo,
            onCheckedChange = vm::setDeleteFromRepo,
        )
    }
    item {
        SwitchSettingsItem(
            title = stringResource(R.string.githubSyncDeleteHereTitle),
            supporting = stringResource(R.string.githubSyncDeleteHereSummary),
            checked = state.deleteHere,
            onCheckedChange = vm::setDeleteHere,
        )
    }
    item {
        ClickableSettingsItem(
            title = stringResource(R.string.githubSyncNow),
            supporting = stringResource(R.string.githubSyncNowSummary),
            enabled = !state.busy,
            onClick = vm::syncNow,
        )
    }
}

private fun LazyListScope.repoChooser(
    state: GitHubSyncUiState,
    vm: GitHubSyncViewModel,
    context: Context,
) {
    if (state.loadingRepos) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.githubSyncLoadingRepos))
            }
        }
    }
    items(state.repos.orEmpty()) { repo ->
        ClickableSettingsItem(title = repo.full, onClick = { vm.chooseRepo(repo) })
    }
    item {
        ClickableSettingsItem(
            title = stringResource(R.string.githubSyncNewRepo),
            supporting = stringResource(R.string.githubSyncNewRepoSummary),
            enabled = !state.busy,
            onClick = { vm.createRepo { openUrl(context, GitHubConfig.INSTALL_URL) } },
        )
    }
    item {
        ClickableSettingsItem(
            title = stringResource(R.string.githubSyncAddRepos),
            supporting = stringResource(R.string.githubSyncAddReposSummary),
            onClick = { openUrl(context, GitHubConfig.INSTALL_URL) },
        )
    }
    item {
        ClickableSettingsItem(
            title = stringResource(R.string.githubSyncRefresh),
            enabled = !state.busy,
            onClick = vm::loadRepos,
        )
    }
}

/** Opens the page in an in-app Custom Tab, or in the default browser when none is available. */
private fun openUrl(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    } catch (e: Exception) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Closes the Custom Tab on top of the app by launching the app's own activity over it. Best effort. */
private fun bringAppToFront(context: Context) {
    var c: Context? = context
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    val activity = c as? Activity ?: return
    runCatching {
        activity.startActivity(
            Intent(activity, activity.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
