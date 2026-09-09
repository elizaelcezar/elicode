package com.elicode.app.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elicode.app.core.EliError
import com.elicode.app.runtime.EliProcess
import com.elicode.app.runtime.ProcessListener
import com.elicode.app.runtime.RuntimeManager
import com.elicode.app.runtime.Stream
import com.elicode.app.service.EliCodeService
import com.elicode.app.ui.SessionState
import com.elicode.app.util.Shell
import java.io.File

/**
 * Real Linux terminal: an interactive shell process (guest bash via PRoot
 * when installed, host `sh` otherwise) with streaming output, stdin input,
 * destructive-command confirmation and restart/kill controls.
 */
class TerminalController(
    private val runtime: RuntimeManager,
    private val session: SessionState,
    private val context: android.content.Context,
    private val log: (String, String, String) -> Unit
) {
    val lines = mutableStateListOf<String>()
    var shellLabel = mutableStateOf("…")
    var running = mutableStateOf(false)
    var pendingConfirm = mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var shellId: String? = null
    private var shell: EliProcess? = null

    private fun emit(s: String) {
        main.post {
            // Keep the buffer bounded for phones.
            lines.add(s)
            if (lines.size > 1500) lines.removeRange(0, lines.size - 1500)
        }
    }

    fun start() {
        if (running.value) return
        val projectDir = session.projectDir()
        val listener = object : ProcessListener {
            override fun onOutput(stream: Stream, text: String) {
                emit(text)
                log("Terminal", "shell", text.take(500))
            }

            override fun onExit(code: Int) {
                emit("\n[shell exited: $code — tap ⟳ to restart]\n")
                main.post { running.value = false }
                shellId?.let { EliCodeService.taskFinished(context, "shell-$it") }
            }
        }
        try {
            val (id, proc) = runtime.startShell(projectDir, listener)
            shellId = id
            shell = proc
            val guest = runtime.isInstalled()
            main.post {
                shellLabel.value = if (guest) "ubuntu ✓" else "host sh"
                running.value = true
            }
            emit(if (guest) "EliCode Ubuntu shell — ${projectDir?.name ?: "/projects"}\n"
            else "Runtime not installed — host `sh` fallback. Run 'Prepare environment' for full Ubuntu.\n")
            EliCodeService.taskStarted(context, "shell-$id", "Terminal shell")
        } catch (t: Throwable) {
            emit("\nFailed to start shell: ${t.message}\n")
        }
    }

    fun send(raw: String) {
        val cmd = raw.trimEnd()
        if (cmd.isEmpty()) return
        if (Shell.isDestructive(cmd)) {
            pendingConfirm.value = cmd
            return
        }
        deliver(cmd)
    }

    fun confirmSend() {
        pendingConfirm.value?.let { deliver(it) }
        pendingConfirm.value = null
    }

    private fun deliver(cmd: String) {
        val p = shell
        if (p == null || !p.isAlive) {
            emit("\n[shell not running — restarting]\n")
            start()
            main.postDelayed({ shell?.writeStdin(cmd + "\n") }, 600)
            return
        }
        emit("$ $cmd\n")
        p.writeStdin(cmd + "\n")
    }

    fun interrupt() {
        // SIGINT equivalent for pipe shells: ETX char; fallback kills line.
        shell?.writeStdin("\u0003\n")
    }

    fun restart() {
        kill()
        main.postDelayed({ start() }, 300)
    }

    fun kill() {
        shellId?.let {
            runtime.registry.kill(it)
            EliCodeService.taskFinished(context, "shell-$it")
        }
        shell = null
        shellId = null
        main.post { running.value = false }
    }
}

@Composable
fun TerminalView(
    runtime: RuntimeManager,
    session: SessionState,
    controller: TerminalController,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        if (!controller.running.value) controller.start()
    }
    LaunchedEffect(controller.lines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    DisposableEffect(Unit) {
        onDispose { /* keep shell alive across tab switches */ }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                controller.shellLabel.value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { controller.interrupt() }) {
                Text("^C", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = { controller.restart() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Restart shell")
            }
            IconButton(onClick = { controller.kill() }) {
                Icon(Icons.Default.Cancel, contentDescription = "Kill shell")
            }
        }
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                controller.lines.joinToString(""),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(8.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                label = { Text("$") }
            )
            IconButton(onClick = {
                controller.send(input)
                input = ""
            }) {
                Text("⏎", style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    controller.pendingConfirm.value?.let { cmd ->
        com.elicode.app.ui.components.ConfirmDialog(
            title = "Dangerous command?",
            body = "This looks destructive:\n\n$cmd\n\nRun it anyway?",
            confirmLabel = "Run",
            onConfirm = { controller.confirmSend() },
            onCancel = { controller.pendingConfirm.value = null }
        )
    }
}
