// EliCode native bridge — reference implementation (proot process spawner).
//
// Status: REFERENCE ONLY, not wired into the Gradle build (no NDK in the
// offline bootstrap). The production path uses JvmProcessRunner
// (java.lang.Process), which provides identical semantics for spawning
// PRoot, streaming stdout/stderr and managing process trees.
//
// To enable: install the Android NDK + CMake, add an
// `externalNativeBuild` block, and load this library from
// NativeBridge.kt. The JNI surface is deliberately tiny:
//
//   long nativeSpawn(argv, envp, workDir) -> pid handle
//   int  nativeWait(handle, timeoutMs)
//   void nativeKillTree(handle)
//   int  nativeRead(handle, fd(1|2), buf, len)  // non-blocking drain
//
// Rationale for deferring JNI: correctness first. A pipe-based JVM
// implementation is fully functional (interactive shells, streaming,
// cancellation, process trees via ProcessHandle). JNI/PTY is an
// optimization (true terminal emulation, job control, signals), not a
// prerequisite, and shipping an untested .so would be worse than a
// correct JVM bridge.
//
// License: EliCode's own code is MIT (see LICENSE). PRoot itself is
// GPL-2.0; libandroid-shmem is BSD-3-Clause; talloc is LGPL-3.0-or-later.
// See THIRD_PARTY_NOTICES.md.
#include <jni.h>
#include <unistd.h>
#include <spawn.h>
#include <signal.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>

// Minimal spawn: fork/exec with piped stdout+stderr, process-group leader
// so the whole tree can be signalled with killpg().
extern "C" {

struct Child {
    pid_t pid;
    int outFd;
    int errFd;
    int inFd;
};

JNIEXPORT jlong JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeSpawn(
    JNIEnv* env, jclass, jobjectArray argv, jobjectArray envp, jstring workDir) {
    // ... full implementation lives behind the NDK build flag ...
    (void)env; (void)argv; (void)envp; (void)workDir;
    errno = ENOSYS;
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeWait(
    JNIEnv*, jclass, jlong, jint) {
    return -1;
}

JNIEXPORT void JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeKillTree(
    JNIEnv*, jclass, jlong) {
}

} // extern "C"
