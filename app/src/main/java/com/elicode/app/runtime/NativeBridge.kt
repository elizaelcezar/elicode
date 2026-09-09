package com.elicode.app.runtime

/**
 * JNI bridge to `libelicode_bridge.so` (see `native/src/proot_bridge.cpp`).
 *
 * The library is built by the NDK for arm64-v8a + x86_64. On JVM unit
 * tests (or any device where the .so failed to load) [AVAILABLE] is false
 * and callers must use [JvmProcessRunner] instead — see
 * [defaultProcessRunner].
 */
object NativeBridge {
    const val LIB_NAME = "elicode_bridge"

    /** nativeWait() result while the child is still running. */
    const val WAIT_RUNNING = -1000

    /** Spawn/handle failure sentinel. */
    const val INVALID_HANDLE = 0L

    val AVAILABLE: Boolean = runCatching {
        System.loadLibrary(LIB_NAME)
        true
    }.getOrDefault(false)

    @JvmStatic
    external fun nativeSpawnPty(
        argv: Array<String>,
        envp: Array<String>,
        workDir: String,
        cols: Int,
        rows: Int
    ): Long

    @JvmStatic
    external fun nativeSpawnPipe(
        argv: Array<String>,
        envp: Array<String>,
        workDir: String
    ): Long

    /**
     * Reads up to [maxLen] bytes waiting at most [timeoutMs].
     * Returns null on EOF/error, an empty array on timeout.
     */
    @JvmStatic
    external fun nativeRead(
        handle: Long,
        stream: Int,
        maxLen: Int,
        timeoutMs: Int
    ): ByteArray?

    @JvmStatic
    external fun nativeWrite(handle: Long, data: ByteArray): Int

    /** True ^C: SIGINT to the whole process group. */
    @JvmStatic
    external fun nativeInterrupt(handle: Long): Boolean

    /** SIGTERM-then-SIGKILL to the group + reap. Returns the exit code. */
    @JvmStatic
    external fun nativeKillTree(handle: Long): Int

    /** Exit code, or [WAIT_RUNNING] when still alive after [timeoutMs]. */
    @JvmStatic
    external fun nativeWait(handle: Long, timeoutMs: Int): Int

    @JvmStatic
    external fun nativePid(handle: Long): Int

    @JvmStatic
    external fun nativeResize(handle: Long, cols: Int, rows: Int): Boolean

    @JvmStatic
    external fun nativeCloseStdin(handle: Long)

    @JvmStatic
    external fun nativeClose(handle: Long)
}

/**
 * Picks the best [ProcessRunner] for this device: native PTY/pipe runner
 * when `libelicode_bridge.so` loaded, JVM fallback otherwise (and always
 * on unit-test JVMs). [usePty] only matters for the native runner:
 * interactive shells want a PTY, one-shot commands want split streams.
 */
fun defaultProcessRunner(usePty: Boolean = false): ProcessRunner =
    if (NativeBridge.AVAILABLE) NativeProcessRunner(usePty) else JvmProcessRunner()
