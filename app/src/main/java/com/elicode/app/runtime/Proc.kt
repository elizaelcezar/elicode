package com.elicode.app.runtime

/**
 * Process abstractions. [ProcessRunner] spawns OS processes, [EliProcess]
 * manages their lifecycle with streamed stdout/stderr.
 *
 * Implemented on top of [java.lang.Process] so it works both on the
 * Android host (real `sh`) and inside the Ubuntu runtime (via PRoot).
 * [NativeProcessRunner] upgrades spawning to a real PTY + process-group
 * signals without changing callers — see [defaultProcessRunner].
 */
interface ProcessListener {
    fun onOutput(stream: Stream, text: String)
    fun onExit(code: Int)
}

enum class Stream { STDOUT, STDERR }

data class ProcResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val combined: String get() = (stdout + stderr).trim()
}

interface EliProcess {
    val label: String
    val pid: Long
    val isAlive: Boolean
    fun writeStdin(text: String)
    fun closeStdin()
    /**
     * Best-effort foreground interrupt (Ctrl+C): SIGINT to the process
     * group on native processes, ETX byte on pipe-based JVM processes.
     */
    fun interrupt()
    fun kill()
    fun waitFor(): Int
    fun snapshot(): ProcResult
}

interface ProcessRunner {
    fun start(
        cmd: List<String>,
        cwd: java.io.File?,
        env: Map<String, String>,
        label: String,
        listener: ProcessListener?
    ): EliProcess
}
