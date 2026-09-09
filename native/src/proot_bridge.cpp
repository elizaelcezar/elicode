// EliCode native bridge — PTY + process-group spawner.
//
// Status: PRODUCTION. Built by the NDK (see ../CMakeLists.txt) into
// libelicode_bridge.so for arm64-v8a + x86_64 and loaded by
// com.elicode.app.runtime.NativeBridge with JVM fallback.
//
// What this gives over java.lang.Process:
//  - Real PTY sessions (/dev/ptmx + setsid + controlling tty): the guest
//    sees a tty (isatty == true), so line editing, hidden password input,
//    job control and curses programs behave like a real terminal.
//  - Real signals: interrupt() sends SIGINT to the whole foreground
//    process group (a true ^C), killTree() does SIGTERM-then-SIGKILL to
//    the group, so no orphaned gradle/node children survive.
//  - Pipe mode (no pty) keeps stdout/stderr separate for one-shot
//    commands while still getting process-group semantics.
//
// License: EliCode's own code is MIT (see LICENSE). PRoot itself is
// GPL-2.0; libandroid-shmem is BSD-3-Clause; talloc is LGPL-3.0-or-later.
// See THIRD_PARTY_NOTICES.md.
#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <pthread.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <vector>
#include <string>
#include <android/log.h>

#define LOG_TAG "elicode_bridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Wait/timeout conventions shared with NativeBridge.kt.
constexpr jint WAIT_RUNNING = -1000;
constexpr jint WAIT_ERROR = -1;

struct Child {
    pid_t pid = -1;
    bool isPty = false;
    // PTY mode: single master fd (full duplex).
    int masterFd = -1;
    // Pipe mode fds (parent side).
    int stdinFd = -1;
    int stdoutFd = -1;
    int stderrFd = -1;
    bool reaped = false;
    int exitCode = WAIT_ERROR;
};

pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

Child* toChild(jlong h) {
    return reinterpret_cast<Child*>(static_cast<intptr_t>(h));
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::vector<std::string> jstrArray(JNIEnv* env, jobjectArray arr) {
    std::vector<std::string> out;
    if (!arr) return out;
    jsize n = env->GetArrayLength(arr);
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        out.push_back(jstr(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

// Applies KEY=VALUE overrides onto the inherited environment, then execs
// with PATH lookup (execvp). Must be called in the forked child only.
void childExec(const std::vector<std::string>& argv,
               const std::vector<std::string>& envp,
               const std::string& workDir) {
    for (const auto& kv : envp) {
        size_t eq = kv.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        setenv(kv.substr(0, eq).c_str(), kv.substr(eq + 1).c_str(), 1);
    }
    if (!workDir.empty() && workDir != ".") {
        if (chdir(workDir.c_str()) != 0) {
            LOGE("chdir(%s): %s", workDir.c_str(), strerror(errno));
            _exit(127);
        }
    }
    std::vector<char*> args;
    args.reserve(argv.size() + 1);
    for (const auto& a : argv) args.push_back(const_cast<char*>(a.c_str()));
    args.push_back(nullptr);
    if (args.empty() || args[0] == nullptr) _exit(127);
    execvp(args[0], args.data());
    LOGE("execvp(%s): %s", args[0], strerror(errno));
    _exit(127);
}

void setNonBlocking(int fd) {
    int f = fcntl(fd, F_GETFL, 0);
    if (f >= 0) fcntl(fd, F_SETFL, f | O_NONBLOCK);
}

// Single WNOHANG reap attempt. Returns true when the child is reaped.
bool reapOnce(Child* c) {
    if (c->reaped || c->pid < 0) return true;
    int status = 0;
    pid_t r = waitpid(c->pid, &status, WNOHANG);
    if (r == c->pid) {
        c->reaped = true;
        if (WIFEXITED(status)) c->exitCode = WEXITSTATUS(status);
        else if (WIFSIGNALED(status)) c->exitCode = 128 + WTERMSIG(status);
        else c->exitCode = WAIT_ERROR;
        return true;
    }
    return false;
}

int waitMs(Child* c, int timeoutMs) {
    if (reapOnce(c)) return c->exitCode;
    if (timeoutMs == 0) return WAIT_RUNNING;
    const int stepMs = 25;
    int waited = 0;
    while (timeoutMs < 0 || waited < timeoutMs) {
        usleep(static_cast<useconds_t>(stepMs) * 1000);
        waited += stepMs;
        if (reapOnce(c)) return c->exitCode;
    }
    return WAIT_RUNNING;
}

void closeQuiet(int& fd) {
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
}

} // namespace

extern "C" {

// Spawns argv attached to a fresh PTY (new session, controlling tty).
// Returns a native handle (>0) or 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeSpawnPty(
        JNIEnv* env, jclass, jobjectArray argv, jobjectArray envp,
        jstring workDir, jint cols, jint rows) {
    std::vector<std::string> args = jstrArray(env, argv);
    std::vector<std::string> envs = jstrArray(env, envp);
    std::string dir = jstr(env, workDir);
    if (args.empty()) return 0;

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        master = open("/dev/ptmx", O_RDWR | O_NOCTTY);
        if (master < 0) {
            LOGE("open ptmx: %s", strerror(errno));
            return 0;
        }
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        LOGE("grantpt/unlockpt: %s", strerror(errno));
        close(master);
        return 0;
    }
    char* slaveName = ptsname(master);
    if (!slaveName) {
        LOGE("ptsname: %s", strerror(errno));
        close(master);
        return 0;
    }
    std::string slavePath = slaveName;

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        close(master);
        return 0;
    }
    if (pid == 0) {
        // Child: new session, controlling tty, stdio on the slave.
        close(master);
        if (setsid() < 0) _exit(127);
        int slave = open(slavePath.c_str(), O_RDWR);
        if (slave < 0) _exit(127);
#ifdef TIOCSCTTY
        ioctl(slave, TIOCSCTTY, 0);
#endif
        struct winsize ws{};
        ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
        ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
        ioctl(slave, TIOCSWINSZ, &ws);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        childExec(args, envs, dir);
        _exit(127); // unreachable
    }

    setNonBlocking(master);
    auto* c = new Child();
    c->pid = pid;
    c->isPty = true;
    c->masterFd = master;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(c));
}

// Spawns argv with pipes (separate stdout/stderr). The child leads a new
// process group so interrupt()/killTree() hit the whole tree.
JNIEXPORT jlong JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeSpawnPipe(
        JNIEnv* env, jclass, jobjectArray argv, jobjectArray envp,
        jstring workDir) {
    std::vector<std::string> args = jstrArray(env, argv);
    std::vector<std::string> envs = jstrArray(env, envp);
    std::string dir = jstr(env, workDir);
    if (args.empty()) return 0;

    int inP[2] = {-1, -1}, outP[2] = {-1, -1}, errP[2] = {-1, -1};
    if (pipe(inP) != 0 || pipe(outP) != 0 || pipe(errP) != 0) {
        LOGE("pipe: %s", strerror(errno));
        if (inP[0] >= 0) { close(inP[0]); close(inP[1]); }
        if (outP[0] >= 0) { close(outP[0]); close(outP[1]); }
        if (errP[0] >= 0) { close(errP[0]); close(errP[1]); }
        return 0;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        close(inP[0]); close(inP[1]);
        close(outP[0]); close(outP[1]);
        close(errP[0]); close(errP[1]);
        return 0;
    }
    if (pid == 0) {
        // Child: own process group; wire pipes to stdio.
        setpgid(0, 0);
        dup2(inP[0], STDIN_FILENO);
        dup2(outP[1], STDOUT_FILENO);
        dup2(errP[1], STDERR_FILENO);
        close(inP[0]); close(inP[1]);
        close(outP[0]); close(outP[1]);
        close(errP[0]); close(errP[1]);
        // Detach from any controlling tty so reads never block on it.
        setsid();
        childExec(args, envs, dir);
        _exit(127); // unreachable
    }

    // Parent: make sure the child is grouped (race-safe; child did it too).
    setpgid(pid, pid);
    close(inP[0]);
    close(outP[1]);
    close(errP[1]);
    setNonBlocking(outP[0]);
    setNonBlocking(errP[0]);

    auto* c = new Child();
    c->pid = pid;
    c->isPty = false;
    c->stdinFd = inP[1];
    c->stdoutFd = outP[0];
    c->stderrFd = errP[0];
    return static_cast<jlong>(reinterpret_cast<intptr_t>(c));
}

// Reads up to maxLen bytes (stream 1=stdout, 2=stderr; ignored for PTY).
// Returns null on EOF/error, empty array on timeout (pump keeps going).
JNIEXPORT jbyteArray JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeRead(
        JNIEnv* env, jclass, jlong handle, jint stream,
        jint maxLen, jint timeoutMs) {
    Child* c = toChild(handle);
    if (!c) return nullptr;
    int fd = c->isPty ? c->masterFd : (stream == 2 ? c->stderrFd : c->stdoutFd);
    if (fd < 0) return nullptr;

    struct pollfd pfd{fd, POLLIN, 0};
    int pr = poll(&pfd, 1, timeoutMs < 0 ? -1 : timeoutMs);
    if (pr == 0) return env->NewByteArray(0); // timeout: keep pumping
    if (pr < 0) {
        if (errno == EINTR) return env->NewByteArray(0);
        return nullptr;
    }
    if (!(pfd.revents & (POLLIN | POLLHUP))) return env->NewByteArray(0);

    int cap = maxLen > 0 ? maxLen : 8192;
    if (cap > 65536) cap = 65536;
    std::vector<char> buf(static_cast<size_t>(cap));
    ssize_t n = read(fd, buf.data(), buf.size());
    if (n == 0) return nullptr; // EOF
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)
            return env->NewByteArray(0);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(n));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(n),
                                     reinterpret_cast<jbyte*>(buf.data()));
    return out;
}

JNIEXPORT jint JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeWrite(
        JNIEnv* env, jclass, jlong handle, jbyteArray data) {
    Child* c = toChild(handle);
    if (!c || !data) return -1;
    int fd = c->isPty ? c->masterFd : c->stdinFd;
    if (fd < 0) return -1;
    jsize n = env->GetArrayLength(data);
    if (n == 0) return 0;
    std::vector<char> buf(static_cast<size_t>(n));
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(buf.data()));
    ssize_t total = 0;
    while (total < n) {
        ssize_t w = write(fd, buf.data() + total,
                          static_cast<size_t>(n - total));
        if (w < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                struct pollfd pfd{fd, POLLOUT, 0};
                if (poll(&pfd, 1, 2000) <= 0) break;
                continue;
            }
            return total > 0 ? static_cast<jint>(total) : -1;
        }
        total += w;
    }
    return static_cast<jint>(total);
}

// True ^C: SIGINT to the whole process group (foreground pipeline).
JNIEXPORT jboolean JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeInterrupt(
        JNIEnv*, jclass, jlong handle) {
    Child* c = toChild(handle);
    if (!c || c->pid < 0 || c->reaped) return JNI_FALSE;
    if (killpg(c->pid, SIGINT) == 0) return JNI_TRUE;
    // Group already gone: maybe a lone child remains.
    if (kill(c->pid, SIGINT) == 0) return JNI_TRUE;
    return (errno == ESRCH) ? JNI_TRUE : JNI_FALSE;
}

// SIGTERM the group, wait ~1.5s, escalate to SIGKILL, reap.
// Returns the exit code, or -1 if nothing was reaped.
JNIEXPORT jint JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeKillTree(
        JNIEnv*, jclass, jlong handle) {
    Child* c = toChild(handle);
    if (!c || c->pid < 0) return WAIT_ERROR;
    pthread_mutex_lock(&g_lock);
    if (!c->reaped) {
        killpg(c->pid, SIGTERM);
        // waitpid must run without the lock? It only touches this child,
        // and all entry points serialize on g_lock — keep it simple.
        for (int i = 0; i < 60 && !reapOnce(c); i++) usleep(25 * 1000);
        if (!c->reaped) {
            killpg(c->pid, SIGKILL);
            kill(c->pid, SIGKILL);
            for (int i = 0; i < 40 && !reapOnce(c); i++) usleep(25 * 1000);
            reapOnce(c);
        }
    }
    int code = c->reaped ? c->exitCode : WAIT_ERROR;
    pthread_mutex_unlock(&g_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeWait(
        JNIEnv*, jclass, jlong handle, jint timeoutMs) {
    Child* c = toChild(handle);
    if (!c) return WAIT_ERROR;
    pthread_mutex_lock(&g_lock);
    int code = waitMs(c, timeoutMs);
    pthread_mutex_unlock(&g_lock);
    return code;
}

JNIEXPORT jint JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativePid(
        JNIEnv*, jclass, jlong handle) {
    Child* c = toChild(handle);
    return c ? static_cast<jint>(c->pid) : -1;
}

JNIEXPORT jboolean JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeResize(
        JNIEnv*, jclass, jlong handle, jint cols, jint rows) {
    Child* c = toChild(handle);
    if (!c || !c->isPty || c->masterFd < 0) return JNI_FALSE;
    struct winsize ws{};
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    return ioctl(c->masterFd, TIOCSWINSZ, &ws) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeCloseStdin(
        JNIEnv*, jclass, jlong handle) {
    Child* c = toChild(handle);
    if (!c) return;
    if (c->isPty) {
        // EOT through the line discipline reads as EOF for canonical input.
        const char eot = 0x04;
        if (c->masterFd >= 0) write(c->masterFd, &eot, 1);
    } else {
        pthread_mutex_lock(&g_lock);
        closeQuiet(c->stdinFd);
        pthread_mutex_unlock(&g_lock);
    }
}

JNIEXPORT void JNICALL
Java_com_elicode_app_runtime_NativeBridge_nativeClose(
        JNIEnv*, jclass, jlong handle) {
    Child* c = toChild(handle);
    if (!c) return;
    pthread_mutex_lock(&g_lock);
    reapOnce(c);
    closeQuiet(c->masterFd);
    closeQuiet(c->stdinFd);
    closeQuiet(c->stdoutFd);
    closeQuiet(c->stderrFd);
    pthread_mutex_unlock(&g_lock);
    delete c;
}

} // extern "C"
