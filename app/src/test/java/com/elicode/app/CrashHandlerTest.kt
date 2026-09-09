package com.elicode.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CrashHandlerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun persistsCrashAndChainsToPreviousHandler() {
        val latch = CountDownLatch(1)
        val prev = Thread.UncaughtExceptionHandler { _, _ -> latch.countDown() }
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(prev)
        try {
            EliCodeApp.installCrashHandler(tmp.root)
            val boom = RuntimeException("boom-test", IllegalStateException("root-cause"))
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)
            assertTrue(latch.await(5, TimeUnit.SECONDS))

            val text = EliCodeApp.readCrash(tmp.root)
            assertTrue(text.contains("boom-test"))
            assertTrue(text.contains("root-cause"))
            assertTrue(text.contains("Caused by:"))
            assertTrue(text.contains(Thread.currentThread().name))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
        }
    }

    @Test
    fun rotatesHugeCrashFile() {
        val f = File(tmp.root, EliCodeApp.CRASH_FILE)
        f.writeBytes(ByteArray((EliCodeApp.MAX_CRASH_BYTES + 1000).toInt()))
        val old = Thread.getDefaultUncaughtExceptionHandler()
        try {
            EliCodeApp.installCrashHandler(tmp.root, null)
            Thread.getDefaultUncaughtExceptionHandler()!!
                .uncaughtException(Thread.currentThread(), RuntimeException("fresh"))
            val text = EliCodeApp.readCrash(tmp.root)
            assertTrue(text.contains("fresh"))
            assertTrue(f.length() < EliCodeApp.MAX_CRASH_BYTES + 1000)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(old)
        }
    }

    @Test
    fun clearCrashRemovesFile() {
        File(tmp.root, EliCodeApp.CRASH_FILE).writeText("x")
        EliCodeApp.clearCrash(tmp.root)
        assertFalse(File(tmp.root, EliCodeApp.CRASH_FILE).exists())
        assertTrue(EliCodeApp.readCrash(tmp.root).isEmpty())
    }
}
