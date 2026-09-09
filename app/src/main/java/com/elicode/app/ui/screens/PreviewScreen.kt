package com.elicode.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.elicode.app.AppGraph
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.core.ProjectDetector
import com.elicode.app.ui.components.EmptyState
import com.elicode.app.ui.components.ErrorCard
import com.elicode.app.ui.components.FormCard
import com.elicode.app.ui.components.MonoLogCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = graph.session.project
    val server by graph.preview.server.collectAsState()
    val lastChange by graph.preview.lastChange.collectAsState()
    var command by remember(project?.path) { mutableStateOf("") }
    var portText by remember(project?.path) {
        mutableStateOf(
            project?.let { ProjectDetector.detect(java.io.File(it.path)) }
                ?.let { ProjectDetector.defaultPort(it.type).toString() } ?: "8080"
        )
    }
    var error by remember { mutableStateOf<EliError?>(null) }
    var starting by remember { mutableStateOf(false) }
    var webNonce by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var autoReload by remember { mutableStateOf(true) }

    // Live preview: project file changes reload the WebView (debounced
    // by the 2s watch poll; skipped while no server is running).
    LaunchedEffect(lastChange) {
        if (autoReload && lastChange > 0 && server != null && graph.preview.isRunning()) {
            webView?.reload()
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Preview", style = MaterialTheme.typography.headlineSmall)
        if (project == null) {
            EmptyState("Open a project to preview it.")
            return@Column
        }
        val type = remember(project.path) {
            ProjectDetector.detect(java.io.File(project.path)).type
        }
        Text("${project.name} · ${type.label}", style = MaterialTheme.typography.labelLarge)

        FormCard {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Server command (blank = auto: ${graph.preview.defaultCommand(type, portText.toIntOrNull() ?: 8080).take(60)}…)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        error = null
                        starting = true
                        scope.launch(Dispatchers.IO) {
                            val dir = java.io.File(project.path)
                            val res = graph.preview.start(
                                dir, type,
                                portHint = portText.toIntOrNull() ?: 8080,
                                customCommand = command
                            )
                            launch(Dispatchers.Main) {
                                starting = false
                                when (res) {
                                    is EliResult.Ok -> {
                                        graph.prefs.lastPreviewPort = res.value.port
                                        graph.logs.add("Preview", "server", "started ${res.value.url}")
                                        webNonce++
                                    }
                                    is EliResult.Err -> {
                                        graph.logs.add("Preview", "server", "FAILED: ${res.error.format()}")
                                        error = res.error
                                    }
                                }
                            }
                        }
                    },
                    enabled = !starting && server == null
                ) { Text(if (starting) "…" else "▶ Run") }
                if (server != null) {
                    OutlinedButton(onClick = {
                        graph.preview.stop()
                        graph.logs.add("Preview", "server", "stopped")
                    }) { Text("Stop") }
                }
                OutlinedButton(onClick = { autoReload = !autoReload }) {
                    Text(if (autoReload) "Live ✓" else "Live off")
                }
            }
        }

        error?.let { ErrorCard(it, onDismiss = { error = null }) }

        server?.let { s ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    s.url + (if (graph.preview.isRunning()) "" else " (starting…)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload")
                }
                IconButton(onClick = {
                    val i = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(s.url)
                    )
                    runCatching { context.startActivity(i) }
                }) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser")
                }
                IconButton(onClick = { graph.preview.stop() }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop server")
                }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = WebViewClient()
                        loadUrl(s.url)
                        webView = this
                    }
                },
                update = { wv ->
                    if (webNonce > 0) {
                        wv.loadUrl(s.url)
                        webNonce = 0
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            MonoLogCard(graph.preview.recentOutput().takeLast(1500))
        } ?: run {
            EmptyState("Start the dev server, then the app preview appears here.")
        }
    }
}
