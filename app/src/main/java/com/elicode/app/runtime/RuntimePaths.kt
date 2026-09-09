package com.elicode.app.runtime

import android.content.Context
import java.io.File

/**
 * On-disk layout (all inside the app-private files dir, never public storage):
 *
 * ```
 * filesDir/elicode/
 *   runtime/
 *     rootfs/        Ubuntu ARM64 tree (proot -r target)
 *     tools/proot    PRoot static binary (aarch64)
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
     * True when this device denies direct exec of the PRoot binary
     * (error=13) and every launch must go through the system linker
     * (read-only workaround). Set by the installer's proot smoke test.
     */
    fun useLinker(): Boolean =
        runCatching { linkerModeFile.isFile && linkerModeFile.readText().trim() == "1" }
            .getOrDefault(false)

    fun writeLinkerMode(use: Boolean) {
        runCatching { linkerModeFile.writeText(if (use) "1" else "0") }
    }

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
