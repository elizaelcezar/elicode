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

    // ---- live reload: file watcher ----

    private val _lastChange = MutableStateFlow(0L)
    /** Epoch-ms of the last detected project file change (0 = none yet). */
    val lastChange: StateFlow<Long> = _lastChange

    @Volatile private var watching = false
    private var watchThread: Thread? = null

    companion object {
        private val IGNORED_DIRS = setOf(".git", "node_modules", "build", ".gradle", "__pycache__", ".cxx")

        /**
         * Cheap project fingerprint: relative path + mtime + size over a
         * capped file set. Pure function — unit-tested (PreviewWatchTest).
         */
        fun fingerprint(dir: File, maxFiles: Int = 3000): Long {
            if (!dir.isDirectory) return 0L
            var h = 17L
            var count = 0
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.addLast(dir to 0)
            while (stack.isNotEmpty() && count < maxFiles) {
                val (d, depth) = stack.removeLast()
                val kids = d.listFiles() ?: continue
                for (f in kids) {
                    if (f.isDirectory) {
                        if (depth < 8 && f.name !in IGNORED_DIRS) stack.addLast(f to depth + 1)
                    } else {
                        h = h * 31 + f.relativeTo(dir).path.hashCode()
                        h = h * 31 + f.lastModified()
                        h = h * 31 + f.length()
                        count++
                        if (count >= maxFiles) break
                    }
                }
            }
            return h
        }
    }

    private fun startWatch(dir: File) {
        stopWatch()
        watching = true
        watchThread = Thread({
            var last = fingerprint(dir)
            while (watching) {
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!watching) break
                val now = runCatching { fingerprint(dir) }.getOrDefault(last)
                if (now != last) {
                    last = now
                    _lastChange.value = System.currentTimeMillis()
                }
            }
        }, "elic-preview-watch").apply { isDaemon = true; start() }
    }

    private fun stopWatch() {
        watching = false
        watchThread?.interrupt()
        watchThread = null
    }

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
                _lastChange.value = 0L
                startWatch(projectDir)
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
        stopWatch()
        _server.value?.let { runCatching { runtime.registry.kill(it.processId) } }
        _server.value = null
    }

    fun recentOutput(): String = synchronized(output) { output.toString().takeLast(6000) }

    fun isRunning(): Boolean {
        val s = _server.value ?: return false
        return runtime.registry.get(s.processId)?.isAlive == true
    }
}
