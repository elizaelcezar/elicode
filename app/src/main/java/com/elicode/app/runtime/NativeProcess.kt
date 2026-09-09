package com.elicode.app.runtime

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [EliProcess] backed by `libelicode_bridge.so`.
 *
 * PTY mode: one master fd carries both streams (like a real terminal);
 * the guest sees a tty, so line editing, hidden password prompts, job
 * control and ^C (SIGINT to the process group) all work. Pipe mode keeps
 * stdout/stderr split for one-shot commands while still getting
 * process-group signals and kill-trees.
 */
class NativeManagedProcess(
    override val label: String,
    private val handle: Long,
    private val isPty: Boolean,
    private val listener: ProcessListener?
) : EliProcess {

    private val outBuf = StringBuffer()
    private val errBuf = StringBuffer()
    private val exited = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var exitCode: Int = -1

    init {
        pump(Stream.STDOUT, outBuf)
        if (!isPty) pump(Stream.STDERR, errBuf)
        Thread({
            try {
                var code = NativeBridge.nativeWait(handle, 500)
                while (code == NativeBridge.WAIT_RUNNING) {
                    code = NativeBridge.nativeWait(handle, 500)
                }
                exitCode = code
            } catch (_: Throwable) {
                exitCode = -1
            } finally {
                exited.set(true)
                closeHandle()
                runCatching { listener?.onExit(exitCode) }
            }
        }, "elic-nwait-$label").apply { isDaemon = true }.start()
    }

    private fun pump(which: Stream, buf: StringBuffer) {
        val streamId = if (which == Stream.STDERR) 2 else 1
        Thread({
            try {
                while (!exited.get()) {
                    val chunk =
                        NativeBridge.nativeRead(handle, streamId, 8192, 250)
                            ?: break // EOF / fd gone
                    if (chunk.isEmpty()) continue // poll timeout: keep going
                    val text = String(chunk, Charsets.UTF_8)
                    synchronized(buf) { buf.append(text) }
                    runCatching { listener?.onOutput(which, text) }
                }
            } catch (_: Throwable) {
            }
        }, "elic-npump-$label-$which").apply { isDaemon = true }.start()
    }

    override val pid: Long get() =
        runCatching { NativeBridge.nativePid(handle).toLong() }.getOrDefault(-1L)

    override val isAlive: Boolean get() = !exited.get()

    @Synchronized
    override fun writeStdin(text: String) {
        runCatching {
            NativeBridge.nativeWrite(handle, text.toByteArray(Charsets.UTF_8))
        }
    }

    override fun closeStdin() {
        runCatching { NativeBridge.nativeCloseStdin(handle) }
    }

    override fun interrupt() {
        val sent = runCatching { NativeBridge.nativeInterrupt(handle) }.getOrDefault(false)
        if (!sent) {
            // Fallback: ETX through the tty line discipline (becomes SIGINT).
            runCatching { NativeBridge.nativeWrite(handle, byteArrayOf(0x03)) }
        }
    }

    override fun kill() {
        Thread({
            runCatching { NativeBridge.nativeKillTree(handle) }
        }, "elic-nkill-$label").apply { isDaemon = true }.start()
    }

    override fun waitFor(): Int {
        return try {
            var code = NativeBridge.nativeWait(handle, 1000)
            while (code == NativeBridge.WAIT_RUNNING) {
                code = NativeBridge.nativeWait(handle, 1000)
            }
            code
        } catch (_: InterruptedException) {
            -1
        } catch (_: Throwable) {
            -1
        }
    }

    override fun snapshot(): ProcResult = ProcResult(
        exitCode = if (exited.get()) exitCode else -1,
        stdout = synchronized(outBuf) { outBuf.toString() },
        stderr = synchronized(errBuf) { errBuf.toString() }
    )

    private fun closeHandle() {
        if (closed.compareAndSet(false, true)) {
            runCatching { NativeBridge.nativeClose(handle) }
        }
    }
}

/** [ProcessRunner] using the JNI bridge (requires [NativeBridge.AVAILABLE]). */
class NativeProcessRunner(
    private val usePty: Boolean = false,
    private val ptyCols: Int = 80,
    private val ptyRows: Int = 24
) : ProcessRunner {
    override fun start(
        cmd: List<String>,
        cwd: File?,
        env: Map<String, String>,
        label: String,
        listener: ProcessListener?
    ): EliProcess {
        if (!NativeBridge.AVAILABLE) {
            throw IllegalStateException("Native bridge library is not loaded.")
        }
        val handle = if (usePty) {
            NativeBridge.nativeSpawnPty(
                cmd.toTypedArray(),
                mergedEnv(env),
                cwd?.absolutePath.orEmpty(),
                ptyCols, ptyRows
            )
        } else {
            NativeBridge.nativeSpawnPipe(
                cmd.toTypedArray(),
                mergedEnv(env),
                cwd?.absolutePath.orEmpty()
            )
        }
        if (handle == NativeBridge.INVALID_HANDLE) {
            throw java.io.IOException("Native spawn failed for: ${cmd.firstOrNull()}")
        }
        return NativeManagedProcess(label, handle, usePty, listener)
    }

    companion object {
        /**
         * Full child environment: parent env merged with [extra] winning.
         * Pure function so unit tests can pin the merge semantics.
         */
        fun mergedEnv(extra: Map<String, String>): Array<String> {
            val merged = HashMap(System.getenv())
            merged.putAll(extra)
            return merged.map { (k, v) -> "$k=$v" }.toTypedArray()
        }
    }
}
