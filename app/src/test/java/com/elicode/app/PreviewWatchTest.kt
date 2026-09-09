package com.elicode.app

import com.elicode.app.preview.PreviewEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PreviewWatchTest {

    private fun tmpDir(): File =
        Files.createTempDirectory("elicode-preview-test").toFile()

    @Test
    fun stableWithoutChanges() {
        val dir = tmpDir()
        try {
            File(dir, "index.html").writeText("<h1>hi</h1>")
            val a = PreviewEngine.fingerprint(dir)
            val b = PreviewEngine.fingerprint(dir)
            assertEquals(a, b)
            assertTrue(a != 0L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun detectsContentChange() {
        val dir = tmpDir()
        try {
            val f = File(dir, "app.js")
            f.writeText("v1")
            val before = PreviewEngine.fingerprint(dir)
            Thread.sleep(1100)
            f.writeText("v2")
            assertNotEquals(before, PreviewEngine.fingerprint(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ignoresBuildOutputs() {
        val dir = tmpDir()
        try {
            File(dir, "index.html").writeText("x")
            val before = PreviewEngine.fingerprint(dir)
            val git = File(dir, ".git/objects").apply { mkdirs() }
            File(git, "pack").writeText("binary-blob")
            val node = File(dir, "node_modules/lib").apply { mkdirs() }
            File(node, "bundle.js").writeText("huge")
            assertEquals(before, PreviewEngine.fingerprint(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingDirIsZero() {
        assertEquals(0L, PreviewEngine.fingerprint(File("/nonexistent-elicode-dir")))
    }
}
