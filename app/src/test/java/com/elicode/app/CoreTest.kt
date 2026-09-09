package com.elicode.app

import com.elicode.app.core.EliError
import com.elicode.app.core.FileManager
import com.elicode.app.core.ProjectManager
import com.elicode.app.core.templates.Templates
import com.elicode.app.git.GitEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun eliErrorFormatsAllFields() {
        val e = EliError("op", "cmd", 1, "msg", "cause", "fix")
        val f = e.format()
        assertTrue(f.contains("op"))
        assertTrue(f.contains("cmd"))
        assertTrue(f.contains("1"))
        assertTrue(f.contains("cause"))
        assertTrue(f.contains("fix"))
    }

    @Test
    fun fileManagerBlocksTraversal() {
        val root = tmp.newFolder()
        try {
            FileManager.resolveSafe(root, "../../evil")
            assertFalse("should have thrown", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("traversal", ignoreCase = true))
        }
    }

    @Test
    fun fileManagerRoundTrip() {
        val root = tmp.newFolder()
        FileManager.createFile(root, "a/b.txt", "hi")
        assertEquals("hi", FileManager.readText(File(root, "a/b.txt")))
        assertEquals("b.txt", FileManager.relativePath(root, File(root, "a/b.txt").canonicalFile).substringAfter('/'))
    }

    @Test
    fun androidTemplateLooksBuildable() {
        val files = Templates.byId("android-compose").files("Demo", "com.example.demo")
        assertTrue(files.containsKey("settings.gradle"))
        assertTrue(files.containsKey("app/build.gradle"))
        assertTrue(files.containsKey("app/src/main/AndroidManifest.xml"))
        assertTrue(files["app/build.gradle"]!!.contains("com.android.application"))
        assertTrue(files.keys.any { it.endsWith("MainActivity.kt") })
    }

    @Test
    fun allTemplatesNonEmpty() {
        Templates.all.forEach { t ->
            val files = t.files("App", "com.example.app")
            assertTrue("${t.id} is empty", files.isNotEmpty())
            files.forEach { (k, v) ->
                assertTrue("$k blank", v.isNotBlank())
                assertFalse("$k traverses", k.contains(".."))
            }
        }
    }

    @Test
    fun gitTokenInjection() {
        val engine = GitEngine(FakeShell())
        val authed = engine.injectToken("https://github.com/u/r.git", "TOKEN123")
        assertEquals("https://x-access-token:TOKEN123@github.com/u/r.git", authed)
        assertEquals("https://github.com/u/r.git", engine.injectToken("https://github.com/u/r.git", null))
        assertEquals("git@github.com:u/r.git", engine.injectToken("git@github.com:u/r.git", "TOKEN123"))
    }

    @Test
    fun gitSanitizeRedactsTokens() {
        val engine = GitEngine(FakeShell())
        val dirty = "git clone https://x-access-token:SECRET@github.com/u/r.git"
        assertFalse(engine.sanitize(dirty).contains("SECRET"))
    }

    @Test
    fun slugify() {
        assertEquals("my-app", ProjectManager.slugify("My App!"))
        assertTrue(ProjectManager.slugify("x").isNotEmpty())
    }
}
