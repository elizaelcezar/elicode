package com.elicode.app.runtime

import com.elicode.app.core.EliResult
import java.io.File

/**
 * Shell backend used by Git (and anything that prefers host binaries with
 * a runtime fallback). [RuntimeManager] is the production implementation;
 * tests use fakes.
 */
interface GitShell {
    fun execOnHost(bashCmd: String, cwd: File?, timeoutMs: Long = 60_000L): EliResult<ProcResult>
    fun execInRuntime(bashCmd: String, projectDir: File?, timeoutMs: Long = 120_000L): EliResult<ProcResult>
    fun isInstalled(): Boolean
}
