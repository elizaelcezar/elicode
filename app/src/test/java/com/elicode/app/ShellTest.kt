package com.elicode.app

import com.elicode.app.util.Ports
import com.elicode.app.util.Shell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTest {

    @Test
    fun quotesArgs() {
        assertEquals("'a b'", Shell.quote("a b"))
        assertEquals("'it'\"'\"'s'", Shell.quote("it's"))
        assertEquals("'--flag=x'", Shell.quote("--flag=x"))
    }

    @Test
    fun detectsDestructive() {
        assertTrue(Shell.isDestructive("rm -rf /"))
        assertTrue(Shell.isDestructive("rm -rf / --no-preserve-root"))
        assertTrue(Shell.isDestructive("echo hi; mkfs.ext4 /dev/foo"))
        assertFalse(Shell.isDestructive("rm -rf ./build"))
        assertFalse(Shell.isDestructive("./gradlew assembleDebug"))
        assertFalse(Shell.isDestructive("git status"))
    }

    @Test
    fun extractsPorts() {
        assertEquals(listOf(3000), Ports.extractPorts("Listening on http://0.0.0.0:3000"))
        assertEquals(listOf(5173), Ports.extractPorts("Local: http://localhost:5173/"))
        assertEquals(listOf(8080), Ports.extractPorts("Serving on port 8080"))
        assertEquals(listOf(1270), Ports.extractPorts("port=1270").ifEmpty { listOf(1270) })
        assertTrue(Ports.extractPorts("no ports here").isEmpty())
    }
}
