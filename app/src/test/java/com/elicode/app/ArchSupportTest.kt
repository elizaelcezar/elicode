package com.elicode.app

import com.elicode.app.runtime.ArchSupport
import com.elicode.app.runtime.ArchiveExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchSupportTest {

    @Test
    fun prefersArm64WhenBothPresent() {
        assertEquals("arm64", ArchSupport.selectArch(listOf("arm64-v8a", "x86_64")))
    }

    @Test
    fun selectsX86_64ForEmulator() {
        assertEquals("x86_64", ArchSupport.selectArch(listOf("x86_64", "x86")))
    }

    @Test
    fun rejects32BitOnly() {
        assertEquals("", ArchSupport.selectArch(listOf("armeabi-v7a", "x86")))
        assertFalse(ArchSupport.isSupported(listOf("armeabi-v7a")))
    }

    @Test
    fun acceptsBoth() {
        assertTrue(ArchSupport.isSupported(listOf("arm64-v8a")))
        assertTrue(ArchSupport.isSupported(listOf("x86_64")))
    }

    @Test
    fun expectedElfMatchesArch() {
        assertEquals(183, ArchSupport.expectedElf("arm64"))
        assertEquals(62, ArchSupport.expectedElf("x86_64"))
        assertEquals(ArchiveExtractor.EM_AARCH64, ArchSupport.expectedElf("arm64"))
        assertEquals(ArchiveExtractor.EM_X86_64, ArchSupport.expectedElf("x86_64"))
    }

    @Test
    fun guestLoaderNamePerArch() {
        // Matches Ubuntu 22.04 INTERP filenames (verified from the
        // ubuntu-base tarball: /lib/<name> with merged-/usr).
        assertEquals("ld-linux-aarch64.so.1", ArchSupport.guestLoaderName("arm64"))
        assertEquals("ld-linux-x86-64.so.2", ArchSupport.guestLoaderName("x86_64"))
    }
}
