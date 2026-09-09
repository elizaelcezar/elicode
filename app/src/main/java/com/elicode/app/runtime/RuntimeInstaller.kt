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
    private val busy = AtomicBoolean(false)
    private val runner = JvmProcessRunner()
    private val gson = Gson()

    fun loadConfig(): Config {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        return loadConfigFor(ArchSupport.selectArch(abis))
    }

    /** Arch-aware loader (testable): [arch] is "arm64", "x86_64" or "" (legacy keys). */
    fun loadConfigFor(arch: String): Config {
        val bundled = context.assets.open("bootstrap/runtime.json").bufferedReader().use { it.readText() }
        val root = gson.fromJson(bundled, Map::class.java) as Map<*, *>
        fun pkg(key: String): Package {
            val m = root[key] as? Map<*, *>
                ?: throw IllegalStateException("Missing package '$key' in runtime.json")
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
        // x86_64 keys fall back to the legacy (arm64) keys when absent,
        // so a v1 manifest still installs on ARM64 devices.
        fun archPkg(base: String): Package {
            if (arch == ArchSupport.X86_64 && root.containsKey("${base}_x86_64")) {
                return pkg("${base}_x86_64")
            }
            return pkg(base)
        }
        return Config(
            version = (root["version"] as? Double)?.toInt() ?: 1,
            minFreeBytes = (root["minFreeBytes"] as? Double)?.toLong() ?: 1_000_000_000L,
            prootDeb = archPkg("proot"),
            tallocDeb = archPkg("libtalloc"),
            shmemDeb = archPkg("libshmem"),
            rootfs = archPkg("rootfs")
        )
    }

    fun install(listener: Listener) {
        if (!busy.compareAndSet(false, true)) {
            listener.onError(
                EliError(
                    "Install runtime",
                    message = "An install or repair is already running.",
                    suggestedFix = "Wait for it to finish, or restart the app if it is stuck."
                )
            )
            return
        }
        cancelled.set(false)
        try {
            val cfg = loadConfig()
            stage(listener, "prepare", 0f, "Checking device…")
            val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            val arch = ArchSupport.selectArch(abis)
            if (arch.isEmpty()) {
                throw InstallFail(
                    EliError(
                        operation = "Check architecture",
                        message = "Supported ABIs: ${abis.joinToString()}",
                        probableCause = "EliCode's Linux runtime ships ARM64 + x86_64 binaries only.",
                        suggestedFix = "Use an ARM64 device or an x86_64 emulator."
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
            download(paths.downloads, cfg.prootDeb, listener, "proot-dl", 0.05f, 0.12f)
            download(paths.downloads, cfg.tallocDeb, listener, "proot-dl", 0.12f, 0.14f)
            download(paths.downloads, cfg.shmemDeb, listener, "proot-dl", 0.14f, 0.16f)

            checkCancel()
            stage(listener, "proot-x", 0.16f, "Installing PRoot…")
            val stageDir = File(paths.stage, "debs").apply { deleteRecursively(); mkdirs() }
            // Re-verify right before extracting: catches bitrot, kills
            // mid-write and stale caches from older builds.
            val prootVerified = verifyCache(cfg.prootDeb)
            val tallocVerified = verifyCache(cfg.tallocDeb)
            val shmemVerified = verifyCache(cfg.shmemDeb)
            runExtract("Extract ${prootVerified.name}", prootVerified) {
                ArchiveExtractor.extractDeb(prootVerified, File(stageDir, "proot"))
            }
            runExtract("Extract ${tallocVerified.name}", tallocVerified) {
                ArchiveExtractor.extractDeb(tallocVerified, File(stageDir, "talloc"))
            }
            runExtract("Extract ${shmemVerified.name}", shmemVerified) {
                ArchiveExtractor.extractDeb(shmemVerified, File(stageDir, "shmem"))
            }
            installProotFromStage(stageDir)
            val machine = ArchiveExtractor.elfMachine(paths.prootBin)
            val expected = ArchSupport.expectedElf(arch)
            if (machine != expected) {
                throw InstallFail(
                    EliError(
                        operation = "Validate PRoot",
                        message = "ELF machine=${machine ?: "unknown"} (expected $expected/${arch}).",
                        probableCause = "Wrong-architecture PRoot binary.",
                        suggestedFix = "Delete runtime/downloads and retry (arch=$arch)."
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
            download(paths.downloads, cfg.rootfs, listener, "rootfs-dl", 0.22f, 0.6f)

            checkCancel()
            stage(listener, "rootfs-x", 0.6f, "Extracting Ubuntu (this takes a while)…")
            val tgzVerified = verifyCache(cfg.rootfs)
            checkFreeSpaceForExtract()
            runExtract("Extract ${tgzVerified.name}", tgzVerified) {
                paths.rootfs.deleteRecursively()
                paths.rootfs.mkdirs()
                ArchiveExtractor.extractRootfsTarGz(tgzVerified, paths.rootfs) { p ->
                    val frac = 0.6f + 0.25f * (p.entries / 4000f).coerceIn(0f, 1f)
                    stage(listener, "rootfs-x", frac, "Extracting Ubuntu… ${p.entries} entries")
                }
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
                    message = "${t.javaClass.simpleName}: ${t.message}",
                    suggestedFix = "Check storage/network, then retry. Partial downloads resume automatically."
                )
            )
        } finally {
            busy.set(false)
        }
    }

    /** Re-extracts cached archives + reconfigures, without re-downloading. */
    fun repair(listener: Listener) {
        if (!busy.compareAndSet(false, true)) {
            listener.onError(
                EliError(
                    "Repair runtime",
                    message = "An install or repair is already running.",
                    suggestedFix = "Wait for it to finish, or restart the app if it is stuck."
                )
            )
            return
        }
        cancelled.set(false)
        try {
            val cfg = loadConfig()
            stage(listener, "repair", 0f, "Repairing runtime…")
            val stageDir = File(paths.stage, "debs").apply { deleteRecursively(); mkdirs() }
            // Repair used to re-extract blindly: a corrupt cache failed
            // here forever. Now bad caches are deleted with a clear
            // message pointing back to Install (which re-downloads).
            val prootVerified = verifyCache(cfg.prootDeb)
            val tallocVerified = verifyCache(cfg.tallocDeb)
            val shmemVerified = verifyCache(cfg.shmemDeb)
            runExtract("Extract ${prootVerified.name}", prootVerified) {
                ArchiveExtractor.extractDeb(prootVerified, File(stageDir, "proot"))
            }
            runExtract("Extract ${tallocVerified.name}", tallocVerified) {
                ArchiveExtractor.extractDeb(tallocVerified, File(stageDir, "talloc"))
            }
            runExtract("Extract ${shmemVerified.name}", shmemVerified) {
                ArchiveExtractor.extractDeb(shmemVerified, File(stageDir, "shmem"))
            }
            installProotFromStage(stageDir)
            paths.prootBin.setExecutable(true, false)
            val tgzFile = File(paths.downloads, cfg.rootfs.fileName)
            if (tgzFile.isFile) {
                val tgzVerified = verifyCache(cfg.rootfs)
                runExtract("Extract ${tgzVerified.name}", tgzVerified) {
                    paths.rootfs.deleteRecursively()
                    paths.rootfs.mkdirs()
                    ArchiveExtractor.extractRootfsTarGz(tgzVerified, paths.rootfs) { p ->
                        stage(listener, "repair", (p.entries / 4000f).coerceIn(0f, 0.8f), "Re-extracting… ${p.entries}")
                    }
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
        } finally {
            busy.set(false)
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

    /**
     * Re-verifies a cached archive right before extraction (bitrot,
     * kills mid-write, stale caches from older builds). A bad cache is
     * deleted so the next Install re-downloads it cleanly.
     */
    private fun verifyCache(pkg: Package): File {
        val f = File(paths.downloads, pkg.fileName)
        if (!f.isFile) {
            throw InstallFail(
                EliError(
                    "Verify ${pkg.fileName}",
                    message = "Cached file missing.",
                    suggestedFix = "Run full Install (downloads resume automatically)."
                )
            )
        }
        if (pkg.sha256.isNotBlank() && !Net.sha256(f).equals(pkg.sha256, ignoreCase = true)) {
            f.delete()
            File(paths.downloads, pkg.fileName + ".part").delete()
            throw InstallFail(
                EliError(
                    "Verify ${pkg.fileName}",
                    message = "Cached file failed SHA-256 (truncated or corrupt) — deleted.",
                    suggestedFix = "Run Install again to re-download it."
                )
            )
        }
        return f
    }

    /** Runs an extraction with file context so archive failures pinpoint the file. */
    private inline fun runExtract(op: String, file: File, fn: () -> Unit) {
        try {
            fn()
        } catch (t: InterruptedException) {
            throw t
        } catch (t: Throwable) {
            throw InstallFail(
                EliError(
                    op,
                    message = "${file.name} (${file.length() / 1_000_000}MB on disk): " +
                        "${t.javaClass.simpleName}: ${t.message}",
                    probableCause = "The archive is truncated or corrupt.",
                    suggestedFix = "Config → Runtime → 'Clear downloads', then Install again."
                )
            )
        }
    }

    /** Ubuntu needs ~500MB scratch beyond the download; fail fast, not mid-tar. */
    private fun checkFreeSpaceForExtract() {
        val free = freeBytes()
        if (free < 500_000_000L) {
            throw InstallFail(
                EliError(
                    "Check free space",
                    message = "Only ${free / 1_000_000}MB free before Ubuntu extraction.",
                    probableCause = "Extraction needs ~500MB scratch beyond the download.",
                    suggestedFix = "Free up space and retry (Repair reuses downloads)."
                )
            )
        }
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
