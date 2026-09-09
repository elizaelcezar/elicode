package com.elicode.app

import com.elicode.app.runtime.EliProcess
import com.elicode.app.runtime.JvmProcessRunner
import com.elicode.app.runtime.NativeBridge
import com.elicode.app.runtime.NativeProcessRunner
import com.elicode.app.runtime.ProcResult
import com.elicode.app.runtime.defaultProcessRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeBridgeTest {

    @Test
    fun unavailableOnJvm() {
        // Unit tests run on the host JVM, where libelicode_bridge.so
        // is never present — the factory must fall back to JVM pipes.
        assertFalse(NativeBridge.AVAILABLE)
    }

    @Test
    fun factoryFallsBackToJvm() {
        assertTrue(defaultProcessRunner(usePty = false) is JvmProcessRunner)
        assertTrue(defaultProcessRunner(usePty = true) is JvmProcessRunner)
    }

    @Test
    fun nativeRunnerRefusesWithoutLibrary() {
        try {
            NativeProcessRunner().start(listOf("sh"), null, emptyMap(), "x", null)
            fail("expected IllegalStateException when the .so is absent")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not loaded"))
        }
    }

    @Test
    fun envMergeKeepsParentAndExtraWins() {
        val parent = System.getenv()
        val merged = NativeProcessRunner.mergedEnv(mapOf("ELICODE_TEST_XYZ" to "1"))
        val back = merged.mapNotNull { entry ->
            // Same rule as native childExec: separator is the first '=',
            // except entries starting with '=' (Windows per-drive vars
            // like "=C:=..."), which carry no usable KEY=VALUE pair.
            val sep = entry.indexOf('=', if (entry.startsWith("=")) 1 else 0)
            if (sep <= 0) null
            else entry.substring(0, sep) to entry.substring(sep + 1)
        }.toMap()
        assertEquals("1", back["ELICODE_TEST_XYZ"])
        // Every usable parent entry survives (extra only overrides same keys).
        var checked = 0
        for ((k, v) in parent) {
            if (k.isEmpty() || k.contains('=') || k == "ELICODE_TEST_XYZ") continue
            assertEquals(v, back[k])
            checked++
        }
        assertTrue(checked > 0)
    }

    @Test
    fun sentinelsMatchNative() {
        assertEquals(-1000, NativeBridge.WAIT_RUNNING)
        assertEquals(0L, NativeBridge.INVALID_HANDLE)
    }

    @Test
    fun interruptIsPartOfTheContract() {
        var interrupted = false
        val fake = object : EliProcess {
            override val label = "fake"
            override val pid = 1L
            override val isAlive = true
            override fun writeStdin(text: String) = Unit
            override fun closeStdin() = Unit
            override fun interrupt() { interrupted = true }
            override fun kill() = Unit
            override fun waitFor() = 0
            override fun snapshot() = ProcResult(0, "", "")
        }
        fake.interrupt()
        assertTrue(interrupted)
    }
}
