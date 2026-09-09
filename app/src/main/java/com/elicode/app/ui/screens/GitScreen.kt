package com.elicode.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elicode.app.AppGraph
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.ui.components.EmptyState
import com.elicode.app.ui.components.ErrorCard
import com.elicode.app.ui.components.FormCard
import com.elicode.app.ui.components.LoadingRow
import com.elicode.app.ui.components.MonoLogCard
import com.elicode.app.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun GitScreen(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val project = graph.session.project
    var status by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<EliError?>(null) }
    var message by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var userEmail by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }

    fun refreshAll() {
        val dir = File(project!!.path)
        scope.launch(Dispatchers.IO) {
            val s = graph.git.status(dir)
            val l = graph.git.log(dir)
            val b = graph.git.branches(dir)
            withContext(Dispatchers.Main) {
                status = (s as? EliResult.Ok)?.value?.combined ?: ""
                if (s is EliResult.Err) error = s.error
                log = (l as? EliResult.Ok)?.value?.stdout ?: ""
                branches = (b as? EliResult.Ok)?.value?.stdout ?: ""
            }
        }
    }

    fun runOp(label: String, op: suspend () -> EliResult<*>, after: (() -> Unit)? = null) {
        val dir = File(project!!.path)
        busy = true
        error = null
        scope.launch(Dispatchers.IO) {
            val r = op()
            withContext(Dispatchers.Main) {
                busy = false
                when (r) {
                    is EliResult.Ok -> {
                        val out = (r.value as? com.elicode.app.runtime.ProcResult)?.combined
                            ?: r.value.toString()
                        graph.logs.add("Git", label, "ok ${out.take(200)}")
                        after?.invoke()
                        refreshAll()
                    }
                    is EliResult.Err -> {
                        graph.logs.add("Git", label, "FAILED: ${r.error.format()}")
                        error = r.error
                    }
                }
            }
        }
    }

    LaunchedEffect(project?.path) {
        if (project != null) {
            val dir = File(project.path)
            if (!graph.git.isRepo(dir)) {
                status = "(not a git repository)"
            } else refreshAll()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Git", style = MaterialTheme.typography.headlineSmall)
        if (project == null) {
            EmptyState("Open a project to use git.")
            return@Column
        }
        val dir = File(project.path)
        val isRepo = remember(project.path, status) { graph.git.isRepo(dir) }
        Text("${project.name} · ⎇ ${graph.projects.readGitBranch(dir).ifBlank { "—" }}",
            style = MaterialTheme.typography.labelLarge)

        if (!isRepo) {
            FormCard {
                Text("This folder is not a git repository.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runOp("init", op = { graph.git.init(dir) })
                    }, enabled = !busy) { Text("git init") }
                }
                OutlinedTextField(remoteUrl, { remoteUrl = it },
                    label = { Text("Remote URL (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    runOp("remote", op = { graph.git.setRemote(dir, remoteUrl) })
                }, enabled = !busy && remoteUrl.isNotBlank()) { Text("Set origin") }
            }
            error?.let { ErrorCard(it, onDismiss = { error = null }) }
            return@Column
        }

        if (busy) LoadingRow("Running git…")
        error?.let { ErrorCard(it, onDismiss = { error = null }) }

        SectionHeader("Status")
        MonoLogCard(status)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runOp("add", op = { graph.git.addAll(dir) }) }, enabled = !busy) {
                Text("Stage all")
            }
            Button(onClick = { runOp("pull", op = { graph.git.pull(dir) }) }, enabled = !busy) {
                Text("Pull")
            }
            Button(onClick = {
                runOp("push", op = { graph.git.push(dir, setUpstream = true) })
            }, enabled = !busy) { Text("Push") }
        }
        FormCard {
            OutlinedTextField(message, { message = it },
                label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                runOp("commit", op = { graph.git.commit(dir, message) }, after = { message = "" })
            }, enabled = !busy && message.isNotBlank()) { Text("Commit") }
        }
        SectionHeader("Branches")
        MonoLogCard(branches)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(branchName, { branchName = it },
                label = { Text("branch") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                runOp("checkout", op = { graph.git.checkout(dir, branchName) }, after = { branchName = "" })
            }, enabled = !busy && branchName.isNotBlank()) { Text("Switch") }
            OutlinedButton(onClick = {
                runOp("branch", op = { graph.git.createBranch(dir, branchName) }, after = { branchName = "" })
            }, enabled = !busy && branchName.isNotBlank()) { Text("New") }
        }
        SectionHeader("Identity")
        FormCard {
            OutlinedTextField(userName, { userName = it },
                label = { Text("user.name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(userEmail, { userEmail = it },
                label = { Text("user.email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                runOp("identity", op = { graph.git.setIdentity(dir, userName, userEmail) })
            }, enabled = !busy && userName.isNotBlank() && userEmail.isNotBlank()) {
                Text("Save identity")
            }
        }
        SectionHeader("Recent commits")
        MonoLogCard(log)
    }
}
