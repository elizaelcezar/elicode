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
import androidx.compose.material3.ListItem
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
import com.elicode.app.buildsys.BuildEngine
import com.elicode.app.core.BuildArtifact
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.runtime.ProcessListener
import com.elicode.app.runtime.Stream
import com.elicode.app.service.EliCodeService
import com.elicode.app.ui.components.EmptyState
import com.elicode.app.ui.components.ErrorCard
import com.elicode.app.ui.components.FormCard
import com.elicode.app.ui.components.MonoLogCard
import com.elicode.app.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BuildScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = graph.session.project
    var gradleInfo by remember { mutableStateOf<BuildEngine.GradleInfo?>(null) }
    var log by remember { mutableStateOf("") }
    var building by remember { mutableStateOf(false) }
    var buildId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<EliError?>(null) }
    var apks by remember { mutableStateOf<List<BuildArtifact>>(emptyList()) }
    var aabs by remember { mutableStateOf<List<BuildArtifact>>(emptyList()) }
    var extraArgs by remember { mutableStateOf("") }

    fun refreshOutputs() {
        project?.let {
            val dir = File(it.path)
            apks = graph.build.findApks(dir)
            aabs = graph.build.findAabs(dir)
        }
    }

    LaunchedEffect(project?.path) {
        project?.let {
            gradleInfo = (graph.build.detectGradle(File(it.path)) as? EliResult.Ok)?.value
            refreshOutputs()
        }
    }

    fun startBuild(task: String) {
        val dir = File(project!!.path)
        error = null
        log = ""
        building = true
        val listener = object : ProcessListener {
            override fun onOutput(stream: Stream, text: String) {
                scope.launch { log += text }
            }

            override fun onExit(code: Int) {
                scope.launch(Dispatchers.IO) {
                    val tail = log.takeLast(6000)
                    withContext(Dispatchers.Main) {
                        building = false
                        buildId?.let { EliCodeService.taskFinished(context, it); buildId = null }
                        refreshOutputs()
                        if (code == 0) {
                            log += "\n✅ Build finished (exit 0). See artifacts below.\n"
                            graph.logs.add("Build", task, "success")
                        } else {
                            val e = graph.build.interpretBuildFailure(tail)
                            error = e
                            log += "\n❌ ${e.probableCause}\n${e.suggestedFix}\n"
                            graph.logs.add("Build", task, "FAILED: ${e.format()}")
                        }
                    }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            val res = if (task == "assembleDebug") {
                graph.build.assembleDebug(dir, listener, extraArgs)
            } else {
                graph.build.bundleRelease(dir, listener, extraArgs)
            }
            withContext(Dispatchers.Main) {
                when (res) {
                    is EliResult.Ok -> {
                        buildId = res.value.first
                        EliCodeService.taskStarted(context, res.value.first, "Gradle $task")
                    }
                    is EliResult.Err -> {
                        building = false
                        error = res.error
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Build", style = MaterialTheme.typography.headlineSmall)
        if (project == null) {
            EmptyState("Open an Android project to build it.")
            return@Column
        }
        Text(project.name, style = MaterialTheme.typography.labelLarge)
        FormCard {
            Text("Gradle: ${gradleInfo?.command ?: "not detected"}", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = extraArgs, onValueChange = { extraArgs = it },
                label = { Text("Extra args (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { startBuild("assembleDebug") }, enabled = !building) {
                    Text("▶ Build APK")
                }
                OutlinedButton(onClick = { startBuild("bundleRelease") }, enabled = !building) {
                    Text("Build AAB")
                }
                if (building) {
                    OutlinedButton(onClick = {
                        buildId?.let { graph.runtime.registry.kill(it) }
                        building = false
                    }) { Text("Cancel") }
                }
            }
            if (!graph.runtime.isInstalled()) {
                Text(
                    "Builds run inside the Ubuntu runtime with the Android toolchain. Prepare it in Settings → Runtime.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        error?.let { ErrorCard(it, onDismiss = { error = null }) }
        if (log.isNotBlank()) {
            SectionHeader("Build log")
            MonoLogCard(log.takeLast(6000))
        }
        SectionHeader("APKs") { }
        if (apks.isEmpty()) Text("No APK found yet.", style = MaterialTheme.typography.bodySmall)
        apks.forEach { apk -> ArtifactRow(graph, apk, isApk = true) }
        SectionHeader("AABs") { }
        if (aabs.isEmpty()) Text("No AAB found yet.", style = MaterialTheme.typography.bodySmall)
        aabs.forEach { aab -> ArtifactRow(graph, aab, isApk = false) }
    }
}

@Composable
private fun ArtifactRow(graph: AppGraph, artifact: BuildArtifact, isApk: Boolean) {
    val context = LocalContext.current
    val f = artifact.file
    val sizeMb = f.length() / 1_000_000
    ListItem(
        headlineContent = { Text(f.name) },
        supportingContent = {
            Text("${artifact.variant} · ${sizeMb}MB · ${f.absolutePath}", style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Row {
                if (isApk) {
                    androidx.compose.material3.TextButton(onClick = {
                        if (!graph.build.canInstallUnknownApps()) {
                            runCatching {
                                context.startActivity(graph.build.unknownSourcesIntent())
                            }
                        } else {
                            runCatching {
                                context.startActivity(graph.build.installIntent(f))
                            }
                        }
                    }) { Text("Install") }
                }
                androidx.compose.material3.TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(graph.build.shareIntent(f), "Share ${f.name}")
                        )
                    }
                }) { Text("Share") }
            }
        }
    )
}
