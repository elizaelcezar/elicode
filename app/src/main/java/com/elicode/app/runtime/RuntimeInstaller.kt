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
class RuntimeInstaller(
    private val context: Context,
    val paths: RuntimePaths,
    private val log: (category: String, tag: String, message: String) -> Unit = { _, _, _ -> }
) {

    /**
     * Self-heal policy: after a corrupt-cache or extraction failure the
     * installer re-downloads the package and retries exactly once, then
     * gives up with a clear error. Pure policy — unit-tested.
     */
    object RecoveryPolicy {
        fun shouldRedownload(failuresSoFar: Int): Boolean = failuresSoFar < 1
    }

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
        val l = logging(listener)
        if (!busy.compareAndSet(false, true)) {
            l.onError(
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

            // ---- PRoot + libs (self-healing: corrupt cache is
            // re-downloaded and retried once, automatically) ----
            stage(listener, "proot-dl", 0.05f, "Downloading PRoot…")
            val stageDir = File(paths.stage, "debs").apply { deleteRecursively(); mkdirs() }
            fetchExtractDeb(cfg.prootDeb, "proot", stageDir, listener, "proot-dl", 0.05f, 0.12f)
            fetchExtractDeb(cfg.tallocDeb, "talloc", stageDir, listener, "proot-dl", 0.12f, 0.14f)
            fetchExtractDeb(cfg.shmemDeb, "shmem", stageDir, listener, "proot-dl", 0.14f, 0.16f)
            checkCancel()
            stage(listener, "proot-x", 0.16f, "Installing PRoot…")
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
            testProot(listener, arch)

            // ---- Ubuntu rootfs ----
            checkCancel()
            stage(listener, "rootfs-dl", 0.22f, "Downloading Ubuntu ARM64…")
            checkFreeSpaceForExtract()
            fetchExtractRootfs(cfg.rootfs, listener)

            checkCancel()
            stage(listener, "configure", 0.87f, "Configuring Ubuntu…")
            configureRootfs()

            checkCancel()
            stage(listener, "verify", 0.9f, "Verifying runtime…")
            log(
                "Runtime", "verify",
                "PROOT_LOADER=${paths.effectiveLoader().absolutePath} " +
                    "(bundled=${paths.bundledLoader.isFile} tools=${paths.loaderBin.isFile})"
            )
            val report = RuntimeValidator(context, paths, log).validateCore()
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
            l.onError(e.error)
        } catch (e: InterruptedException) {
            l.onError(
                EliError("Install runtime", message = "Cancelled.", suggestedFix = "Retry to resume downloads.")
            )
        } catch (t: Throwable) {
            l.onError(
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
        val l = logging(listener)
        if (!busy.compareAndSet(false, true)) {
            l.onError(
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
            // Self-healing repair: missing/corrupt caches are re-downloaded
            // (once each) instead of failing blindly like before.
            val prootVerified = fetchVerified(cfg.prootDeb, listener, "repair")
            val tallocVerified = fetchVerified(cfg.tallocDeb, listener, "repair")
            val shmemVerified = fetchVerified(cfg.shmemDeb, listener, "repair")
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
            val arch = ArchSupport.selectArch(Build.SUPPORTED_ABIS?.toList().orEmpty())
            testProot(listener, arch)
            val tgzVerified = fetchVerified(cfg.rootfs, listener, "repair")
            runExtract("Extract ${tgzVerified.name}", tgzVerified) {
                paths.rootfs.deleteRecursively()
                paths.rootfs.mkdirs()
                ArchiveExtractor.extractRootfsTarGz(tgzVerified, paths.rootfs) { p ->
                    stage(listener, "repair", (p.entries / 4000f).coerceIn(0f, 0.8f), "Re-extracting… ${p.entries}")
                }
            }
            configureRootfs()
            log(
                "Runtime", "verify",
                "PROOT_LOADER=${paths.effectiveLoader().absolutePath} " +
                    "(bundled=${paths.bundledLoader.isFile} tools=${paths.loaderBin.isFile})"
            )
            val report = RuntimeValidator(context, paths, log).validateCore()
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
            l.onError(e.error)
        } catch (t: Throwable) {
            l.onError(EliError.unknown("Repair runtime", t))
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

    /**
     * Smoke-tests PRoot across invocation modes, cheapest first:
     * direct exec → system linker on filesDir binary → system linker on
     * the APK-bundled binary. Persists the winning mode. Every attempt's
     * error is kept, so a total failure still tells the full story.
     */
    private fun testProot(listener: Listener, arch: String): String {
        val bin = paths.prootBin.absolutePath
        val lib = paths.toolsLib.absolutePath
        val linker = ProotLauncher.systemLinker(arch.ifBlank { ArchSupport.ARM64 })
        val chmodOk = paths.prootBin.setExecutable(true, false)
        stage(
            listener, "proot-test", 0.2f,
            "Testing PRoot… (chmod +x ${if (chmodOk) "ok" else "FAILED"})"
        )
        fun probe(argv: List<String>, label: String): Pair<String?, String?> {
            val r = Execs.run(
                runner, argv, null,
                mapOf("LD_LIBRARY_PATH" to lib), label, 30_000L
            )
            val ok = r as? EliResult.Ok
            val out = ok?.value?.combined.orEmpty()
            return if (ok != null && ok.value.exitCode == 0 &&
                out.contains("proot", ignoreCase = true)
            ) {
                out to null
            } else {
                null to ((r as? EliResult.Err)?.error?.format() ?: out.ifBlank { "no output" })
            }
        }

        val (directOut, directErr) = probe(listOf(bin, "--version"), "proot --version")
        var filesOut: String? = null
        var filesErr: String? = "not tried"
        if (directOut == null) {
            stage(listener, "proot-test", 0.2f, "Direct exec denied — trying system linker…")
            val (o, e) = probe(
                listOf(linker.absolutePath, bin, "--version"),
                "proot --version (linker)"
            )
            filesOut = o
            filesErr = e
        }
        var soOut: String? = null
        var soErr: String? = "not tried"
        val so = paths.bundledProot
        if (directOut == null && filesOut == null && so.isFile) {
            stage(listener, "proot-test", 0.2f, "Linker denied too — trying APK-bundled proot…")
            val (o, e) = probe(
                listOf(linker.absolutePath, so.absolutePath, "--version"),
                "proot --version (bundled)"
            )
            soOut = o
            soErr = e
        }
        val mode = ProotLauncher.pickMode(directOut != null, filesOut != null, soOut != null)
        if (mode >= 0) {
            paths.writeProotMode(mode)
            if (mode > 0) {
                stage(
                    listener, "proot-test", 0.2f,
                    if (mode == 1) "Linker mode enabled for this device."
                    else "APK-bundled proot mode enabled for this device."
                )
            }
            return (listOfNotNull(directOut, filesOut, soOut).firstOrNull()).orEmpty()
        }
        throw InstallFail(
            EliError(
                operation = "Test PRoot",
                command = "$bin --version",
                message = "direct: ${(directErr ?: "?").take(400)}\n" +
                    "linker(filesDir): ${(filesErr ?: "?").take(400)}\n" +
                    "linker(bundled): ${(soErr ?: "?").take(400)}\n" +
                    execDiagnostics(),
                probableCause = "This device blocks executing PRoot (all 3 invocation modes failed).",
                suggestedFix = "Diagnostics → Copiar diagnóstico and share the log."
            )
        )
    }

    /** Gathers exec-denial evidence: permissions, SELinux, mount flags. */
    private fun execDiagnostics(): String = buildString {
        val f = paths.prootBin
        appendLine(
            "exec-diagnostics: exists=${f.isFile} size=${f.length()} " +
                "read=${f.canRead()} write=${f.canWrite()} exec=${f.canExecute()}"
        )
        val so = paths.bundledProot
        appendLine(
            "bundled-proot: dir=${so.parent} exists=${so.isFile} size=${so.length()}"
        )
        appendLine(
            "selinux-enforce: " + runCatching {
                File("/sys/fs/selinux/enforce").readText().trim()
            }.getOrDefault("?") + " (1=enforcing)"
        )
        appendLine(
            "selinux-ctx: " + runCatching {
                File("/proc/self/attr/current").readText().trim()
            }.getOrDefault("?")
        )
        appendLine(
            "mount: " + runCatching {
                val dir = context.filesDir.absolutePath
                File("/proc/mounts").readLines().mapNotNull { line ->
                    val parts = line.split(" ")
                    if (parts.size >= 4 && dir.startsWith(parts[1])) {
                        parts[1] + " " + parts[2] + " " + parts[3]
                    } else null
                }.maxByOrNull { it.length }
            }.getOrDefault("?")
        )
    }

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

    /**
     * Downloads + verifies + extracts one .deb, healing itself once: a
     * corrupt cache or a failed extraction deletes the file and retries
     * the whole fetch before surfacing an error.
     */
    private fun fetchExtractDeb(
        pkg: Package,
        subdir: String,
        stageDir: File,
        listener: Listener,
        stage: String,
        from: Float,
        to: Float
    ) {
        var failures = 0
        while (true) {
            checkCancel()
            val f = download(paths.downloads, pkg, listener, stage, from, to)
            try {
                verifyCache(pkg)
            } catch (e: InstallFail) {
                if (!RecoveryPolicy.shouldRedownload(failures++)) throw e
                onHeal(listener, pkg, "checksum", failures)
                continue
            }
            try {
                val dest = File(stageDir, subdir).apply { deleteRecursively(); mkdirs() }
                runExtract("Extract ${f.name}", f) {
                    ArchiveExtractor.extractDeb(f, dest)
                }
                return
            } catch (e: InstallFail) {
                if (!RecoveryPolicy.shouldRedownload(failures++)) throw e
                onHeal(listener, pkg, "extraction", failures)
                f.delete()
                File(paths.downloads, pkg.fileName + ".part").delete()
            }
        }
    }

    /** Same self-healing fetch for the Ubuntu rootfs tarball. */
    private fun fetchExtractRootfs(pkg: Package, listener: Listener) {
        var failures = 0
        while (true) {
            checkCancel()
            val f = download(paths.downloads, pkg, listener, "rootfs-dl", 0.22f, 0.6f)
            try {
                verifyCache(pkg)
            } catch (e: InstallFail) {
                if (!RecoveryPolicy.shouldRedownload(failures++)) throw e
                onHeal(listener, pkg, "checksum", failures)
                continue
            }
            try {
                runExtract("Extract ${f.name}", f) {
                    paths.rootfs.deleteRecursively()
                    paths.rootfs.mkdirs()
                    ArchiveExtractor.extractRootfsTarGz(f, paths.rootfs) { p ->
                        val frac = 0.6f + 0.25f * (p.entries / 4000f).coerceIn(0f, 1f)
                        stage(listener, "rootfs-x", frac, "Extracting Ubuntu… ${p.entries} entries")
                    }
                }
                return
            } catch (e: InstallFail) {
                if (!RecoveryPolicy.shouldRedownload(failures++)) throw e
                onHeal(listener, pkg, "extraction", failures)
                f.delete()
                File(paths.downloads, pkg.fileName + ".part").delete()
            }
        }
    }

    /**
     * Verifies a cached archive, re-downloading it (once) when missing
     * or corrupt. Used by Repair so it heals instead of failing blindly.
     */
    private fun fetchVerified(pkg: Package, listener: Listener, stage: String): File {
        var failures = 0
        while (true) {
            checkCancel()
            try {
                return verifyCache(pkg)
            } catch (e: InstallFail) {
                if (!RecoveryPolicy.shouldRedownload(failures++)) throw e
                onHeal(listener, pkg, "cache", failures)
                download(paths.downloads, pkg, listener, stage, 0f, 0.5f)
            }
        }
    }

    private fun onHeal(listener: Listener, pkg: Package, kind: String, attempt: Int) {
        stage(
            listener, "heal", 0f,
            "Self-heal: ${pkg.fileName} failed $kind — re-downloading (attempt $attempt)…"
        )
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
                    message = "${file.name} (${ArchiveExtractor.humanBytes(file.length())} on disk): " +
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
                    message = "Only ${free / 1_000_000}MB free before Ubuntu download + extraction.",
                    probableCause = "Download + extraction need ~500MB scratch.",
                    suggestedFix = "Free up space and retry (downloads already fetched are kept)."
                )
            )
        }
    }

    private fun stage(l: Listener, s: String, f: Float, m: String) {
        // Unified log: every stage lands in LogStore, so Diagnostics →
        // Copiar diagnóstico always carries the full story.
        log("Runtime", s, m.take(500))
        l.onStage(s, f, m)
    }

    /** Decorates a listener so final errors also land in the unified log. */
    private fun logging(base: Listener): Listener = object : Listener {
        override fun onStage(stage: String, fraction: Float, message: String) =
            base.onStage(stage, fraction, message)

        override fun onDone() = base.onDone()

        override fun onError(error: EliError) {
            log("Runtime", "error", error.format().take(2000))
            base.onError(error)
        }
    }

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
                    ArchiveExtractor.humanBytes(p.bytesDone) +
                        (if (p.bytesTotal > 0) " / ${ArchiveExtractor.humanBytes(p.bytesTotal)}" else "")
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
        // ELF loader helpers (PROOT_LOADER / PROOT_LOADER_32): the Termux
        // proot .deb ships libexec/proot/loader + loader32. Older EliCode
        // builds discarded them, so every guest exec failed with
        // execve("/usr/bin/bash") ENOENT ("the loader was not found")
        // even with a healthy rootfs. Fail loudly when absent.
        val loader = find("loader", File(stageDir, "proot"))
            ?: throw InstallFail(EliError("Install PRoot", message = "proot loader not found in .deb"))
        loader.copyTo(paths.loaderBin, overwrite = true)
        find("loader32", File(stageDir, "proot"))?.copyTo(paths.loader32Bin, overwrite = true)
        paths.prootBin.setExecutable(true, false)
        paths.loaderBin.setExecutable(true, false)
        if (paths.loader32Bin.isFile) paths.loader32Bin.setExecutable(true, false)
        // Shared libs: libtalloc.so*, libandroid-shmem.so
        stageDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".so") || ".so." in it.name) }
            .forEach { so -> so.copyTo(File(paths.toolsLib, so.name), overwrite = true) }
    }

    /**
     * Ubuntu-base ships an EMPTY etc/resolv.conf (0 bytes): a mere
     * existence check leaves the guest without DNS, so every apt
     * install fails with "no installation candidate". Pure —
     * unit-tested.
     */
    object DnsPolicy {
        fun needsWrite(resolv: File): Boolean =
            !resolv.isFile || resolv.length() == 0L
    }

    private fun configureRootfs() {
        // DNS inside Ubuntu.
        val resolv = File(paths.rootfs, "etc/resolv.conf")
        runCatching {
            resolv.parentFile?.mkdirs()
            if (DnsPolicy.needsWrite(resolv)) resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
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
