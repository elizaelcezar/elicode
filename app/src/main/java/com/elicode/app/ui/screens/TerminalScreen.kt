package com.elicode.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elicode.app.AppGraph
import com.elicode.app.ui.components.EmptyState
import com.elicode.app.ui.components.SectionHeader
import com.elicode.app.ui.components.TerminalController
import com.elicode.app.ui.components.TerminalView

@Composable
fun TerminalScreen(graph: AppGraph) {
    val context = LocalContext.current
    val controller = remember(graph, graph.session.project?.path) {
        TerminalController(
            runtime = graph.runtime,
            session = graph.session,
            context = context.applicationContext,
            log = { c, t, m -> graph.logs.add(c, t, m.take(300)) }
        )
    }
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        val project = graph.session.project
        if (project == null) {
            EmptyState("Open a project to get a shell in its folder.")
        }
        TerminalView(runtime = graph.runtime, session = graph.session, controller = controller)

        // Live processes (long builds, servers, agent runs).
        val procs = remember(controller.lines.size) { graph.runtime.registry.list() }
        if (procs.isNotEmpty()) {
            SectionHeader("Processes (${procs.size})")
            LazyColumn {
                items(procs, key = { it.first }) { (id, proc) ->
                    ListItem(
                        headlineContent = { Text("$id · ${proc.label}") },
                        supportingContent = {
                            Text(if (proc.isAlive) "running (pid ${proc.pid})" else "exited")
                        },
                        trailingContent = {
                            TextButton(onClick = { graph.runtime.registry.kill(id) }) {
                                Text("Kill", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                }
            }
        }
    }
}
