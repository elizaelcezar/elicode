package com.elicode.app

import com.elicode.app.core.EliResult
import com.elicode.app.runtime.GitShell
import com.elicode.app.runtime.ProcResult
import java.io.File

/** In-memory fake shell backend for unit tests (no Android needed). */
class FakeShell(
    var hostGitPresent: Boolean = true,
    var installed: Boolean = false,
    var handler: (cmd: String) -> ProcResult = { ProcResult(0, "", "") }
) : GitShell {
    val hostCalls = mutableListOf<String>()
    val guestCalls = mutableListOf<String>()

    override fun execOnHost(bashCmd: String, cwd: File?, timeoutMs: Long): EliResult<ProcResult> {
        hostCalls += bashCmd
        if (bashCmd.contains("command -v git")) {
            return if (hostGitPresent) EliResult.Ok(ProcResult(0, "/usr/bin/git\ngit version 2.40.0", ""))
            else EliResult.Ok(ProcResult(1, "", "not found"))
        }
        return EliResult.Ok(handler(bashCmd))
    }

    override fun execInRuntime(bashCmd: String, projectDir: File?, timeoutMs: Long): EliResult<ProcResult> {
        guestCalls += bashCmd
        return EliResult.Ok(handler(bashCmd))
    }

    override fun isInstalled(): Boolean = installed
}
