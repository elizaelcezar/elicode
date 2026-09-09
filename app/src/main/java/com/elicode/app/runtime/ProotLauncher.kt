package com.elicode.app.runtime

import java.io.File

/**
 * Builds PRoot command lines.
 *
 * Layout: `[proot, -r rootfs, binds..., -w workdir, --kernel-release, /bin/bash, ...]`
 * with `-0` (fake root) so apt/dpkg-style tooling works without Android root.
 *
 * Security note: PRoot is NOT a security sandbox (no container/KVM
 * isolation). It provides filesystem namespacing for dev tools only.
 */
object ProotLauncher {

    const val KERNEL_RELEASE = "5.15.0"

    data class Launch(
        val argv: List<String>,
        val env: Map<String, String>
    )

    /**
     * System dynamic linker for this arch (arm64/x86_64 → linker64).
     * Invoking PRoot *through* the linker only needs READ permission on
     * the binary — the escape hatch for devices that deny exec on
     * app-private files (error=13, Permission denied).
     */
    fun linkerName(arch: String): String =
        if (arch == ArchSupport.X86_64 || arch == ArchSupport.ARM64) "linker64" else "linker"

    fun systemLinker(arch: String): File {
        val direct = File("/system/bin/" + linkerName(arch))
        return if (direct.isFile) direct else File(linkerName(arch))
    }

    /**
     * Prefixes a proot argv with an explicit linker invocation:
     * [linker, --library-path, lib, proot, args...]. Pure function —
     * unit-tested (ProotLinkerTest).
     */
    fun linkerArgv(linker: String, toolsLib: String, prootBin: String, rest: List<String>): List<String> =
        listOf(linker, "--library-path", toolsLib, prootBin) + rest

    fun shellLaunch(
        paths: RuntimePaths,
        workDirInGuest: String = "/projects",
        extraBinds: List<Pair<File, String>> = emptyList(),
        useLinker: Boolean = false
    ): Launch = build(paths, workDirInGuest, extraBinds, listOf("/bin/bash", "--login"), useLinker)

    /**
     * Fullscreen/interactive guest program (e.g. `opencode` TUI) on the
     * PTY: `exec` replaces the login shell so signals and job control
     * hit the program directly, not an intermediate bash.
     */
    fun tuiLaunch(
        paths: RuntimePaths,
        guestCmd: List<String>,
        workDirInGuest: String = "/projects",
        extraBinds: List<Pair<File, String>> = emptyList(),
        useLinker: Boolean = false
    ): Launch = build(
        paths, workDirInGuest, extraBinds,
        listOf("/bin/bash", "--login", "-c", "exec " + guestCmd.joinToString(" ")),
        useLinker
    )

    fun execLaunch(
        paths: RuntimePaths,
        guestCmd: List<String>,
        workDirInGuest: String = "/projects",
        extraBinds: List<Pair<File, String>> = emptyList(),
        useLinker: Boolean = false
    ): Launch = build(paths, workDirInGuest, extraBinds, listOf("/bin/bash", "--login", "-c", guestCmd.joinToString(" ")), useLinker)

    private fun build(
        paths: RuntimePaths,
        workDirInGuest: String,
        extraBinds: List<Pair<File, String>>,
        guest: List<String>,
        useLinker: Boolean = false
    ): Launch {
        require(paths.prootBin.isFile) { "PRoot binary missing: ${paths.prootBin.absolutePath}" }
        require(paths.rootfs.isDirectory) { "Ubuntu rootfs missing: ${paths.rootfs.absolutePath}" }
        val argv = mutableListOf<String>()
        val prootArgs = mutableListOf<String>()
        prootArgs += paths.prootBin.absolutePath
        prootArgs += "-r"; prootArgs += paths.rootfs.absolutePath
        prootArgs += "-0" // fake root inside guest
        prootArgs += "--kernel-release=$KERNEL_RELEASE"
        // Essential pseudo-filesystems.
        prootArgs += "-b"; prootArgs += "/dev"
        prootArgs += "-b"; prootArgs += "/proc"
        prootArgs += "-b"; prootArgs += "/sys"
        // Writable homes/scratch.
        prootArgs += "-b"; prootArgs += "${paths.home.absolutePath}:/root"
        prootArgs += "-b"; prootArgs += "${paths.tmp.absolutePath}:/tmp"
        // Projects visible at a stable guest path.
        prootArgs += "-b"; prootArgs += "${paths.projects.absolutePath}:/projects"
        extraBinds.forEach { (host, guestPath) ->
            host.mkdirs()
            prootArgs += "-b"; prootArgs += "${host.absolutePath}:$guestPath"
        }
        prootArgs += "-w"; prootArgs += workDirInGuest
        prootArgs += guest
        if (useLinker) {
            val arch = ArchSupport.selectArch(
                android.os.Build.SUPPORTED_ABIS?.toList().orEmpty()
            )
            val linker = systemLinker(arch.ifBlank { ArchSupport.ARM64 })
            argv += linkerArgv(
                linker.absolutePath,
                paths.toolsLib.absolutePath,
                prootArgs.removeAt(0),
                prootArgs
            )
        } else {
            argv += prootArgs
        }
        val env = mapOf(
            "LD_LIBRARY_PATH" to paths.toolsLib.absolutePath,
            "PROOT_TMPDIR" to paths.tmp.absolutePath,
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            // Guest PATH: keep Ubuntu tools first.
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin"
        )
        return Launch(argv, env)
    }

    /** Guest-visible path for a host project dir (bound under /projects or extra bind). */
    fun guestPathFor(hostProjectsRoot: File, projectDir: File): String {
        val root = hostProjectsRoot.canonicalPath
        val p = projectDir.canonicalPath
        return if (p == root || p.startsWith("$root/")) {
            "/projects/" + p.removePrefix("$root/").trim('/')
        } else {
            // External (SAF) project: caller must add an extra bind; default mount point:
            "/ext"
        }
    }
}
