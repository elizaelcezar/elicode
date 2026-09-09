package com.elicode.app.ui.components

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import com.elicode.app.core.EliResult
import com.elicode.app.runtime.EliProcess
import com.elicode.app.runtime.ProcessListener
import com.elicode.app.runtime.RuntimeManager
import com.elicode.app.runtime.Stream
import com.elicode.app.service.EliCodeService
import com.elicode.app.ui.SessionState
import com.elicode.app.util.Shell
import java.io.File

/** Which program owns the terminal tab: pure opencode TUI or a shell. */
enum class TermMode { OPENCODE, SHELL }

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

    // Pure-opencode mode: PTY TUI rendered through the VT emulator.
    var mode = mutableStateOf(TermMode.SHELL)
    val emulator = VtEmulator(80, 24)
    var screenTick = mutableStateOf(0)
    var opencodeRunning = mutableStateOf(false)
    var opencodeError = mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var shellId: String? = null
    private var shell: EliProcess? = null
    private var opId: String? = null
    private var oproc: EliProcess? = null
    private var lastTickPost = 0L

    private fun emit(s: String) {
        main.post {
            // Keep the buffer bounded for phones.
            lines.add(s)
            if (lines.size > 1500) lines.removeRange(0, lines.size - 1500)
        }
    }

    fun start() {
        if (mode.value == TermMode.OPENCODE) {
            startOpencode()
        } else {
            startShell()
        }
    }

    /** Starts the pure interactive `opencode` TUI (like a desktop terminal). */
    fun startOpencode() {
        if (opencodeRunning.value) return
        opencodeError.value = null
        val projectDir = session.projectDir()
        val startedAt = System.currentTimeMillis()
        val listener = object : ProcessListener {
            override fun onOutput(stream: Stream, text: String) {
                emulator.process(text)
                log("Terminal", "opencode", text.take(500))
                val now = System.currentTimeMillis()
                if (now - lastTickPost > 40) {
                    lastTickPost = now
                    main.post { screenTick.value++ }
                }
            }

            override fun onExit(code: Int) {
                main.post {
                    screenTick.value++
                    opencodeRunning.value = false
                    if (code == 127 && System.currentTimeMillis() - startedAt < 10_000) {
                        opencodeError.value =
                            "opencode not found in Ubuntu. Install it (AI tab → Install OpenCode), then tap ⟳."
                    } else {
                        emit("\n[opencode exited: $code — tap ⟳ to restart]\n")
                    }
                }
                opId?.let { EliCodeService.taskFinished(context, "opencode-$it") }
            }
        }
        when (val r = runtime.startOpencode(projectDir, listener)) {
            is EliResult.Ok -> {
                opId = r.value.first
                oproc = r.value.second
                main.post {
                    shellLabel.value = "opencode ✓"
                    opencodeRunning.value = true
                }
                EliCodeService.taskStarted(context, "opencode-${r.value.first}", "opencode TUI")
            }
            is EliResult.Err -> {
                opencodeError.value = "${r.error.message} ${r.error.suggestedFix}".trim()
            }
        }
    }

    /** Sends raw keystrokes to the TUI (no command checks, no echo). */
    fun sendKey(raw: String) {
        if (raw.isEmpty()) return
        val p = oproc
        if (p == null || !p.isAlive) {
            opencodeError.value = "opencode is not running — tap ⟳ to restart."
            return
        }
        p.writeStdin(raw)
    }

    fun interruptOpencode() {
        oproc?.interrupt()
    }

    fun switchMode(m: TermMode) {
        if (mode.value == m) return
        if (m == TermMode.OPENCODE) kill() else killOpencode()
        mode.value = m
        main.postDelayed({ start() }, 300)
    }

    fun killOpencode() {
        opId?.let {
            runtime.registry.kill(it)
            EliCodeService.taskFinished(context, "opencode-$it")
        }
        oproc = null
        opId = null
        main.post { opencodeRunning.value = false }
    }

    fun restartOpencode() {
        killOpencode()
        emulator.process("\u001B[2J")
        main.postDelayed({ startOpencode() }, 300)
    }

    fun startShell() {
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
        // True ^C on native processes (SIGINT to the process group);
        // ETX byte fallback on pipe-based JVM processes.
        shell?.interrupt()
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
        // Tab 1 premise: pure opencode when the runtime is ready,
        // plain shell otherwise.
        if (controller.mode.value == TermMode.SHELL && runtime.isInstalled()) {
            controller.mode.value = TermMode.OPENCODE
        }
        if (!controller.running.value && !controller.opencodeRunning.value) {
            controller.start()
        }
    }
    LaunchedEffect(controller.lines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    DisposableEffect(Unit) {
        onDispose { /* keep processes alive across tab switches */ }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = { controller.switchMode(TermMode.OPENCODE) }) {
                Text(
                    "opencode",
                    color = if (controller.mode.value == TermMode.OPENCODE)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { controller.switchMode(TermMode.SHELL) }) {
                Text(
                    "shell",
                    color = if (controller.mode.value == TermMode.SHELL)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                controller.shellLabel.value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            if (controller.mode.value == TermMode.OPENCODE) {
                IconButton(onClick = { controller.interruptOpencode() }) {
                    Text("^C", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = { controller.restartOpencode() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Restart opencode")
                }
                IconButton(onClick = { controller.killOpencode() }) {
                    Icon(Icons.Default.Cancel, contentDescription = "Kill opencode")
                }
            } else {
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
        }
        if (controller.mode.value == TermMode.OPENCODE) {
            OpencodePane(controller, Modifier.weight(1f))
        } else {
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
        }
        if (controller.mode.value == TermMode.SHELL) {
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

@Composable
private fun OpencodePane(controller: TerminalController, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    // Recompose on screen ticks (throttled to ~25fps by the controller).
    val tick = controller.screenTick.value
    val rows = remember(tick) { controller.emulator.renderedRows() }
    val defaultFg = MaterialTheme.colorScheme.onSurface

    Column(modifier.fillMaxSize()) {
        controller.opencodeError.value?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            rows.forEach { runs ->
                Text(
                    buildAnnotatedString {
                        if (runs.all { it.text.isEmpty() }) append(" ")
                        else runs.forEach { r ->
                            withStyle(
                                androidx.compose.ui.text.SpanStyle(
                                    color = if (r.fg == VtEmulator.FG_DEFAULT) defaultFg
                                    else androidx.compose.ui.graphics.Color(r.fg),
                                    background = if (r.bg == VtEmulator.BG_DEFAULT)
                                        androidx.compose.ui.graphics.Color.Transparent
                                    else androidx.compose.ui.graphics.Color(r.bg),
                                    fontWeight = if (r.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                                    textDecoration = if (r.underline)
                                        androidx.compose.ui.text.style.TextDecoration.Underline else null
                                )
                            ) { append(r.text) }
                        }
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        // Special-keys bar (phones have no Esc/arrows/Ctrl).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val keys = listOf(
                "Esc" to "\u001B",
                "Tab" to "\t",
                "↑" to "\u001B[A",
                "↓" to "\u001B[B",
                "←" to "\u001B[D",
                "→" to "\u001B[C",
                "⌫" to "\u007F"
            )
            keys.forEach { (label, seq) ->
                TextButton(
                    onClick = { controller.sendKey(seq) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
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
                label = { Text("›") }
            )
            IconButton(onClick = {
                controller.sendKey(input + "\r")
                input = ""
            }) {
                Text("⏎", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
