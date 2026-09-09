package com.elicode.app.runtime

import android.content.Context
import java.io.File

/**
 * On-disk layout (all inside the app-private files dir, never public storage):
 *
 * ```
 * filesDir/elicode/
 *   runtime/
 *   rootfs/        Ubuntu ARM64 tree (proot -r target)
 *     tools/proot    PRoot static binary (aarch64)
 *     tools/loader   PRoot ELF loader (PROOT_LOADER; from the proot .deb)
 *     tools/loader32 PRoot 32-bit loader (PROOT_LOADER_32; from the proot .deb)
 *     tools/lib/     libtalloc.so, libandroid-shmem.so (LD_LIBRARY_PATH)
 *     downloads/     cached .deb / tarball (+ .part resume files)
 *     stage/         extraction scratch dir
 *     home/          bind-mounted as /root inside Ubuntu
 *     tmp/           TMPDIR / PROOT_TMPDIR
 *     version.json   installed runtime version marker
 *   projects/        default project location
 *   cache/
 *   logs/
 * ```
 */
class RuntimePaths(context: Context) {

    val base: File = File(context.filesDir, "elicode").apply { mkdirs() }
    val runtime: File = File(base, "runtime").apply { mkdirs() }
    val rootfs: File = File(runtime, "rootfs")
    val tools: File = File(runtime, "tools").apply { mkdirs() }
    val prootBin: File = File(tools, "proot")
    /**
     * PRoot ELF loader helpers shipped inside the proot .deb
     * (`libexec/proot/loader`, `loader32`). Without PROOT_LOADER
     * pointing here, proot reports `execve("/usr/bin/bash")` ENOENT
     * ("the loader was not found") even when bash is present —
     * the kernel cannot resolve the guest INTERP
     * (/lib/ld-linux-aarch64.so.1) on the Android host by itself.
     */
    val loaderBin: File = File(tools, "loader")
    val loader32Bin: File = File(tools, "loader32")
    val toolsLib: File = File(tools, "lib").apply { mkdirs() }
    val downloads: File = File(runtime, "downloads").apply { mkdirs() }
    val stage: File = File(runtime, "stage").apply { mkdirs() }
    val home: File = File(runtime, "home").apply { mkdirs() }
    val tmp: File = File(runtime, "tmp").apply { mkdirs() }
    val versionFile: File = File(runtime, "version.json")
    val projects: File = File(base, "projects").apply { mkdirs() }
    val cache: File = File(base, "cache").apply { mkdirs() }
    private val linkerModeFile: File = File(runtime, "linker-mode")

    /**
     * PRoot binary bundled in the APK (lib/proot_exec.so, extracted by the
     * package manager into the native-library dir). Last-resort exec
     * location when the device denies exec on app-private files.
     */
    val bundledProot: File =
        File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so")

    /**
     * Proot invocation mode: 0=direct filesDir binary, 1=system linker +
     * filesDir binary, 2=system linker + APK-bundled binary, -1=unset
     * (try direct first). Set by the installer's proot smoke test.
     */
    fun prootMode(): Int =
        runCatching { if (linkerModeFile.isFile) linkerModeFile.readText().trim().toInt() else -1 }
            .getOrDefault(-1)

    fun writeProotMode(mode: Int) {
        runCatching { linkerModeFile.writeText(mode.toString()) }
    }

    /** Effective binary for launches (bundled .so only when mode==2). */
    fun effectiveProot(): File =
        if (prootMode() == 2) bundledProot.takeIf { it.isFile } ?: prootBin else prootBin

    /** True when launches must go through the system linker (modes 1-2). */
    fun useLinker(): Boolean = prootMode() >= 1

    fun installedVersion(): Int {
        if (!versionFile.isFile) return 0
        return runCatching {
            val text = versionFile.readText()
            Regex(""""version"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        }.getOrDefault(0)
    }

    fun writeVersion(v: Int) {
        versionFile.writeText("{\"version\": $v, \"installedAt\": ${System.currentTimeMillis()}}")
    }
}
