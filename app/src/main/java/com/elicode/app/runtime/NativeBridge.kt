package com.elicode.app.runtime

/**
 * JNI bridge entry points (see `native/src/proot_bridge.cpp`).
 *
 * Currently UNUSED: the NDK is not part of the bootstrap toolchain, so the
 * production path is [JvmProcessRunner]. This object documents the exact
 * surface a future native implementation must provide — callers program
 * against [ProcessRunner], so swapping implementations is a one-line change.
 */
object NativeBridge {
    const val AVAILABLE = false

    @JvmStatic
    external fun nativeSpawn(argv: Array<String>, envp: Array<String>, workDir: String): Long

    @JvmStatic
    external fun nativeWait(handle: Long, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeKillTree(handle: Long)
}
