package com.elicode.app.git

import com.elicode.app.core.EliError
import com.elicode.app.core.EliResult
import com.elicode.app.runtime.GitShell
import com.elicode.app.runtime.ProcResult
import java.io.File

/**
 * Real Git operations. Prefers a host `git` binary when present, otherwise
 * runs `git` inside the Ubuntu runtime. Every failure is a structured
 * [EliError] with cause + fix (e.g. missing identity, diverged branch).
 *
 * Tokens are passed in-memory only and redacted from all logs/errors.
 */
class GitEngine(private val runtime: GitShell) {

    /** Where git runs: host binary if available, else guest. */
    private fun backend(): Backend {
        val host = runtime.execOnHost("command -v git && git --version", null, 10_000L)
        val ok = host as? EliResult.Ok
        return if (ok != null && ok.value.exitCode == 0) Backend.HOST else Backend.GUEST
    }

    private enum class Backend { HOST, GUEST }

    private fun git(
        dir: File, vararg args: String, timeoutMs: Long = 120_000L, token: String? = null
    ): EliResult<ProcResult> {
        val display = ("git " + args.joinToString(" ")).let { sanitize(it) }
        val fullArgs = args.toList()
        return when (backend()) {
            Backend.HOST -> {
                val env = mapOf("GIT_TERMINAL_PROMPT" to "0")
                val r = runtime.execOnHost(
                    "git " + fullArgs.joinToString(" ") { shellQ(it) },
                    dir, timeoutMs
                )
                mapGitResult(r, display, dir, token != null)
            }
            Backend.GUEST -> {
                if (!runtime.isInstalled()) {
                    return EliResult.Err(
                        EliError("git ${args.firstOrNull() ?: ""}", display,
                            message = "Neither host git nor the Linux runtime is available.",
                            probableCause = "Android ships no git binary; EliCode runs git inside Ubuntu.",
                            suggestedFix = "Prepare the environment (Settings → Runtime), then retry.")
                    )
                }
                val r = runtime.execInRuntime(
                    "GIT_TERMINAL_PROMPT=0 git " + fullArgs.joinToString(" ") { shellQ(it) },
                    projectDirFor(dir), timeoutMs
                )
                mapGitResult(r, display, dir, token != null)
            }
        }.let { res ->
            // token never persisted; ensure redaction even if impl leaked it
            if (res is EliResult.Err && token != null) {
                EliResult.Err(res.error.copy(
                    command = sanitize(res.error.command),
                    message = sanitize(res.error.message)
                ))
            } else res
        }
    }

    /** Guest sees projects under /projects or /ext; git must run in the real dir. */
    private fun projectDirFor(dir: File): File = dir

    private fun mapGitResult(
        r: EliResult<ProcResult>, display: String, dir: File, usedToken: Boolean
    ): EliResult<ProcResult> {
        if (r is EliResult.Err) return r
        val v = (r as EliResult.Ok).value
        if (v.exitCode == 0) return r
        return EliResult.Err(interpret(display, v, dir, usedToken))
    }

    private fun interpret(cmd: String, v: ProcResult, dir: File, usedToken: Boolean): EliError {
        val raw = (v.stderr.ifBlank { v.stdout }).trim()
        var cause = "Git exited with code ${v.exitCode}."
        var fix = "Check the output above and retry."
        when {
            "not a git repository" in raw -> {
                cause = "${dir.name} is not a git repository."
                fix = "Use 'git init' or clone the repository first."
            }
            "Author identity unknown" in raw || "unable to auto-detect email" in raw -> {
                cause = "Git identity (user.name/user.email) is not configured."
                fix = "Set identity in the Git tab, then commit again."
            }
            "nothing to commit" in raw -> {
                cause = "No staged changes."
                fix = "Stage files with 'git add' first."
            }
            "failed to push" in raw || "rejected" in raw && "fetch first" in raw -> {
                cause = "Remote has commits you don't have (diverged)."
                fix = "Pull (rebase) first, resolve conflicts, then push."
            }
            "Authentication failed" in raw || "could not read Username" in raw || "Invalid username or password" in raw -> {
                cause = if (usedToken) "Token rejected by the server." else "No valid credentials for this remote."
                fix = "Connect your GitHub account (token) in the GitHub tab, then retry."
            }
            "Could not resolve host" in raw || "unable to access" in raw && "Could not resolve" in raw -> {
                cause = "No network / DNS from the runtime."
                fix = "Check connectivity and retry."
            }
            "Connection timed out" in raw -> {
                cause = "Network timeout reaching the remote."
                fix = "Retry; large clones may need Wi-Fi."
            }
            "CONFLICT" in raw || "Merge conflict" in raw -> {
                cause = "Merge/pull produced conflicts."
                fix = "Resolve conflict markers in the Editor, then commit."
            }
        }
        return EliError("git", cmd, v.exitCode, raw.take(3000), cause, fix)
    }

    // ---------------- public API ----------------

    fun isRepo(dir: File): Boolean = File(dir, ".git").exists()

    fun init(dir: File): EliResult<ProcResult> = git(dir, "init")

    fun status(dir: File): EliResult<ProcResult> =
        git(dir, "status", "--short", "--branch", timeoutMs = 30_000L)

    fun diff(dir: File, staged: Boolean = false): EliResult<ProcResult> =
        if (staged) git(dir, "diff", "--cached", timeoutMs = 30_000L)
        else git(dir, "diff", timeoutMs = 30_000L)

    fun add(dir: File, paths: List<String>): EliResult<ProcResult> =
        git(dir, "add", "--", *paths.toTypedArray())

    fun addAll(dir: File): EliResult<ProcResult> = git(dir, "add", "-A")

    fun commit(dir: File, message: String): EliResult<ProcResult> =
        git(dir, "commit", "-m", message)

    fun log(dir: File, limit: Int = 30): EliResult<ProcResult> =
        git(dir, "log", "--oneline", "--decorate", "-$limit", timeoutMs = 30_000L)

    fun branch(dir: File): EliResult<ProcResult> = git(dir, "branch", "--show-current", timeoutMs = 30_000L)

    fun branches(dir: File): EliResult<ProcResult> = git(dir, "branch", "-a", timeoutMs = 30_000L)

    fun checkout(dir: File, ref: String): EliResult<ProcResult> = git(dir, "checkout", ref)

    fun createBranch(dir: File, name: String): EliResult<ProcResult> = git(dir, "checkout", "-b", name)

    fun pull(dir: File): EliResult<ProcResult> = git(dir, "pull", "--rebase", timeoutMs = 300_000L)

    fun push(dir: File, setUpstream: Boolean = false): EliResult<ProcResult> {
        val branch = (branch(dir) as? EliResult.Ok)?.value?.stdout?.trim().orEmpty()
        return if (setUpstream && branch.isNotBlank()) {
            git(dir, "push", "-u", "origin", branch, timeoutMs = 300_000L)
        } else git(dir, "push", timeoutMs = 300_000L)
    }

    fun setIdentity(dir: File, name: String, email: String): EliResult<ProcResult> {
        val a = git(dir, "config", "user.name", name, timeoutMs = 15_000L)
        if (a is EliResult.Err) return a
        return git(dir, "config", "user.email", email, timeoutMs = 15_000L)
    }

    fun setRemote(dir: File, url: String): EliResult<ProcResult> {
        val existing = git(dir, "remote", timeoutMs = 15_000L)
        val hasOrigin = (existing as? EliResult.Ok)?.value?.stdout?.contains("origin") == true
        return if (hasOrigin) git(dir, "remote", "set-url", "origin", url, timeoutMs = 15_000L)
        else git(dir, "remote", "add", "origin", url, timeoutMs = 15_000L)
    }

    /**
     * Clones [url] into [parentDir]/[dirName]. When [token] is given and the
     * URL is https, it is injected in-memory for authentication.
     */
    fun clone(
        url: String, parentDir: File, dirName: String, token: String? = null,
        timeoutMs: Long = 600_000L
    ): EliResult<File> {
        val authed = injectToken(url, token)
        val args = listOf("clone", authed, dirName)
        val display = "git clone $url $dirName"
        val res: EliResult<ProcResult> = when (backend()) {
            Backend.HOST -> mapGitResult(
                runtime.execOnHost("git " + args.joinToString(" ") { shellQ(it) }, parentDir, timeoutMs),
                display, parentDir, token != null
            )
            Backend.GUEST -> {
                if (!runtime.isInstalled()) {
                    return EliResult.Err(
                        EliError("git clone", display, message = "Linux runtime is not installed.",
                            probableCause = "Android ships no git binary.",
                            suggestedFix = "Prepare the environment first, then clone again.")
                    )
                }
                mapGitResult(
                    runtime.execInRuntime(
                        "GIT_TERMINAL_PROMPT=0 git " + args.joinToString(" ") { shellQ(it) },
                        parentDir, timeoutMs
                    ),
                    display, parentDir, token != null
                )
            }
        }
        return when (res) {
            is EliResult.Ok -> EliResult.Ok(File(parentDir, dirName))
            is EliResult.Err -> EliResult.Err(
                if (token != null) res.error.copy(
                    command = sanitize(res.error.command),
                    message = sanitize(res.error.message)
                ) else res.error
            )
        }
    }

    // ---------------- helpers ----------------

    private fun shellQ(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    fun injectToken(url: String, token: String?): String {
        if (token.isNullOrBlank()) return url
        if (!url.startsWith("https://")) return url
        if ("@" in url.substringAfter("https://").substringBefore("/")) return url
        return url.replace("https://", "https://x-access-token:$token@")
    }

    fun sanitize(text: String): String =
        text.replace(Regex("https://[^/@\\s]+@"), "https://***@")
            .replace(Regex("(?i)(token|password|secret)\\s*[:=]\\s*\\S+"), "$1=***")
}
