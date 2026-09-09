package com.elicode.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.elicode.app.AppGraph
import com.elicode.app.buildsys.TestLogParser
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.core.logs.LogStore
import com.elicode.app.runtime.ProcessListener
import com.elicode.app.runtime.RuntimeInstaller
import com.elicode.app.runtime.SetupOrchestrator
import com.elicode.app.runtime.Stream
import com.elicode.app.service.EliCodeService
import com.elicode.app.ui.components.ErrorCard
import com.elicode.app.ui.components.FormCard
import com.elicode.app.ui.components.MonoLogCard
import com.elicode.app.ui.components.ProgressRow
import com.elicode.app.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(graph: AppGraph, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by graph.runtime.status.collectAsState()
    var error by remember { mutableStateOf<EliError?>(null) }
    var themeExpanded by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf(graph.prefs.themeMode) }
    var fontSize by remember { mutableStateOf(graph.prefs.editorFontSize) }
    var model by remember { mutableStateOf(graph.prefs.opencodeModel) }

    LaunchedEffect(Unit) { graph.runtime.refreshStatus(probeTools = false) }

    fun installAction(repair: Boolean) {
        error = null
        EliCodeService.taskStarted(context, "runtime-setup", if (repair) "Repairing runtime" else "Installing runtime")
        val listener = object : RuntimeInstaller.Listener {
            override fun onStage(stage: String, fraction: Float, message: String) {}
            override fun onDone() {
                EliCodeService.taskFinished(context, "runtime-setup")
            }

            override fun onError(e: EliError) {
                EliCodeService.taskFinished(context, "runtime-setup")
                error = e
            }
        }
        if (repair) graph.runtime.repair(listener)
        else graph.runtime.install(listener)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Config", style = MaterialTheme.typography.headlineSmall)

        SectionHeader("One-click setup")
        OneClickSetupCard(graph)

        SectionHeader("Testes automatizados")
        TestRunnerCard(graph)

        SectionHeader("General")
        FormCard {
            Text("Theme: $theme")
            OutlinedButton(onClick = { themeExpanded = true }) { Text("Change theme") }
            DropdownMenu(themeExpanded, { themeExpanded = false }) {
                listOf("system", "light", "dark").forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = {
                        theme = m
                        graph.prefs.themeMode = m
                        themeExpanded = false
                    })
                }
            }
            Text("Editor font size: ${fontSize.toInt()}sp")
            Slider(value = fontSize, onValueChange = {
                fontSize = it
                graph.prefs.editorFontSize = it
            }, valueRange = 10f..22f)
            OutlinedTextField(model, {
                model = it
                graph.prefs.opencodeModel = it
            }, label = { Text("OpenCode model (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
        }

        SectionHeader("Linux runtime")
        FormCard {
            Text("Status: " + if (status.installed) "installed (v${status.version})" else "not installed")
            Text("Free space: ${status.freeBytes / 1_000_000}MB")
            if (status.installing) {
                ProgressRow("Working", status.installFraction, status.installMessage)
                OutlinedButton(onClick = { graph.runtime.cancelInstall() }) { Text("Cancel") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { installAction(false) }) {
                        Text(if (status.installed) "Reinstall" else "Install")
                    }
                    // Repair works from cached downloads even when the
                    // runtime is not (yet) valid — exactly the state a
                    // failed install leaves behind. Never gate it on
                    // status.installed or the user gets stuck.
                    OutlinedButton(onClick = { installAction(true) }) {
                        Text("Repair")
                    }
                    OutlinedButton(onClick = { graph.runtime.refreshStatus(probeTools = true) }) {
                        Text("Probe tools")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            graph.runtime.clearDownloads()
                            graph.runtime.refreshStatus(probeTools = false)
                        }
                    }) { Text("Clear downloads") }
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            graph.runtime.wipeRuntime()
                            graph.runtime.refreshStatus(probeTools = false)
                        }
                    }) { Text("Wipe runtime") }
                }
            }
            if (status.toolChecks.isNotEmpty()) {
                status.toolChecks.forEach { c ->
                    Text("• ${c.name}: ${c.detail.take(80)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Optional toolchains (Node, Java, Android SDK) install on demand inside Ubuntu. " +
                    "The Android toolchain is only fetched when you build APKs.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        error?.let { ErrorCard(it, onDismiss = { error = null }) }
        status.lastError?.let { ErrorCard(it) }

        SectionHeader("Support")
        FormCard {
            Button(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Diagnostics & logs")
            }
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        graph.logs.clear()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear logs") }
        }
        Text("EliCode ${appVersion(context)} · minSdk 28 · ${status.arch.ifBlank { "arm64/x86_64" }}",
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun OneClickSetupCard(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var steps by remember { mutableStateOf(SetupOrchestrator.defaultSteps()) }
    var log by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<Boolean?>(null) }

    fun appendLog(t: String) {
        log = (log + "\n" + t).takeLast(4000)
    }

    FormCard {
        Text(
            "Installs Ubuntu runtime → Node.js → OpenCode → Android toolchain. " +
                "Safe to re-run: finished steps are skipped.",
            style = MaterialTheme.typography.bodySmall
        )
        steps.forEach { s ->
            val icon = when (s.state) {
                SetupOrchestrator.State.DONE, SetupOrchestrator.State.SKIPPED -> "✓"
                SetupOrchestrator.State.RUNNING -> "…"
                SetupOrchestrator.State.FAILED -> "✗"
                else -> "•"
            }
            Text(
                "$icon ${s.label}" + (if (s.detail.isNotBlank()) " — ${s.detail.take(80)}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (s.state == SetupOrchestrator.State.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    log = ""
                    done = null
                    running = true
                    graph.setup.start(object : SetupOrchestrator.Listener {
                        override fun onUpdate(updated: List<SetupOrchestrator.Step>) {
                            scope.launch(Dispatchers.Main) { steps = updated }
                        }

                        override fun onLog(t: String) {
                            scope.launch(Dispatchers.Main) { appendLog(t) }
                        }

                        override fun onDone(allOk: Boolean) {
                            scope.launch(Dispatchers.Main) {
                                running = false
                                done = allOk
                                graph.runtime.refreshStatus(probeTools = true)
                            }
                        }
                    })
                },
                enabled = !running
            ) { Text(if (running) "Configurando…" else "⚡ Configurar tudo") }
            if (running) {
                OutlinedButton(onClick = { graph.setup.cancel() }) { Text("Cancel") }
            }
        }
        done?.let {
            Text(
                if (it) "✓ Tudo pronto! Abra a aba Terminal."
                else "Algo falhou — veja o log acima e rode de novo.",
                style = MaterialTheme.typography.bodySmall,
                color = if (it) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        if (log.isNotBlank()) MonoLogCard(log)
    }
}

@Composable
private fun TestRunnerCard(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = graph.session.project
    var log by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var runId by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<TestLogParser.Summary?>(null) }
    var error by remember { mutableStateOf<EliError?>(null) }

    fun copyReport() {
        val s = summary
        val text = if (s != null && project != null) TestLogParser.report(project.name, s, log)
        else log.takeLast(20000).ifBlank { "No test output yet." }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("elicode-test-log", text))
        Toast.makeText(context, "Log copiado — pode colar", Toast.LENGTH_SHORT).show()
    }

    FormCard {
        Text(
            "Roda os testes do projeto aberto no Ubuntu e resume o resultado. " +
                "Se falhar, copie o log e cole no chat.",
            style = MaterialTheme.typography.bodySmall
        )
        if (project == null) {
            Text("Abra um projeto primeiro (aba Projects).", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Projeto: ${project.name}", style = MaterialTheme.typography.labelLarge)
        }
        summary?.let { s ->
            Text(
                s.headline(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (s.failed == 0 && s.buildOk) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            if (s.failedTasks.isNotEmpty()) {
                Text(
                    "Falhou: ${s.failedTasks.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        error?.let { ErrorCard(it, onDismiss = { error = null }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val dir = java.io.File(project!!.path)
                    error = null
                    summary = null
                    log = ""
                    running = true
                    val listener = object : ProcessListener {
                        override fun onOutput(stream: Stream, text: String) {
                            scope.launch { log += text }
                        }

                        override fun onExit(code: Int) {
                            scope.launch(Dispatchers.IO) {
                                val parsed = TestLogParser.summarize(log)
                                withContext(Dispatchers.Main) {
                                    running = false
                                    runId?.let {
                                        EliCodeService.taskFinished(context, it)
                                        runId = null
                                    }
                                    summary = parsed
                                    val detail = buildString {
                                        append(parsed.headline())
                                        if (parsed.failedTasks.isNotEmpty()) {
                                            append(" Failed: ${parsed.failedTasks.joinToString()}")
                                        }
                                    }.take(500)
                                    graph.logs.add("Test", project.name, detail)
                                    if (!parsed.buildOk || parsed.failed > 0) {
                                        graph.logs.add("Test", project.name, log.takeLast(3000))
                                    }
                                }
                            }
                        }
                    }
                    scope.launch(Dispatchers.IO) {
                        val res = graph.build.runUnitTests(dir, listener)
                        withContext(Dispatchers.Main) {
                            when (res) {
                                is EliResult.Ok -> {
                                    runId = res.value.first
                                    EliCodeService.taskStarted(context, res.value.first, "Unit tests")
                                }
                                is EliResult.Err -> {
                                    running = false
                                    error = res.error
                                }
                            }
                        }
                    }
                },
                enabled = !running && project != null
            ) { Text(if (running) "Rodando…" else "▶ Rodar testes") }
            if (running) {
                OutlinedButton(onClick = {
                    runId?.let { graph.runtime.registry.kill(it) }
                    running = false
                }) { Text("Cancel") }
            }
            OutlinedButton(
                onClick = { copyReport() },
                enabled = log.isNotBlank()
            ) { Text("📋 Copiar log") }
        }
        if (log.isNotBlank()) MonoLogCard(log.takeLast(6000))
    }
}

@Suppress("DEPRECATION")
fun appVersion(context: android.content.Context): String {
    return runCatching {
        val pm = context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            pm.getPackageInfo(context.packageName, 0)
        }
        "${pi.versionName} (${pi.versionCode})"
    }.getOrDefault("0.1.0")
}

@Composable
fun DiagnosticsScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by graph.runtime.status.collectAsState()
    var category by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        graph.runtime.refreshStatus(probeTools = true)
        graph.logs.onChange { tick++ }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
        SectionHeader("Environment")
        MonoLogCard(
            buildString {
                appendLine("EliCode: ${appVersion(context)}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("ABI: ${Build.SUPPORTED_ABIS?.joinToString()}")
                appendLine("Runtime installed: ${status.installed} (v${status.version})")
                appendLine("Free bytes: ${status.freeBytes}")
            }
        )
        SectionHeader("Core checks")
        if (status.coreChecks.isEmpty()) Text("Not probed yet — open Settings → Probe tools.")
        status.coreChecks.forEach { c ->
            Text(
                "${if (c.ok) "✓" else "✗"} ${c.name}: ${c.detail.take(160)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (c.ok) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error
            )
        }
        SectionHeader("Tool versions")
        if (status.toolChecks.isEmpty()) Text("Not probed yet.")
        status.toolChecks.forEach { c ->
            Text(
                "${if (c.ok) "✓" else "·"} ${c.name}: ${c.detail.take(120)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        SectionHeader("Logs")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.FilterChip(
                selected = category == null,
                onClick = { category = null; tick++ },
                label = { Text("All") }
            )
            graph.logs.categories().forEach { cat ->
                androidx.compose.material3.FilterChip(
                    selected = category == cat,
                    onClick = { category = cat; tick++ },
                    label = { Text(cat) }
                )
            }
        }
        val entries = remember(category, tick) { graph.logs.snapshot(category).takeLast(200) }
        MonoLogCard(
            entries.joinToString("\n") {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(it.time)) + " [${it.category}/${it.tag}] ${it.message}"
            }.takeLast(12000)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val text = buildString {
                    appendLine("EliCode diagnostics — ${appVersion(context)}")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("ABIs: ${Build.SUPPORTED_ABIS?.joinToString()}")
                    appendLine("Runtime installed: ${status.installed} (v${status.version}), free ${status.freeBytes / 1_000_000}MB")
                    appendLine("--- core checks ---")
                    status.coreChecks.forEach { c ->
                        appendLine("${if (c.ok) "OK " else "FAIL"} ${c.name}: ${c.detail.take(200)}")
                    }
                    appendLine("--- tools ---")
                    status.toolChecks.forEach { c ->
                        appendLine("${if (c.ok) "OK " else ".. "} ${c.name}: ${c.detail.take(120)}")
                    }
                    status.lastError?.let { appendLine("--- last error ---\n${it.format()}") }
                    appendLine("--- recent log ---")
                    append(
                        graph.logs.snapshot(null).takeLast(30).joinToString("\n") {
                            "[${it.category}/${it.tag}] ${it.message.take(300)}"
                        }
                    )
                }.take(20000)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("elicode-diagnostics", text))
                Toast.makeText(context, "Diagnóstico copiado — pode colar", Toast.LENGTH_SHORT).show()
            }) { Text("📋 Copiar diagnóstico") }
            OutlinedButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    val file = graph.logs.export()
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(intent, "Share diagnostics log")
                        )
                    }
                }
            }) { Text("Share log file") }
        }
    }
}
