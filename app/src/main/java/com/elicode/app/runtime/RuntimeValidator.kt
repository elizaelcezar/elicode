package com.elicode.app.runtime

import android.content.Context
import android.os.Build
import com.elicode.app.core.EliResult
import java.io.File

/**
 * Validates the installed runtime with real smoke tests.
 * Core checks must ALL pass before the app reports "installed".
 * Tool checks (git/node/java/...) are informational: missing tools
 * map to optional toolchain installs, not to a broken runtime.
 */
class RuntimeValidator(context: Context, private val paths: RuntimePaths) {

    data class Check(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val required: Boolean = true
    )

    private val runner = JvmProcessRunner()

    fun validateCore(): List<Check> {
        val out = mutableListOf<Check>()
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val arch = ArchSupport.selectArch(abis)
        out += Check(
            "arch", arch.isNotEmpty(),
            "arch=$arch ABIs: ${abis.joinToString()}"
        )
        val elf = ArchiveExtractor.elfMachine(paths.prootBin)
        val elfOk = elf == ArchiveExtractor.EM_AARCH64 || elf == ArchiveExtractor.EM_X86_64
        out += Check(
            "proot-binary", paths.prootBin.isFile && elfOk,
            "ELF machine=$elf (want 183/arm64 or 62/x86_64), " +
                "size=${paths.prootBin.length()}, exec=${paths.prootBin.canExecute()}, " +
                "linker=${paths.useLinker()}"
        )
        out += Check(
            "proot-runs",
            prootVersion().isNotBlank(),
            prootVersion().ifBlank { "proot --version failed" }
        )
        val bash = File(paths.rootfs, "bin/bash")
        val bashUsr = File(paths.rootfs, "usr/bin/bash")
        // Ubuntu 22.04 uses merged-/usr: /bin is a symlink to usr/bin. Some
        // devices fail to create symlinks on first extract, so accept the
        // real path too instead of reporting a healthy rootfs as corrupt.
        val bashOk = bash.isFile || bashUsr.isFile
        out += Check(
            "rootfs-bash", bashOk,
            when {
                bash.isFile -> "bin/bash present"
                bashUsr.isFile -> "usr/bin/bash present (merged-/usr, /bin symlink missing)"
                else -> "rootfs/bin/bash AND usr/bin/bash missing — rootfs incomplete"
            }
        )
        val echo = guestEcho()
        out += Check("guest-bash", echo == "elicode-ok", "bash echo -> '$echo'")
        out += Check(
            "native-pty", NativeBridge.AVAILABLE,
            if (NativeBridge.AVAILABLE) "libelicode_bridge.so loaded (real PTY + ^C)"
            else "libelicode_bridge.so not loaded — JVM pipe fallback (no true ^C)",
            required = false
        )
        return out
    }

    fun validateTools(): List<Check> {
        if (!isCoreOk()) return listOf(
            Check("tools", false, "Runtime core not installed — install it first.", required = false)
        )
        return listOf("git", "node", "npm", "java", "python3", "gradle", "opencode").map { tool ->
            val v = guestToolVersion(tool)
            Check(tool, v != null, v ?: "not found in guest PATH", required = false)
        }
    }

    fun isCoreOk(): Boolean = validateCore().all { !it.required || it.ok }

    /** True when a previous install left a usable tree (even if unvalidated). */
    fun hasPartialInstall(): Boolean =
        paths.prootBin.isFile ||
            File(paths.rootfs, "bin/bash").isFile ||
            File(paths.rootfs, "usr/bin/bash").isFile

    private fun prootVersion(): String {
        if (!paths.prootBin.isFile) return ""
        val argv = if (paths.useLinker()) {
            val arch = ArchSupport.selectArch(Build.SUPPORTED_ABIS?.toList().orEmpty())
            val linker = ProotLauncher.systemLinker(arch.ifBlank { ArchSupport.ARM64 })
            listOf(
                linker.absolutePath, "--library-path",
                paths.toolsLib.absolutePath, paths.prootBin.absolutePath, "--version"
            )
        } else {
            listOf(paths.prootBin.absolutePath, "--version")
        }
        val r = Execs.run(
            runner,
            argv,
            null,
            mapOf("LD_LIBRARY_PATH" to paths.toolsLib.absolutePath),
            "proot --version", 30_000L
        )
        val ok = r as? EliResult.Ok ?: return ""
        return if (ok.value.exitCode == 0) ok.value.combined.trim() else ""
    }

    private fun guestEcho(): String {
        return try {
            val launch = ProotLauncher.execLaunch(paths, listOf("echo", "elicode-ok"), useLinker = paths.useLinker())
            val r = Execs.run(runner, launch.argv, null, launch.env, "guest echo", 60_000L)
            val ok = r as? EliResult.Ok ?: return ""
            if (ok.value.exitCode == 0) ok.value.stdout.trim() else ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun guestToolVersion(tool: String): String? {
        return try {
            val launch = ProotLauncher.execLaunch(
                paths, listOf("command", "-v", tool, "&&", tool, "--version"),
                useLinker = paths.useLinker()
            )
            val r = Execs.run(runner, launch.argv, null, launch.env, "probe $tool", 60_000L)
            val ok = r as? EliResult.Ok ?: return null
            if (ok.value.exitCode != 0) return null
            ok.value.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}
