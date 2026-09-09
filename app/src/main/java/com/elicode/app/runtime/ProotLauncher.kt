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

    fun shellLaunch(
        paths: RuntimePaths,
        workDirInGuest: String = "/projects",
        extraBinds: List<Pair<File, String>> = emptyList()
    ): Launch = build(paths, workDirInGuest, extraBinds, listOf("/bin/bash", "--login"))

    fun execLaunch(
        paths: RuntimePaths,
        guestCmd: List<String>,
        workDirInGuest: String = "/projects",
        extraBinds: List<Pair<File, String>> = emptyList()
    ): Launch = build(paths, workDirInGuest, extraBinds, listOf("/bin/bash", "--login", "-c", guestCmd.joinToString(" ")))

    private fun build(
        paths: RuntimePaths,
        workDirInGuest: String,
        extraBinds: List<Pair<File, String>>,
        guest: List<String>
    ): Launch {
        require(paths.prootBin.isFile) { "PRoot binary missing: ${paths.prootBin.absolutePath}" }
        require(paths.rootfs.isDirectory) { "Ubuntu rootfs missing: ${paths.rootfs.absolutePath}" }
        val argv = mutableListOf<String>()
        argv += paths.prootBin.absolutePath
        argv += "-r"; argv += paths.rootfs.absolutePath
        argv += "-0" // fake root inside guest
        argv += "--kernel-release=$KERNEL_RELEASE"
        // Essential pseudo-filesystems.
        argv += "-b"; argv += "/dev"
        argv += "-b"; argv += "/proc"
        argv += "-b"; argv += "/sys"
        // Writable homes/scratch.
        argv += "-b"; argv += "${paths.home.absolutePath}:/root"
        argv += "-b"; argv += "${paths.tmp.absolutePath}:/tmp"
        // Projects visible at a stable guest path.
        argv += "-b"; argv += "${paths.projects.absolutePath}:/projects"
        extraBinds.forEach { (host, guestPath) ->
            host.mkdirs()
            argv += "-b"; argv += "${host.absolutePath}:$guestPath"
        }
        argv += "-w"; argv += workDirInGuest
        argv += guest
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
