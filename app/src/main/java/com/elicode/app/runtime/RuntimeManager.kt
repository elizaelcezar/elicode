package com.elicode.app.runtime

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Central runtime facade: host shell, guest (PRoot/Ubuntu) shell,
 * interactive sessions, install/validate/repair orchestration.
 */
class RuntimeManager(
    val context: Context,
    private val log: (category: String, tag: String, message: String) -> Unit = { _, _, _ -> }
) : GitShell {

    val paths = RuntimePaths(context)
    /** One-shots: split stdout/stderr (native pipe runner when available). */
    val runner: ProcessRunner = defaultProcessRunner(usePty = false)
    /** Interactive shells: real PTY (native when available). */
    val interactiveRunner: ProcessRunner = defaultProcessRunner(usePty = true)
    val registry = ProcessRegistry()
    val installer = RuntimeInstaller(context, paths, log)
    val validator = RuntimeValidator(context, paths)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Status(
        val arm64: Boolean = false,
        val supported: Boolean = false,
        val arch: String = "",
        val installed: Boolean = false,
        val version: Int = 0,
        val freeBytes: Long = 0L,
        val coreChecks: List<RuntimeValidator.Check> = emptyList(),
        val toolChecks: List<RuntimeValidator.Check> = emptyList(),
        val installing: Boolean = false,
        val installStage: String = "",
        val installFraction: Float = 0f,
        val installMessage: String = "",
        val lastError: EliError? = null
    )

    private val _status = MutableStateFlow(
        Status(
            arm64 = Build.SUPPORTED_ABIS?.contains("arm64-v8a") == true,
            supported = ArchSupport.isSupported(Build.SUPPORTED_ABIS?.toList().orEmpty()),
            arch = ArchSupport.selectArch(Build.SUPPORTED_ABIS?.toList().orEmpty())
        )
    )
    val status: StateFlow<Status> = _status

    fun isArm64(): Boolean = Build.SUPPORTED_ABIS?.contains("arm64-v8a") == true
    fun currentArch(): String = ArchSupport.selectArch(Build.SUPPORTED_ABIS?.toList().orEmpty())
    fun isSupported(): Boolean = currentArch().isNotEmpty()
    fun freeBytes(): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    override fun isInstalled(): Boolean {
        if (paths.installedVersion() <= 0) return false
        if (!paths.prootBin.isFile || !paths.prootBin.canExecute()) return false
        if (!File(paths.rootfs, "bin/bash").isFile &&
            !File(paths.rootfs, "usr/bin/bash").isFile
        ) return false
        return true
    }

    fun refreshStatus(probeTools: Boolean = false) {
        // Publish cheap facts immediately so the UI never flashes a stale
        // "device incompatible / 0MB free" state while validators run.
        _status.value = _status.value.copy(
            arm64 = isArm64(), supported = isSupported(), arch = currentArch(), freeBytes = freeBytes()
        )
        scope.launch {
            val core = runCatching { validator.validateCore() }.getOrDefault(emptyList())
            val coreOk = core.isNotEmpty() && core.all { !it.required || it.ok }
            val tools = if (probeTools && coreOk) {
                runCatching { validator.validateTools() }.getOrDefault(emptyList())
            } else _status.value.toolChecks
            _status.value = _status.value.copy(
                arm64 = isArm64(),
                supported = isSupported(),
                arch = currentArch(),
                installed = coreOk && paths.installedVersion() > 0,
                version = paths.installedVersion(),
                freeBytes = freeBytes(),
                coreChecks = core,
                toolChecks = tools
            )
        }
    }

    fun install(listener: RuntimeInstaller.Listener) {
        _status.value = _status.value.copy(installing = true, lastError = null)
        scope.launch {
            installer.install(object : RuntimeInstaller.Listener {
                override fun onStage(stage: String, fraction: Float, message: String) {
                    _status.value = _status.value.copy(
                        installStage = stage, installFraction = fraction, installMessage = message
                    )
                    listener.onStage(stage, fraction, message)
                }

                override fun onDone() {
                    _status.value = _status.value.copy(installing = false)
                    refreshStatus(probeTools = true)
                    listener.onDone()
                }

                override fun onError(error: EliError) {
                    _status.value = _status.value.copy(installing = false, lastError = error)
                    listener.onError(error)
                }
            })
        }
    }

    fun cancelInstall() {
        installer.cancelled.set(true)
    }

    fun repair(listener: RuntimeInstaller.Listener) {
        _status.value = _status.value.copy(installing = true, lastError = null)
        scope.launch {
            installer.repair(object : RuntimeInstaller.Listener {
                override fun onStage(stage: String, fraction: Float, message: String) {
                    _status.value = _status.value.copy(
                        installStage = stage, installFraction = fraction, installMessage = message
                    )
                    listener.onStage(stage, fraction, message)
                }

                override fun onDone() {
                    _status.value = _status.value.copy(installing = false)
                    refreshStatus(probeTools = true)
                    listener.onDone()
                }

                override fun onError(error: EliError) {
                    _status.value = _status.value.copy(installing = false, lastError = error)
                    listener.onError(error)
                }
            })
        }
    }

    /** True when a half-finished install left files worth repairing/wiping. */
    fun hasPartialInstall(): Boolean = validator.hasPartialInstall()

    fun wipeRuntime() {
        installer.wipeRuntime()
        refreshStatus(probeTools = false)
    }

    fun clearDownloads() {
        installer.clearDownloads()
    }

    // ---------------- execution ----------------

    /** Guest working dir for a project + extra binds when outside /projects. */
    fun guestWork(projectDir: File?): Pair<List<Pair<File, String>>, String> {
        if (projectDir == null) return emptyList<Pair<File, String>>() to "/projects"
        val root = paths.projects.canonicalPath
        val p = runCatching { projectDir.canonicalPath }.getOrDefault(projectDir.absolutePath)
        return if (p == root || p.startsWith("$root/")) {
            emptyList<Pair<File, String>>() to ProotLauncher.guestPathFor(paths.projects, projectDir)
        } else {
            projectDir.mkdirs()
            listOf(projectDir to "/ext") to "/ext"
        }
    }

    /** One-shot command inside Ubuntu. [bashCmd] runs under guest bash -c. */
    override fun execInRuntime(
        bashCmd: String,
        projectDir: File?,
        timeoutMs: Long
    ): EliResult<ProcResult> {
        if (!isInstalled()) {
            return EliResult.Err(
                EliError(
                    operation = "Run in Linux runtime",
                    command = bashCmd.take(300),
                    message = "Linux runtime is not installed.",
                    probableCause = "Ubuntu/PRoot bootstrap has not finished.",
                    suggestedFix = "Open Onboarding (or Settings → Runtime) and run 'Prepare environment'."
                )
            )
        }
        val (binds, work) = guestWork(projectDir)
        val launch = ProotLauncher.execLaunch(
            paths, listOf(bashCmd), work, binds, paths.useLinker(), paths.effectiveProot()
        )
        return Execs.run(runner, launch.argv, null, launch.env, "guest exec", timeoutMs)
    }

    /** One-shot command on the Android host shell (`sh -c`). Always available. */
    override fun execOnHost(
        bashCmd: String,
        cwd: File?,
        timeoutMs: Long
    ): EliResult<ProcResult> {
        return Execs.run(
            runner, listOf("sh", "-c", bashCmd), cwd,
            mapOf("TERM" to "xterm-256color"), "host exec", timeoutMs
        )
    }

    /**
     * Starts the pure interactive `opencode` TUI (like a desktop terminal)
     * on the PTY inside the project dir. Requires runtime + `opencode`
     * installed (Settings → one-click setup / AI tab → Install).
     */
    fun startOpencode(
        projectDir: File?,
        listener: ProcessListener?
    ): EliResult<Pair<String, EliProcess>> {
        if (!isInstalled()) {
            return EliResult.Err(
                EliError(
                    "Start opencode",
                    message = "Linux runtime is not installed.",
                    suggestedFix = "Settings → Install, then open this tab again."
                )
            )
        }
        val (binds, work) = guestWork(projectDir)
        val launch = ProotLauncher.tuiLaunch(
            paths, listOf("opencode"), work, binds, paths.useLinker(), paths.effectiveProot()
        )
        val proc = interactiveRunner.start(launch.argv, null, launch.env, "opencode", listener)
        return EliResult.Ok(registry.register(proc) to proc)
    }

    /**
     * Starts an interactive shell process (streaming). Uses guest bash when
     * installed, otherwise host `sh`. Returns registry id + process.
     */
    fun startShell(
        projectDir: File?,
        listener: ProcessListener?
    ): Pair<String, EliProcess> {
        val proc: EliProcess = if (isInstalled()) {
            val (binds, work) = guestWork(projectDir)
            val launch = ProotLauncher.shellLaunch(
                paths, work, binds, paths.useLinker(), paths.effectiveProot()
            )
            interactiveRunner.start(launch.argv, null, launch.env, "ubuntu-bash", listener)
        } else {
            interactiveRunner.start(
                listOf("sh"), projectDir,
                mapOf("TERM" to "xterm-256color", "PS1" to "elicode:$ "),
                "host-sh", listener
            )
        }
        return registry.register(proc) to proc
    }

    /** Starts a long-lived tracked process (dev server, build, agent). */
    fun startTracked(
        cmd: List<String>,
        cwd: File?,
        env: Map<String, String>,
        label: String,
        listener: ProcessListener?
    ): Pair<String, EliProcess> {
        val proc = runner.start(cmd, cwd, env, label, listener)
        return registry.register(proc) to proc
    }

    /** Starts a guest command as a tracked long-lived process. */
    fun startGuestTracked(
        bashCmd: String,
        projectDir: File?,
        label: String,
        listener: ProcessListener?
    ): EliResult<Pair<String, EliProcess>> {
        if (!isInstalled()) {
            return EliResult.Err(
                EliError("Start $label", bashCmd.take(300), message = "Linux runtime is not installed.",
                    suggestedFix = "Prepare the environment first (Settings → Runtime).")
            )
        }
        val (binds, work) = guestWork(projectDir)
        val launch = ProotLauncher.execLaunch(
            paths, listOf(bashCmd), work, binds, paths.useLinker(), paths.effectiveProot()
        )
        val proc = runner.start(launch.argv, null, launch.env, label, listener)
        return EliResult.Ok(registry.register(proc) to proc)
    }
}
