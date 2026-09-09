package com.elicode.app

import com.elicode.app.runtime.ArchSupport
import com.elicode.app.runtime.ProotLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotLinkerTest {

    @Test
    fun linkerNamePerArch() {
        assertEquals("linker64", ProotLauncher.linkerName(ArchSupport.ARM64))
        assertEquals("linker64", ProotLauncher.linkerName(ArchSupport.X86_64))
        assertEquals("linker", ProotLauncher.linkerName(""))
    }

    @Test
    fun linkerArgvShape() {
        val argv = ProotLauncher.linkerArgv(
            "/system/bin/linker64", "/lib", "/proot",
            listOf("-r", "rootfs", "/bin/bash")
        )
        assertEquals(
            listOf(
                "/system/bin/linker64", "--library-path", "/lib", "/proot",
                "-r", "rootfs", "/bin/bash"
            ),
            argv
        )
        assertTrue(argv[3].endsWith("proot"))
    }

    @Test
    fun pickModeCheapestFirst() {
        assertEquals(0, ProotLauncher.pickMode(true, false, false))
        assertEquals(0, ProotLauncher.pickMode(true, true, true))
        assertEquals(1, ProotLauncher.pickMode(false, true, false))
        assertEquals(1, ProotLauncher.pickMode(false, true, true))
        assertEquals(2, ProotLauncher.pickMode(false, false, true))
        assertEquals(-1, ProotLauncher.pickMode(false, false, false))
    }
}
