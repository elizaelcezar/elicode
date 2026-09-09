package com.elicode.app

import com.elicode.app.buildsys.TestLogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestLogParserTest {

    @Test
    fun greenBuild() {
        val log = """
            > Task :app:testDebugUnitTest
            50 tests completed, 0 failed
            BUILD SUCCESSFUL in 7s
        """.trimIndent()
        val s = TestLogParser.summarize(log)
        assertEquals(50, s.total)
        assertEquals(0, s.failed)
        assertTrue(s.buildOk)
        assertTrue(s.failedTasks.isEmpty())
        assertEquals("50 tests passed ✓", s.headline())
    }

    @Test
    fun redBuild() {
        val log = """
            > Task :app:testDebugUnitTest FAILED
            NativeBridgeTest > envMergeKeepsParentAndExtraWins FAILED
            50 tests completed, 1 failed
            FAILURE: Build failed with an exception.
            BUILD FAILED in 16s
        """.trimIndent()
        val s = TestLogParser.summarize(log)
        assertEquals(50, s.total)
        assertEquals(1, s.failed)
        assertFalse(s.buildOk)
        assertEquals(listOf(":app:testDebugUnitTest"), s.failedTasks)
        assertEquals("50 tests, 1 failed.", s.headline())
    }

    @Test
    fun noTestsRan() {
        val s = TestLogParser.summarize("FAILURE: plugin not found\nBUILD FAILED")
        assertEquals(0, s.total)
        assertFalse(s.buildOk)
        assertEquals("No tests ran — build failed.", s.headline())
    }

    @Test
    fun reportContainsSummaryAndLog() {
        val s = TestLogParser.Summary(10, 2, false, listOf(":app:testDebugUnitTest"))
        val r = TestLogParser.report("demo", s, "line1\nline2")
        assertTrue(r.contains("demo"))
        assertTrue(r.contains("10 tests, 2 failed."))
        assertTrue(r.contains(":app:testDebugUnitTest"))
        assertTrue(r.contains("line2"))
    }
}
