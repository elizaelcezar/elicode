package com.elicode.app.runtime

import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Blocking exec helper with timeout: starts a process, waits, kills on
 * timeout, returns captured output. For streaming/long processes use
 * [ProcessRunner.start] + [ProcessRegistry] instead.
 */
object Execs {

    fun run(
        runner: ProcessRunner,
        cmd: List<String>,
        cwd: File?,
        env: Map<String, String>,
        label: String,
        timeoutMs: Long = 60_000L
    ): EliResult<ProcResult> {
        val proc = try {
            runner.start(cmd, cwd, env, label, null)
        } catch (t: Throwable) {
            return EliResult.Err(EliError.unknown("start $label", t).copy(command = cmd.joinToString(" ")))
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (proc.isAlive && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (proc.isAlive) {
            proc.kill()
            return EliResult.Err(
                EliError(
                    operation = label,
                    command = cmd.joinToString(" "),
                    exitCode = 124,
                    message = "Timed out after ${timeoutMs / 1000}s.",
                    probableCause = "The command hung (waiting for input or network).",
                    suggestedFix = "Run it in the Terminal tab instead, where you can interact and cancel."
                )
            )
        }
        val snap = proc.snapshot()
        return EliResult.Ok(ProcResult(snap.exitCode, snap.stdout, snap.stderr))
    }
}
