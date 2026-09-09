package com.elicode.app

import com.elicode.app.core.EliResult
import com.elicode.app.git.GitEngine
import com.elicode.app.runtime.ProcResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun prefersHostGitWhenPresent() {
        val shell = FakeShell(hostGitPresent = true)
        val git = GitEngine(shell)
        val dir = tmp.newFolder()
        val r = git.status(dir)
        assertTrue(r is EliResult.Ok)
        assertTrue(shell.hostCalls.any { it.contains("git") && it.contains("status") })
        assertTrue(shell.guestCalls.isEmpty())
    }

    @Test
    fun fallsBackToGuestGit() {
        val shell = FakeShell(hostGitPresent = false, installed = true)
        val git = GitEngine(shell)
        val dir = tmp.newFolder()
        val r = git.status(dir)
        assertTrue(r is EliResult.Ok)
        assertTrue(shell.guestCalls.any { it.contains("git") })
    }

    @Test
    fun errorsWithoutRuntimeOrHostGit() {
        val shell = FakeShell(hostGitPresent = false, installed = false)
        val git = GitEngine(shell)
        val r = git.status(tmp.newFolder())
        assertTrue(r is EliResult.Err)
        val err = (r as EliResult.Err).error
        assertTrue(err.suggestedFix.contains("Prepare", ignoreCase = true))
    }

    @Test
    fun interpretsMissingIdentity() {
        val shell = FakeShell(handler = {
            ProcResult(128, "", "Author identity unknown\nunable to auto-detect email address")
        })
        val git = GitEngine(shell)
        val r = git.commit(tmp.newFolder(), "msg")
        assertTrue(r is EliResult.Err)
        val err = (r as EliResult.Err).error
        assertTrue(err.probableCause.contains("identity", ignoreCase = true))
        assertTrue(err.suggestedFix.isNotBlank())
    }

    @Test
    fun interpretsNotARepo() {
        val shell = FakeShell(handler = {
            ProcResult(128, "", "fatal: not a git repository (or any of the parent directories)")
        })
        val git = GitEngine(shell)
        val r = git.log(tmp.newFolder())
        assertTrue(r is EliResult.Err)
        assertTrue((r as EliResult.Err).error.probableCause.contains("not a git repository"))
    }

    @Test
    fun cloneUsesTokenWithoutLeaking() {
        val seen = mutableListOf<String>()
        val shell = FakeShell(handler = { cmd ->
            seen += cmd
            // Simulate git writing the URL back in an error.
            ProcResult(128, "", "Authentication failed for '$cmd'")
        })
        val git = GitEngine(shell)
        val parent = tmp.newFolder()
        val r = git.clone("https://github.com/u/r.git", parent, "r", token = "SUPERSECRET")
        assertTrue(r is EliResult.Err)
        val err = (r as EliResult.Err).error
        assertTrue(!err.format().contains("SUPERSECRET"))
        // ...but the real command did carry the token (needed for auth).
        assertTrue(seen.any { it.contains("SUPERSECRET") })
    }

    @Test
    fun isRepoDetectsDotGit() {
        val shell = FakeShell()
        val git = GitEngine(shell)
        val dir = tmp.newFolder()
        assertTrue(!git.isRepo(dir))
        File(dir, ".git").mkdir()
        assertTrue(git.isRepo(dir))
    }

    @Test
    fun successPassthrough() {
        val shell = FakeShell(handler = { ProcResult(0, "main", "") })
        val git = GitEngine(shell)
        val r = git.branch(tmp.newFolder())
        assertEquals("main", (r as EliResult.Ok).value.stdout)
    }
}
