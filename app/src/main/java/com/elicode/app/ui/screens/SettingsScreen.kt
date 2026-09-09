package com.elicode.app.ui.screens

import android.os.Build
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
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.core.logs.LogStore
import com.elicode.app.runtime.RuntimeInstaller
import com.elicode.app.service.EliCodeService
import com.elicode.app.ui.components.ErrorCard
import com.elicode.app.ui.components.FormCard
import com.elicode.app.ui.components.MonoLogCard
import com.elicode.app.ui.components.ProgressRow
import com.elicode.app.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

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
        Text("EliCode ${appVersion(context)} · minSdk 28 · arm64-v8a",
            style = MaterialTheme.typography.labelSmall)
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
