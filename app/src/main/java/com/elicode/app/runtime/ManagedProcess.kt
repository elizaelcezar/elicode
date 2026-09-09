package com.elicode.app.runtime

import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [EliProcess] backed by [java.lang.Process] with dedicated pump threads
 * for stdout/stderr (streaming, no deadlocks on large output).
 */
class ManagedProcess(
    override val label: String,
    private val proc: Process,
    private val listener: ProcessListener?
) : EliProcess {

    private val outBuf = StringBuffer()
    private val errBuf = StringBuffer()
    private val exited = AtomicBoolean(false)
    @Volatile private var exitCode: Int = -1
    private val stdin: OutputStream = proc.outputStream

    init {
        pump(proc.inputStream, Stream.STDOUT, outBuf)
        pump(proc.errorStream, Stream.STDERR, errBuf)
        Thread({
            try {
                val code = proc.waitFor()
                exitCode = code
                exited.set(true)
                runCatching { listener?.onExit(code) }
            } catch (t: Throwable) {
                if (exited.compareAndSet(false, true)) {
                    runCatching { listener?.onExit(-1) }
                }
            }
        }, "elic-wait-$label").apply { isDaemon = true }.start()
    }

    private fun pump(stream: java.io.InputStream, which: Stream, buf: StringBuffer) {
        Thread({
            val tmp = ByteArray(8192)
            try {
                while (true) {
                    val n = stream.read(tmp)
                    if (n < 0) break
                    val text = String(tmp, 0, n, Charsets.UTF_8)
                    synchronized(buf) { buf.append(text) }
                    runCatching { listener?.onOutput(which, text) }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { stream.close() }
            }
        }, "elic-pump-$label-$which").apply { isDaemon = true }.start()
    }

    /**
     * PID via reflection: `Process.pid()` is API 26+ and some compile-time
     * android.jar views hide it; minSdk 28 guarantees it at runtime.
     */
    override val pid: Long get() = runCatching {
        proc.javaClass.getMethod("pid").invoke(proc) as Long
    }.getOrDefault(-1L)
    override val isAlive: Boolean get() = !exited.get() && runCatching { proc.isAlive }.getOrDefault(false)

    @Synchronized
    override fun writeStdin(text: String) {
        runCatching {
            stdin.write(text.toByteArray(Charsets.UTF_8))
            stdin.flush()
        }
    }

    override fun closeStdin() {
        runCatching { stdin.close() }
    }

    override fun kill() {
        runCatching {
            // Kill the whole subtree (shell children like gradle/node servers).
            killTreeReflectively()
            proc.destroy()
            Thread {
                Thread.sleep(1500)
                runCatching { if (proc.isAlive) proc.destroyForcibly() }
            }.apply { isDaemon = true }.start()
        }
    }

    override fun waitFor(): Int {
        return try {
            proc.waitFor()
        } catch (_: InterruptedException) {
            -1
        }
    }

    override fun snapshot(): ProcResult = ProcResult(
        exitCode = if (exited.get()) exitCode else -1,
        stdout = synchronized(outBuf) { outBuf.toString() },
        stderr = synchronized(errBuf) { errBuf.toString() }
    )

    /**
     * `ProcessHandle.descendants()` via reflection (API 26+, same rationale
     * as [pid]). Best effort: host shell children die with destroy() anyway.
     */
    private fun killTreeReflectively() {
        runCatching {
            val handle = proc.javaClass.getMethod("toHandle").invoke(proc)
            val descendants = handle.javaClass.getMethod("descendants").invoke(handle)
            val forEach = descendants.javaClass.getMethod(
                "forEach", java.util.function.Consumer::class.java
            )
            forEach.invoke(descendants, java.util.function.Consumer<Any> { h ->
                runCatching {
                    h.javaClass.getMethod("destroy").invoke(h)
                }
            })
        }
    }
}

/** Default [ProcessRunner] using [ProcessBuilder]. */
class JvmProcessRunner : ProcessRunner {
    override fun start(
        cmd: List<String>,
        cwd: File?,
        env: Map<String, String>,
        label: String,
        listener: ProcessListener?
    ): EliProcess {
        val pb = ProcessBuilder(cmd)
        if (cwd != null) pb.directory(cwd)
        if (env.isNotEmpty()) pb.environment().putAll(env)
        // Keep Android linker warnings out of stderr noise.
        val proc = pb.start()
        return ManagedProcess(label, proc, listener)
    }
}

/** Registry of live processes: list them, kill them, keep servers alive. */
class ProcessRegistry {
    private val lock = Any()
    private val live = mutableMapOf<String, EliProcess>()
    private var seq = 0

    fun register(proc: EliProcess): String = synchronized(lock) {
        val id = "p${++seq}-${proc.label.replace(Regex("[^A-Za-z0-9]+"), "-").take(24)}"
        live[id] = proc
        // Prune dead entries opportunistically.
        live.entries.removeAll { !it.value.isAlive && it.key != id }
        id
    }

    fun list(): List<Pair<String, EliProcess>> = synchronized(lock) { live.toList() }

    fun get(id: String): EliProcess? = synchronized(lock) { live[id] }

    fun kill(id: String): Boolean {
        val p = synchronized(lock) { live.remove(id) } ?: return false
        p.kill()
        return true
    }

    fun killAll() {
        val all = synchronized(lock) {
            val c = live.values.toList()
            live.clear()
            c
        }
        all.forEach { runCatching { it.kill() } }
    }
}
