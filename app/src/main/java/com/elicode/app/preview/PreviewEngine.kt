package com.elicode.app.preview

import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.core.ProjectDetector
import com.elicode.app.core.ProjectType
import com.elicode.app.runtime.EliProcess
import com.elicode.app.runtime.ProcessListener
import com.elicode.app.runtime.RuntimeManager
import com.elicode.app.runtime.Stream
import com.elicode.app.util.Ports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Dev-server manager for the Preview tab:
 * starts the project's server command as a tracked guest process,
 * sniffs the port from its output, keeps it alive across navigation.
 */
class PreviewEngine(private val runtime: RuntimeManager) {

    data class Server(
        val id: String,
        val url: String,
        val port: Int,
        val processId: String,
        val startedAt: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _server = MutableStateFlow<Server?>(null)
    val server: StateFlow<Server?> = _server

    private val output = StringBuilder()
    @Volatile private var detectPort: Int = -1

    fun defaultCommand(type: ProjectType, port: Int): String = when (type) {
        ProjectType.VITE -> "npm install --no-audit --no-fund && npm run dev -- --host 0.0.0.0 --port $port"
        ProjectType.NEXTJS, ProjectType.REACT, ProjectType.NODE ->
            "npm install --no-audit --no-fund && PORT=$port npm run dev -- --host 0.0.0.0"
        ProjectType.STATIC_WEB -> "python3 -m http.server $port"
        ProjectType.PYTHON -> "python3 main.py"
        else -> "python3 -m http.server $port"
    }

    fun start(
        projectDir: File,
        type: ProjectType,
        portHint: Int = ProjectDetector.defaultPort(type),
        customCommand: String = ""
    ): EliResult<Server> {
        stop()
        output.clear()
        detectPort = -1
        val command = customCommand.ifBlank { defaultCommand(type, portHint) }
        val listener = object : ProcessListener {
            override fun onOutput(stream: Stream, text: String) {
                synchronized(output) { output.append(text) }
                if (detectPort < 0) {
                    Ports.firstPort(text)?.let { found ->
                        detectPort = found
                        publishUrl(found)
                    }
                }
            }

            override fun onExit(code: Int) {}
        }
        val res = runtime.startGuestTracked(command, projectDir, "preview-server", listener)
        return when (res) {
            is EliResult.Ok -> {
                val (pid, _) = res.value
                // Optimistic URL on the hint; corrected when the real port is sniffed.
                val server = Server(id = pid, url = "http://127.0.0.1:$portHint", port = portHint, processId = pid)
                _server.value = server
                EliResult.Ok(server)
            }
            is EliResult.Err -> EliResult.Err(
                res.error.copy(
                    operation = "Start preview server",
                    probableCause = res.error.probableCause.ifBlank {
                        "The server command failed (missing deps or wrong project type?)."
                    },
                    suggestedFix = res.error.suggestedFix.ifBlank {
                        "For Node projects run 'npm install' in the Terminal first."
                    }
                )
            )
        }
    }

    private fun publishUrl(port: Int) {
        val cur = _server.value ?: return
        _server.value = cur.copy(url = "http://127.0.0.1:$port", port = port)
    }

    fun stop() {
        _server.value?.let { runCatching { runtime.registry.kill(it.processId) } }
        _server.value = null
    }

    fun recentOutput(): String = synchronized(output) { output.toString().takeLast(6000) }

    fun isRunning(): Boolean {
        val s = _server.value ?: return false
        return runtime.registry.get(s.processId)?.isAlive == true
    }
}
