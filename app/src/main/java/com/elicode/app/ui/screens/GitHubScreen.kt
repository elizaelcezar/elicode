package com.elicode.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.elicode.app.AppGraph
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.core.security.KeystoreStore
import com.elicode.app.github.GitHubApi
import com.elicode.app.service.EliCodeService
import com.elicode.app.ui.components.ErrorCard
import com.elicode.app.ui.components.FormCard
import com.elicode.app.ui.components.LoadingRow
import com.elicode.app.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GitHubScreen(graph: AppGraph, onCloned: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(graph.secrets.has(KeystoreStore.GITHUB_TOKEN)) }
    var user by remember { mutableStateOf<GitHubApi.User?>(null) }
    var repos by remember { mutableStateOf<List<GitHubApi.Repo>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GitHubApi.Repo>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<EliError?>(null) }
    var cloning by remember { mutableStateOf<String?>(null) }

    fun token(): String? = graph.secrets.get(KeystoreStore.GITHUB_TOKEN)

    fun loadMine() {
        val t = token() ?: return
        busy = true
        scope.launch(Dispatchers.IO) {
            val me = graph.github.me(t)
            val mine = graph.github.myRepos(t)
            withContext(Dispatchers.Main) {
                busy = false
                (me as? EliResult.Ok)?.let {
                    user = it.value
                    graph.prefs.githubUser = it.value.login
                }
                if (me is EliResult.Err) error = me.error
                (mine as? EliResult.Ok)?.let { repos = it.value }
                if (mine is EliResult.Err && user == null) error = mine.error
            }
        }
    }

    LaunchedEffect(connected) { if (connected) loadMine() }

    fun cloneRepo(repo: GitHubApi.Repo) {
        cloning = repo.fullName
        error = null
        EliCodeService.taskStarted(context, "clone-${repo.name}", "Cloning ${repo.fullName}")
        scope.launch(Dispatchers.IO) {
            val dirName = repo.name
            val res = graph.git.clone(repo.cloneUrl, graph.projects.projectsRoot(), dirName, token())
            withContext(Dispatchers.Main) {
                EliCodeService.taskFinished(context, "clone-${repo.name}")
                cloning = null
                when (res) {
                    is EliResult.Ok -> {
                        val info = graph.projects.registerCloned(
                            res.value, repo.cloneUrl, repo.defaultBranch ?: "main"
                        )
                        graph.session.openProject(info)
                        graph.prefs.currentProjectPath = info.path
                        graph.logs.add("GitHub", "clone", "Cloned ${repo.fullName}")
                        onCloned()
                    }
                    is EliResult.Err -> {
                        graph.logs.add("GitHub", "clone", "FAILED: ${res.error.format()}")
                        error = res.error
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("GitHub", style = MaterialTheme.typography.headlineSmall)
        error?.let { ErrorCard(it, onDismiss = { error = null }) }
        if (!connected) {
            FormCard {
                Text("Connect with a personal access token (classic) with 'repo' scope.")
                Text("Create one at github.com → Settings → Developer settings → Tokens.")
                OutlinedTextField(
                    value = tokenInput, onValueChange = { tokenInput = it },
                    label = { Text("ghp_… token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = {
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val r = graph.github.me(tokenInput.trim())
                        withContext(Dispatchers.Main) {
                            busy = false
                            when (r) {
                                is EliResult.Ok -> {
                                    graph.secrets.put(KeystoreStore.GITHUB_TOKEN, tokenInput.trim())
                                    tokenInput = ""
                                    connected = true
                                    user = r.value
                                    graph.prefs.githubUser = r.value.login
                                }
                                is EliResult.Err -> error = r.error
                            }
                        }
                    }
                }, enabled = tokenInput.isNotBlank() && !busy) { Text("Connect (token stored in Keystore)") }
            }
        } else {
            FormCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Connected", color = MaterialTheme.colorScheme.tertiary)
                        Text(user?.login ?: graph.prefs.githubUser)
                    }
                    OutlinedButton(onClick = {
                        graph.secrets.remove(KeystoreStore.GITHUB_TOKEN)
                        connected = false
                        user = null
                        repos = emptyList()
                    }) { Text("Disconnect") }
                }
            }
        }
        if (busy) LoadingRow("Talking to GitHub…")
        SectionHeader("Search") { }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("user/repo or keyword") },
                singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = {
                busy = true
                scope.launch(Dispatchers.IO) {
                    val r = graph.github.search(query, token())
                    withContext(Dispatchers.Main) {
                        busy = false
                        when (r) {
                            is EliResult.Ok -> results = r.value
                            is EliResult.Err -> error = r.error
                        }
                    }
                }
            }, enabled = query.isNotBlank() && !busy) { Text("Go") }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (results.isNotEmpty()) {
                item { SectionHeader("Results") }
                items(results) { repo -> RepoRow(repo, cloning, ::cloneRepo) }
            }
            if (connected && repos.isNotEmpty()) {
                item { SectionHeader("My repositories") }
                items(repos) { repo -> RepoRow(repo, cloning, ::cloneRepo) }
            }
        }
    }
}

@Composable
private fun RepoRow(
    repo: GitHubApi.Repo,
    cloning: String?,
    onClone: (GitHubApi.Repo) -> Unit
) {
    ListItem(
        headlineContent = { Text(repo.fullName) },
        supportingContent = {
            Text(
                listOfNotNull(
                    repo.description,
                    repo.language,
                    if (repo.private) "private" else "public"
                ).joinToString(" · ").take(160),
                style = MaterialTheme.typography.bodySmall
            )
        },
        trailingContent = {
            TextButton(
                onClick = { onClone(repo) },
                enabled = cloning == null
            ) { Text(if (cloning == repo.fullName) "…" else "Clone") }
        }
    )
}
