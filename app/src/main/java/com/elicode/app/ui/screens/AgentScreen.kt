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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elicode.app.AppGraph
import com.elicode.app.agent.OpenCodeEngine
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.runtime.ProcessListener
import com.elicode.app.runtime.Stream
import com.elicode.app.service.EliCodeService
import com.elicode.app.ui.components.EmptyState
import com.elicode.app.ui.components.ErrorCard
import com.elicode.app.ui.components.FormCard
import com.elicode.app.ui.components.LoadingRow
import com.elicode.app.ui.components.MonoLogCard
import com.elicode.app.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AgentScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = graph.session.project
    var status by remember { mutableStateOf<OpenCodeEngine.Status?>(null) }
    var checking by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var runId by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<EliError?>(null) }
    var changed by remember { mutableStateOf<List<String>>(emptyList()) }
    var installing by remember { mutableStateOf(false) }

    fun refresh() {
        checking = true
        scope.launch(Dispatchers.IO) {
            val s = graph.agent.status()
            withContext(Dispatchers.Main) {
                status = s
                checking = false
            }
        }
    }

    LaunchedEffect(project?.path) { refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("OpenCode agent", style = MaterialTheme.typography.headlineSmall)
        if (project == null) {
            EmptyState("Open a project so the agent knows what to modify.")
            return@Column
        }
        Text("Project: ${project.name}", style = MaterialTheme.typography.labelLarge)

        if (checking && status == null) {
            LoadingRow("Probing runtime → node → opencode…")
        }
        status?.let { s ->
            FormCard {
                StatusLine("Linux runtime", if (s.runtimeReady) "ready" else "missing")
                StatusLine("Node", s.nodeVersion ?: "not found")
                StatusLine("OpenCode", s.opencodeVersion ?: "not installed")
                if (!s.runtimeReady) {
                    Text("Prepare the environment in Settings → Runtime first.")
                } else if (s.nodeVersion == null) {
                    Text("Node.js was not found in Ubuntu. Run Config → ⚡ Configurar tudo (one-click setup).")
                } else if (s.opencodeVersion == null) {
                    Button(
                        onClick = {
                            installing = true
                            error = null
                            val listener = agentListener(
                                onText = { chunk -> scope.launch { output += chunk } },
                                onExit = { code ->
                                    scope.launch {
                                        installing = false
                                        output += "\n[install exit $code]\n"
                                        refresh()
                                    }
                                    EliCodeService.taskFinished(context, "opencode-install")
                                }
                            )
                            EliCodeService.taskStarted(context, "opencode-install", "Installing OpenCode")
                            scope.launch(Dispatchers.IO) {
                                val r = graph.agent.install(listener)
                                if (r is EliResult.Err) withContext(Dispatchers.Main) {
                                    installing = false
                                    error = r.error
                                    EliCodeService.taskFinished(context, "opencode-install")
                                } else {
                                    (r as EliResult.Ok).let {
                                        EliCodeService.taskStarted(context, it.value.first, "Installing OpenCode")
                                    }
                                }
                            }
                        },
                        enabled = !installing
                    ) { Text(if (installing) "Installing…" else "Install OpenCode (npm -g)") }
                }
            }
        }

        error?.let { ErrorCard(it, onDismiss = { error = null }) }

        if (status?.opencodeVersion != null) {
            FormCard {
                OutlinedTextField(
                    value = graph.prefs.opencodeModel,
                    onValueChange = { graph.prefs.opencodeModel = it },
                    label = { Text("Model (optional, e.g. anthropic/claude-…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Ask OpenCode to modify this project…") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            error = null
                            changed = emptyList()
                            output = ""
                            running = true
                            val dir = java.io.File(project.path)
                            val listener = agentListener(
                                onText = { chunk -> scope.launch { output += chunk } },
                                onExit = { code ->
                                    scope.launch(Dispatchers.IO) {
                                        val files = graph.agent.changedFiles(dir)
                                        withContext(Dispatchers.Main) {
                                            running = false
                                            runId = null
                                            changed = files
                                            graph.session.refreshTree()
                                            output += "\n[agent exit $code]\n"
                                        }
                                    }
                                    runId?.let { EliCodeService.taskFinished(context, it) }
                                }
                            )
                            scope.launch(Dispatchers.IO) {
                                val r = graph.agent.run(prompt, dir, graph.prefs.opencodeModel, listener)
                                withContext(Dispatchers.Main) {
                                    when (r) {
                                        is EliResult.Ok -> {
                                            runId = r.value.first
                                            EliCodeService.taskStarted(context, r.value.first, "OpenCode agent")
                                            graph.logs.add("OpenCode", "run", "agent started: ${prompt.take(120)}")
                                        }
                                        is EliResult.Err -> {
                                            running = false
                                            error = r.error
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !running && prompt.isNotBlank()
                    ) { Text("▶ Run agent") }
                    if (running) {
                        OutlinedButton(onClick = {
                            runId?.let { graph.runtime.registry.kill(it) }
                            running = false
                        }) { Text("Stop") }
                    }
                }
            }
            SectionHeader("Output")
            MonoLogCard(output.takeLast(8000))
            if (changed.isNotEmpty()) {
                SectionHeader("Changed files (git status)")
                changed.forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun agentListener(onText: (String) -> Unit, onExit: (Int) -> Unit) =
    object : ProcessListener {
        override fun onOutput(stream: Stream, text: String) = onText(text)
        override fun onExit(code: Int) = onExit(code)
    }
