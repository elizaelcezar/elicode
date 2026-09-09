package com.elicode.app.runtime

import android.content.Context
import com.elicode.app.agent.OpenCodeEngine
import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-click setup: installs everything the app needs in order —
 * Linux runtime → Node.js → OpenCode → Android toolchain — with progress,
 * per-step status and cancel. Steps already satisfied are skipped, so
 * re-running is always safe.
 *
 * The toolchain scripts (`assets/bootstrap/setup-*.sh`) used to be
 * manual-only (nothing in the app executed them); the orchestrator stages
 * them into the guest `/tmp` (host `paths.tmp` bind) and runs them.
 */
class SetupOrchestrator(
    private val context: Context,
    private val runtime: RuntimeManager,
    private val agent: OpenCodeEngine,
    private val log: (category: String, tag: String, message: String) -> Unit = { _, _, _ -> }
) {
    enum class State { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    data class Step(
        val id: String,
        val label: String,
        var state: State = State.PENDING,
        var detail: String = ""
    )

    interface Listener {
        fun onUpdate(steps: List<Step>)
        fun onLog(text: String)
        fun onDone(allOk: Boolean)
    }

    companion object {
        const val RUNTIME = "runtime"
        const val NODE = "node"
        const val OPENCODE = "opencode"
        const val ANDROID = "android"

        /**
         * Guest shells (bash/dash) choke on CRLF: `set -euo pipefail`
         * becomes `pipefail\r`. Windows checkouts produce CRLF
         * assets, so staging normalizes line endings. Pure —
         * unit-tested.
         */
        fun normalizeLineEndings(text: String): String =
            text.replace("\r\n", "\n").replace("\r", "\n")

        fun defaultSteps(): List<Step> = listOf(
            Step(RUNTIME, "Linux (Ubuntu + PRoot)"),
            Step(NODE, "Node.js 20 LTS"),
            Step(OPENCODE, "OpenCode AI"),
            Step(ANDROID, "Android toolchain (JDK + SDK)")
        )

        /**
         * Pure planner: which steps can be skipped given the detected
         * state. Unit-tested (see SetupPlanTest).
         */
        fun planSteps(state: InstallState): List<Step> {
            val steps = defaultSteps()
            if (state.runtimeInstalled) steps[0].state = State.SKIPPED
            if (state.nodeVersion != null) steps[1].state = State.SKIPPED
            if (state.opencodeVersion != null) steps[2].state = State.SKIPPED
            if (state.androidReady) steps[3].state = State.SKIPPED
            return steps
        }
    }

    /** Everything the planner needs to know (probed on-device). */
    data class InstallState(
        val runtimeInstalled: Boolean = false,
        val nodeVersion: String? = null,
        val opencodeVersion: String? = null,
        val androidReady: Boolean = false
    )

    private val cancelled = AtomicBoolean(false)
    @Volatile private var running = false

    fun cancel() {
        cancelled.set(true)
    }

    fun isRunning(): Boolean = running

    fun probeState(): InstallState {
        if (!runtime.isInstalled()) return InstallState()
        val node = runtime.execInRuntime("command -v node && node --version", null, 30_000L)
        val nodeVer = (node as? EliResult.Ok)
            ?.takeIf { it.value.exitCode == 0 }
            ?.value?.stdout?.trim()?.lineSequence()?.lastOrNull()
        val agentStatus = runCatching { agent.status() }.getOrNull()
        val android = runtime.execInRuntime(
            "command -v java >/dev/null && command -v gradle >/dev/null && " +
                "ls -d \${ANDROID_HOME:-/opt/android-sdk}/platforms/android-3* >/dev/null 2>&1",
            null, 30_000L
        )
        val androidOk = (android as? EliResult.Ok)?.value?.exitCode == 0
        return InstallState(
            runtimeInstalled = true,
            nodeVersion = nodeVer,
            opencodeVersion = agentStatus?.opencodeVersion,
            androidReady = androidOk
        )
    }

    fun start(listener: Listener) {
        if (running) return
        running = true
        cancelled.set(false)
        Thread({
            try {
                run(listener)
            } finally {
                running = false
            }
        }, "elic-setup").apply { isDaemon = true }.start()
    }

    private fun run(listener: Listener) {
        listener.onLog("Probing installed components…")
        val probed = probeState()
        log("Setup", "probe", "runtime=${probed.runtimeInstalled} node=${probed.nodeVersion} " +
            "opencode=${probed.opencodeVersion} android=${probed.androidReady}")
        val steps = planSteps(probed).toMutableList()
        listener.onUpdate(steps.toList())

        for (step in steps) {
            if (cancelled.get()) {
                step.detail = "cancelled"
                log("Setup", step.id, "cancelled")
                listener.onUpdate(steps.toList())
                listener.onDone(false)
                return
            }
            if (step.state == State.SKIPPED) {
                listener.onLog("✓ ${step.label} — already installed, skipping.")
                continue
            }
            step.state = State.RUNNING
            log("Setup", step.id, "starting: ${step.label}")
            listener.onUpdate(steps.toList())
            val ok = when (step.id) {
                RUNTIME -> installRuntime(listener, step)
                NODE -> runAssetScript(listener, step, "bootstrap/setup-node.sh", 20 * 60_000L)
                OPENCODE -> installOpencode(listener, step)
                ANDROID -> runAssetScript(listener, step, "bootstrap/setup-android.sh", 60 * 60_000L)
                else -> false
            }
            step.state = if (ok) State.DONE else State.FAILED
            log("Setup", step.id, if (ok) "done" else "FAILED: ${step.detail.take(300)}")
            listener.onUpdate(steps.toList())
            if (!ok) {
                listener.onLog("✗ ${step.label} failed — fix and re-run (done steps are skipped).")
                listener.onDone(false)
                return
            }
            listener.onLog("✓ ${step.label} done.")
        }
        runtime.refreshStatus(probeTools = true)
        log("Setup", "done", "all steps finished")
        listener.onDone(true)
    }

    private fun installRuntime(listener: Listener, step: Step): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        runtime.install(object : RuntimeInstaller.Listener {
            override fun onStage(stage: String, fraction: Float, message: String) {
                step.detail = message.take(120)
                listener.onLog("… $message")
                // Re-publish is handled by the caller loop; keep it cheap.
            }

            override fun onDone() {
                ok = true
                latch.countDown()
            }

            override fun onError(error: EliError) {
                step.detail = error.message.take(160)
                listener.onLog("✗ ${error.format()}")
                latch.countDown()
            }
        })
        while (!latch.await(5, TimeUnit.SECONDS)) {
            if (cancelled.get()) {
                runtime.cancelInstall()
                step.detail = "cancelled"
                return false
            }
        }
        return ok && runtime.isInstalled()
    }

    private fun installOpencode(listener: Listener, step: Step): Boolean {
        listener.onLog("… npm install -g opencode-ai")
        return when (val res = runGuest("npm install -g opencode-ai && opencode version", 10 * 60_000L, listener)) {
            is EliResult.Ok -> {
                val versionOk = res.value.exitCode == 0
                if (versionOk) step.detail = res.value.stdout.trim().lineSequence().lastOrNull().orEmpty()
                versionOk
            }
            is EliResult.Err -> {
                step.detail = res.error.message.take(160)
                false
            }
        }
    }

    private fun runAssetScript(
        listener: Listener,
        step: Step,
        asset: String,
        timeoutMs: Long
    ): Boolean {
        val staged = try {
            stageAsset(asset)
        } catch (t: Throwable) {
            step.detail = "could not stage $asset: ${t.message}"
            return false
        }
        listener.onLog("… running $asset (large download, may take a while)")
        return when (val res = runGuest("bash /tmp/$staged", timeoutMs, listener)) {
            is EliResult.Ok -> {
                if (res.value.exitCode != 0) {
                    step.detail = "exit ${res.value.exitCode}: ${res.value.combined.takeLast(200)}"
                }
                res.value.exitCode == 0
            }
            is EliResult.Err -> {
                step.detail = res.error.message.take(160)
                false
            }
        }
    }

    /**
     * Copies a bootstrap asset into the guest-visible /tmp (host
     * `paths.tmp` bind) so PRoot bash can execute it.
     */
    internal fun stageAsset(asset: String): String {
        val name = "elicode-" + File(asset).name
        val dst = File(runtime.paths.tmp, name)
        context.assets.open(asset).use { input ->
            // Never stage CRLF into the guest (see normalizeLineEndings).
            val text = normalizeLineEndings(input.bufferedReader().readText())
            dst.writeText(text)
        }
        return name
    }

    /**
     * Runs a guest command as a tracked process so cancel/timeout kills
     * the whole tree (blocking exec would only die on timeout).
     */
    private fun runGuest(
        bashCmd: String,
        timeoutMs: Long,
        listener: Listener
    ): EliResult<ProcResult> {
        val out = StringBuilder()
        val err = StringBuilder()
        val procListener = object : ProcessListener {
            override fun onOutput(stream: Stream, text: String) {
                synchronized(out) {
                    if (stream == Stream.STDOUT) out.append(text) else err.append(text)
                }
                text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    listener.onLog("  $line".take(300))
                }
            }

            override fun onExit(code: Int) {}
        }
        val started = runtime.startGuestTracked(bashCmd, null, "setup-step", procListener)
        if (started is EliResult.Err) return started
        val (id, proc) = (started as EliResult.Ok).value
        val deadline = System.currentTimeMillis() + timeoutMs
        while (proc.isAlive && System.currentTimeMillis() < deadline) {
            if (cancelled.get()) {
                runtime.registry.kill(id)
                return EliResult.Err(EliError("Setup step", bashCmd.take(120), message = "Cancelled."))
            }
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                break
            }
        }
        if (proc.isAlive) {
            runtime.registry.kill(id)
            return EliResult.Err(
                EliError("Setup step", bashCmd.take(120), exitCode = 124,
                    message = "Timed out after ${timeoutMs / 60_000}min.")
            )
        }
        return EliResult.Ok(proc.snapshot())
    }
}
