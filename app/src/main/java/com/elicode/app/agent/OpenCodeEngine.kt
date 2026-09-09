package com.elicode.app.agent

import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.runtime.EliProcess
import com.elicode.app.runtime.ProcessListener
import com.elicode.app.runtime.RuntimeManager
import com.elicode.app.util.Shell
import java.io.File

/**
 * Real OpenCode integration. Strategy (per architecture):
 * Android → EliCode runtime → Ubuntu ARM64 → Node/npm → OpenCode CLI.
 *
 * - [status] probes `opencode version` inside the guest.
 * - [install] runs `npm install -g opencode-ai` in the guest (tracked).
 * - [run] executes `opencode run "<prompt>"` in the project dir (tracked,
 *   streaming; caller refreshes the file tree afterwards).
 * - [startWeb]/[stopWeb] manage `opencode web --port P --hostname 0.0.0.0`
 *   for the WebView bridge.
 */
class OpenCodeEngine(private val runtime: RuntimeManager) {

    data class Status(
        val runtimeReady: Boolean,
        val nodeVersion: String?,
        val opencodePath: String?,
        val opencodeVersion: String?
    )

    fun status(): Status {
        if (!runtime.isInstalled()) return Status(false, null, null, null)
        val node = runtime.execInRuntime("command -v node && node --version", null, 30_000L)
        val nodeVer = (node as? EliResult.Ok)?.value?.stdout?.trim()?.lineSequence()?.lastOrNull()
        val probe = runtime.execInRuntime("command -v opencode && opencode version", null, 30_000L)
        val ok = probe as? EliResult.Ok
        return if (ok != null && ok.value.exitCode == 0) {
            val lines = ok.value.stdout.trim().lines()
            Status(true, nodeVer, lines.firstOrNull(), lines.getOrNull(1) ?: lines.firstOrNull())
        } else {
            Status(true, nodeVer, null, null)
        }
    }

    /** Installs/updates OpenCode via npm inside the guest. Tracked process. */
    fun install(listener: ProcessListener?): EliResult<Pair<String, EliProcess>> {
        return runtime.startGuestTracked(
            bashCmd = "npm install -g opencode-ai && opencode version",
            projectDir = null,
            label = "opencode-install",
            listener = listener
        )
    }

    /**
     * Runs the agent on [prompt] with the project dir as cwd.
     * Context exported to the agent: project path, OS, arch, tool versions.
     */
    fun run(
        prompt: String,
        projectDir: File,
        model: String,
        listener: ProcessListener?
    ): EliResult<Pair<String, EliProcess>> {
        if (prompt.isBlank()) {
            return EliResult.Err(EliError("OpenCode run", message = "Empty prompt."))
        }
        val context = buildString {
            append("Project: ${projectDir.absolutePath}. ")
            append("OS: Ubuntu ARM64 under PRoot on Android. ")
            append("Reply concisely; modify files in the current directory when asked. ")
        }
        val full = "$context\n\nUser request: $prompt"
        val modelArg = if (model.isBlank()) "" else " --model ${Shell.quote(model)}"
        return runtime.startGuestTracked(
            bashCmd = "opencode run$modelArg ${Shell.quote(full)}",
            projectDir = projectDir,
            label = "opencode-run",
            listener = listener
        )
    }

    fun startWeb(
        projectDir: File,
        port: Int,
        listener: ProcessListener?
    ): EliResult<Pair<String, EliProcess>> {
        require(port in 1..65535) { "Invalid port $port" }
        return runtime.startGuestTracked(
            bashCmd = "opencode web --port $port --hostname 0.0.0.0",
            projectDir = projectDir,
            label = "opencode-web",
            listener = listener
        )
    }

    fun stop(id: String): Boolean = runtime.registry.kill(id)

    /** Best-effort list of files changed by the last agent run (via git). */
    fun changedFiles(projectDir: File): List<String> {
        val r = runtime.execInRuntime(
            "git status --short 2>/dev/null || true",
            projectDir, 15_000L
        )
        val ok = r as? EliResult.Ok ?: return emptyList()
        return ok.value.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
