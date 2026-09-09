package com.elicode.app.runtime

/**
 * Process abstractions. [ProcessRunner] spawns OS processes, [EliProcess]
 * manages their lifecycle with streamed stdout/stderr.
 *
 * Implemented on top of [java.lang.Process] so it works both on the
 * Android host (real `sh`) and inside the Ubuntu runtime (via PRoot).
 * A JNI/PTY upgrade is possible later without changing callers.
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
