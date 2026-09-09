package com.elicode.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.elicode.app.AppGraph
import com.elicode.app.core.EliError
import com.elicode.app.runtime.RuntimeInstaller
import com.elicode.app.ui.components.ErrorCard
import com.elicode.app.ui.components.ProgressRow
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(graph: AppGraph, onReady: () -> Unit) {
    val context = LocalContext.current
    val status by graph.runtime.status.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<EliError?>(null) }
    var working by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    fun startInstall() {
        working = true
        error = null
        ensureNotifPermission()
        com.elicode.app.service.EliCodeService.taskStarted(context, "runtime-install", "Installing Linux runtime")
        graph.runtime.install(object : RuntimeInstaller.Listener {
            override fun onStage(stage: String, fraction: Float, message: String) {
                graph.logs.add("Runtime", "install", "$stage: $message")
            }

            override fun onDone() {
                com.elicode.app.service.EliCodeService.taskFinished(context, "runtime-install")
                graph.logs.add("Runtime", "install", "Runtime ready.")
                scope.launch {
                    graph.prefs.onboardingDone = true
                    working = false
                    onReady()
                }
            }

            override fun onError(e: EliError) {
                com.elicode.app.service.EliCodeService.taskFinished(context, "runtime-install")
                graph.logs.add("Runtime", "install", "FAILED: ${e.format()}")
                scope.launch { working = false; error = e }
            }
        })
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("ELICODE", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black))
        Text(
            graph.app.getString(com.elicode.app.R.string.app_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "Real Linux terminal (Ubuntu ARM64, no Termux)",
            "OpenCode AI agent working in your project",
            "GitHub: clone, commit, pull, push",
            "Local preview for web projects",
            "Build APK/AAB and install on this device"
        ).forEach { f ->
            ListItem(
                headlineContent = { Text(f) },
                leadingContent = {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!status.arm64) {
            ErrorCard(
                EliError(
                    operation = "Device compatibility",
                    message = "This device does not expose the arm64-v8a ABI.",
                    probableCause = "The Linux runtime ships ARM64 binaries only.",
                    suggestedFix = "EliCode v0.1 requires an ARM64 device."
                )
            )
        }
        if (status.installing || working) {
            ProgressRow("Preparing environment", status.installFraction, status.installMessage)
            OutlinedButton(onClick = { graph.runtime.cancelInstall() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel (downloads resume later)")
            }
        } else if (status.installed) {
            Button(
                onClick = {
                    graph.prefs.onboardingDone = true
                    onReady()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Environment ready — continue") }
            OutlinedButton(
                onClick = { startInstall() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reinstall runtime") }
        } else {
            Button(
                onClick = { startInstall() },
                modifier = Modifier.fillMaxWidth(),
                enabled = status.arm64
            ) { Text("Prepare environment") }
            OutlinedButton(
                onClick = {
                    graph.prefs.onboardingDone = true
                    onReady()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Skip for now (limited features)") }
        }
        error?.let { ErrorCard(it, onDismiss = { error = null }, onRetry = { startInstall() }) }
        Spacer(Modifier.height(16.dp))
        Text(
            "The runtime (~30MB download, ~150MB installed) stays in EliCode's private storage. " +
                "PRoot is filesystem namespacing, not a security sandbox.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
