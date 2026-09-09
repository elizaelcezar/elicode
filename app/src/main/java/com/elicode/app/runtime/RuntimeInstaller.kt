package com.elicode.app.runtime

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.util.Net
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bootstrap installer for the Linux runtime. Steps:
 *
 * 1. check architecture (ARM64) + free space
 * 2. download PRoot + libs (.deb, resume, SHA-256)
 * 3. extract + install PRoot, validate ELF (AArch64) + `--version`
 * 4. download Ubuntu ARM64 rootfs (resume, SHA-256)
 * 5. extract rootfs
 * 6. configure (resolv.conf, home, tmp)
 * 7. verify (bash, ls, tool probes)
 *
 * Progress is real (bytes + stages). Cancel keeps the download cache so
 * a retry resumes instead of restarting. Never reports "installed"
 * before [RuntimeValidator] passes the core checks.
 */
class RuntimeInstaller(private val context: Context, val paths: RuntimePaths) {

    data class Package(val fileName: String, val urls: List<String>, val sha256: String) {
        val url: String get() = urls.firstOrNull().orEmpty()
    }

    data class Config(
        val version: Int,
        val minFreeBytes: Long,
        val prootDeb: Package,
        val tallocDeb: Package,
        val shmemDeb: Package,
        val rootfs: Package
    )

    interface Listener {
        fun onStage(stage: String, fraction: Float, message: String)
        fun onDone()
        fun onError(error: EliError)
    }

    val cancelled = AtomicBoolean(false)
    private val runner = JvmProcessRunner()
    private val gson = Gson()

    fun loadConfig(): Config {
        val bundled = context.assets.open("bootstrap/runtime.json").bufferedReader().use { it.readText() }
        val root = gson.fromJson(bundled, Map::class.java) as Map<*, *>
        fun pkg(key: String): Package {
            val m = root[key] as Map<*, *>
            val mirrors = (m["mirrors"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            val url = (m["url"] as? String).orEmpty()
            // HTTPS first: Android blocks cleartext http by default, so an
            // http-only primary URL used to fail every install on-device.
            val ordered = (listOf(url) + mirrors).filter { it.isNotBlank() }
                .sortedBy { if (it.startsWith("https://")) 0 else 1 }
            return Package(
                m["file"] as String,
                ordered,
                (m["sha256"] as? String).orEmpty()
            )
        }
        return Config(
            version = (root["version"] as? Double)?.toInt() ?: 1,
            minFreeBytes = (root["minFreeBytes"] as? Double)?.toLong() ?: 1_000_000_000L,
            prootDeb = pkg("proot"),
            tallocDeb = pkg("libtalloc"),
            shmemDeb = pkg("libshmem"),
            rootfs = pkg("rootfs")
        )
    }

    fun install(listener: Listener) {
        cancelled.set(false)
        try {
            val cfg = loadConfig()
            stage(listener, "prepare", 0f, "Checking device…")
            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            if (!abis.any { it == "arm64-v8a" }) {
                throw InstallFail(
                    EliError(
                        operation = "Check architecture",
                        message = "Supported ABIs: ${abis.joinToString()}",
                        probableCause = "EliCode's Linux runtime ships ARM64 binaries only.",
                        suggestedFix = "Use an ARM64 (arm64-v8a) device. x86 emulators are not supported in v0.1."
                    )
                )
            }
            val free = freeBytes()
            if (free < cfg.minFreeBytes) {
                throw InstallFail(
                    EliError(
                        operation = "Check free space",
                        message = "Free: ${free / 1_000_000}MB, required: ${cfg.minFreeBytes / 1_000_000}MB.",
                        probableCause = "Not enough storage for Ubuntu + tools.",
                        suggestedFix = "Free up space and retry. Downloads already fetched are kept."
                    )
                )
            }

            // ---- PRoot + libs ----
            stage(listener, "proot-dl", 0.05f, "Downloading PRoot…")
            val prootDeb = download(paths.downloads, cfg.prootDeb, listener, "proot-dl", 0.05f, 0.12f)
            val tallocDeb = download(paths.downloads, cfg.tallocDeb, listener, "proot-dl", 0.12f, 0.14f)
            val shmemDeb = download(paths.downloads, cfg.shmemDeb, listener, "proot-dl", 0.14f, 0.16f)

            checkCancel()
            stage(listener, "proot-x", 0.16f, "Installing PRoot…")
            val stageDir = File(paths.stage, "debs").apply { deleteRecursively(); mkdirs() }
            ArchiveExtractor.extractDeb(prootDeb, File(stageDir, "proot"))
            ArchiveExtractor.extractDeb(tallocDeb, File(stageDir, "talloc"))
            ArchiveExtractor.extractDeb(shmemDeb, File(stageDir, "shmem"))
            installProotFromStage(stageDir)
            val machine = ArchiveExtractor.elfMachine(paths.prootBin)
            if (machine != ArchiveExtractor.EM_AARCH64) {
                throw InstallFail(
                    EliError(
                        operation = "Validate PRoot",
                        message = "ELF machine=${machine ?: "unknown"} (expected 183/AArch64).",
                        probableCause = "Wrong-architecture PRoot binary.",
                        suggestedFix = "Delete runtime/downloads and retry on an ARM64 device."
                    )
                )
            }
            paths.prootBin.setExecutable(true, false)
            stage(listener, "proot-test", 0.2f, "Testing PRoot…")
            val ver = Execs.run(
                runner,
                listOf(paths.prootBin.absolutePath, "--version"),
                null,
                mapOf("LD_LIBRARY_PATH" to paths.toolsLib.absolutePath),
                "proot --version",
                30_000L
            )
            val verOut = (ver as? EliResult.Ok)?.value?.combined.orEmpty()
            if (ver !is EliResult.Ok || !verOut.contains("proot", ignoreCase = true)) {
                throw InstallFail(
                    EliError(
                        operation = "Test PRoot",
                        command = "${paths.prootBin.absolutePath} --version",
                        message = (ver as? EliResult.Err)?.error?.format() ?: verOut,
                        probableCause = "PRoot cannot execute (missing libs or kernel restriction).",
                        suggestedFix = "Open Diagnostics and check the Installer log, then use Repair."
                    )
                )
            }

            // ---- Ubuntu rootfs ----
            checkCancel()
            stage(listener, "rootfs-dl", 0.22f, "Downloading Ubuntu ARM64…")
            val tgz = download(paths.downloads, cfg.rootfs, listener, "rootfs-dl", 0.22f, 0.6f)

            checkCancel()
            stage(listener, "rootfs-x", 0.6f, "Extracting Ubuntu (this takes a while)…")
            paths.rootfs.deleteRecursively()
            paths.rootfs.mkdirs()
            ArchiveExtractor.extractRootfsTarGz(tgz, paths.rootfs) { p ->
                val frac = 0.6f + 0.25f * (p.entries / 4000f).coerceIn(0f, 1f)
                stage(listener, "rootfs-x", frac, "Extracting Ubuntu… ${p.entries} entries")
            }

            checkCancel()
            stage(listener, "configure", 0.87f, "Configuring Ubuntu…")
            configureRootfs()

            checkCancel()
            stage(listener, "verify", 0.9f, "Verifying runtime…")
            val report = RuntimeValidator(context, paths).validateCore()
            val failed = report.filter { it.required && !it.ok }
            if (failed.isNotEmpty()) {
                throw InstallFail(
                    EliError(
                        operation = "Verify runtime",
                        message = failed.joinToString("\n") { "${it.name}: ${it.detail}" },
                        probableCause = "A core component failed its smoke test after install.",
                        suggestedFix = "Use Repair in Settings → Runtime, or reinstall."
                    )
                )
            }

            paths.writeVersion(cfg.version)
            stage(listener, "done", 1f, "Runtime ready.")
            listener.onDone()
        } catch (e: InstallFail) {
            listener.onError(e.error)
        } catch (e: InterruptedException) {
            listener.onError(
                EliError("Install runtime", message = "Cancelled.", suggestedFix = "Retry to resume downloads.")
            )
        } catch (t: Throwable) {
            listener.onError(
                EliError.unknown("Install runtime", t).copy(
                    suggestedFix = "Check storage/network, then retry. Partial downloads resume automatically."
                )
            )
        }
    }

    /** Re-extracts cached archives + reconfigures, without re-downloading. */
    fun repair(listener: Listener) {
        cancelled.set(false)
        try {
            val cfg = loadConfig()
            stage(listener, "repair", 0f, "Repairing runtime…")
            val stageDir = File(paths.stage, "debs").apply { deleteRecursively(); mkdirs() }
            listOf(cfg.prootDeb, cfg.tallocDeb, cfg.shmemDeb).forEach { pkg ->
                val deb = File(paths.downloads, pkg.fileName)
                if (!deb.isFile) throw InstallFail(
                    EliError("Repair runtime", message = "Missing cached ${pkg.fileName}.",
                        suggestedFix = "Run full Install instead (downloads resume).")
                )
            }
            ArchiveExtractor.extractDeb(File(paths.downloads, cfg.prootDeb.fileName), File(stageDir, "proot"))
            ArchiveExtractor.extractDeb(File(paths.downloads, cfg.tallocDeb.fileName), File(stageDir, "talloc"))
            ArchiveExtractor.extractDeb(File(paths.downloads, cfg.shmemDeb.fileName), File(stageDir, "shmem"))
            installProotFromStage(stageDir)
            paths.prootBin.setExecutable(true, false)
            val tgz = File(paths.downloads, cfg.rootfs.fileName)
            if (tgz.isFile) {
                paths.rootfs.deleteRecursively()
                paths.rootfs.mkdirs()
                ArchiveExtractor.extractRootfsTarGz(tgz, paths.rootfs) { p ->
                    stage(listener, "repair", (p.entries / 4000f).coerceIn(0f, 0.8f), "Re-extracting… ${p.entries}")
                }
                configureRootfs()
            }
            val report = RuntimeValidator(context, paths).validateCore()
            val failed = report.filter { it.required && !it.ok }
            if (failed.isNotEmpty()) {
                throw InstallFail(EliError("Repair runtime",
                    message = failed.joinToString("\n") { "${it.name}: ${it.detail}" },
                    suggestedFix = "Full reinstall may be needed."))
            }
            paths.writeVersion(cfg.version)
            stage(listener, "done", 1f, "Runtime repaired.")
            listener.onDone()
        } catch (e: InstallFail) {
            listener.onError(e.error)
        } catch (t: Throwable) {
            listener.onError(EliError.unknown("Repair runtime", t))
        }
    }

    /**
     * Deletes a corrupted runtime tree + version marker so the next
     * install starts clean. Download caches are kept (they resume).
     */
    fun wipeRuntime() {
        cancelled.set(true)
        paths.rootfs.deleteRecursively()
        paths.stage.deleteRecursively()
        paths.versionFile.delete()
        paths.rootfs.mkdirs()
        paths.stage.mkdirs()
    }

    /** Deletes cached archives + partial (.part) files. Next install re-downloads. */
    fun clearDownloads() {
        paths.downloads.listFiles()?.forEach { runCatching { it.delete() } }
    }

    // ---------------- internals ----------------

    private class InstallFail(val error: EliError) : Exception(error.format())

    private fun checkCancel() {
        if (cancelled.get()) throw InterruptedException("cancelled")
    }

    private fun stage(l: Listener, s: String, f: Float, m: String) = l.onStage(s, f, m)

    private fun freeBytes(): Long {
        return runCatching {
            val sf = StatFs(context.filesDir.absolutePath)
            sf.availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    }

    private fun download(
        dir: File, pkg: Package, listener: Listener,
        stage: String, from: Float, to: Float
    ): File {
        val dest = File(dir, pkg.fileName)
        val part = File(dir, pkg.fileName + ".part")
        if (dest.isFile) {
            if (pkg.sha256.isBlank() || Net.sha256(dest).equals(pkg.sha256, ignoreCase = true)) {
                listener.onStage(stage, to, "${pkg.fileName} already cached.")
                // A stale .part next to a good cache only confuses resume.
                if (part.isFile) part.delete()
                return dest
            } else {
                // Corrupt cache: drop BOTH files, otherwise Net resumes the
                // bad .part and the checksum fails again forever.
                dest.delete()
                part.delete()
                listener.onStage(stage, from, "${pkg.fileName} cache corrupt — re-downloading…")
            }
        }
        try {
            Net.downloadFirstAvailable(pkg.urls, dest, pkg.sha256, onProgress = { p ->
                val f = if (p.fraction >= 0) from + (to - from) * p.fraction else from
                val mb = if (p.bytesDone >= 0) {
                    "${p.bytesDone / 1_000_000}MB" +
                        (if (p.bytesTotal > 0) " / ${p.bytesTotal / 1_000_000}MB" else "")
                } else "trying next mirror…"
                listener.onStage(stage, f, "Downloading ${pkg.fileName}… $mb")
            }, isCancelled = { cancelled.get() })
        } catch (t: Throwable) {
            val tried = pkg.urls.joinToString("\n")
            throw InstallFail(
                EliError(
                    operation = "Download ${pkg.fileName}",
                    message = "${t.message}\nTried:\n$tried",
                    probableCause = "Network blocked or every mirror is unreachable (HTTP $t).",
                    suggestedFix = "Check the connection and retry — partial downloads resume. " +
                        "If checksums keep failing, use 'Clear downloads' in Settings → Runtime."
                )
            )
        }
        return dest
    }

    private fun installProotFromStage(stageDir: File) {
        fun find(name: String, under: File): File? =
            under.walkTopDown().firstOrNull { it.isFile && it.name == name }
        val proot = find("proot", File(stageDir, "proot"))
            ?: throw InstallFail(EliError("Install PRoot", message = "proot binary not found in .deb"))
        proot.copyTo(paths.prootBin, overwrite = true)
        // Shared libs: libtalloc.so*, libandroid-shmem.so
        stageDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".so") || ".so." in it.name) }
            .forEach { so -> so.copyTo(File(paths.toolsLib, so.name), overwrite = true) }
    }

    private fun configureRootfs() {
        // DNS inside Ubuntu.
        val resolv = File(paths.rootfs, "etc/resolv.conf")
        runCatching {
            resolv.parentFile?.mkdirs()
            if (!resolv.isFile) resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        paths.home.mkdirs()
        paths.tmp.mkdirs()
        // Minimal login profile so tools behave.
        val profile = File(paths.home, ".bashrc")
        runCatching {
            if (!profile.isFile) {
                profile.writeText(
                    "export PS1='elicode:\\w\\$ '\nexport LANG=C.UTF-8\nexport TERM=xterm-256color\n"
                )
            }
        }
    }
}
