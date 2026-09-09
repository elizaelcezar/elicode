package com.elicode.app

import com.elicode.app.runtime.ArchSupport
import com.elicode.app.runtime.ProotLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotLinkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun linkerNamePerArch() {
        assertEquals("linker64", ProotLauncher.linkerName(ArchSupport.ARM64))
        assertEquals("linker64", ProotLauncher.linkerName(ArchSupport.X86_64))
        assertEquals("linker", ProotLauncher.linkerName(""))
    }

    @Test
    fun linkerArgvShape() {
        // Bionic takes no flags: [linker, program, args...], libs via env.
        val argv = ProotLauncher.linkerArgv(
            "/system/bin/linker64", "/proot",
            listOf("-r", "rootfs", "/bin/bash")
        )
        assertEquals(
            listOf(
                "/system/bin/linker64", "/proot",
                "-r", "rootfs", "/bin/bash"
            ),
            argv
        )
        assertTrue(argv[1].endsWith("proot"))
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

    @Test
    fun loaderEnvPointsAtInstalledHelpers() {
        val loader = tmp.newFile("loader").apply { writeText("x") }
        val loader32 = tmp.newFile("loader32").apply { writeText("y") }
        val env = ProotLauncher.loaderEnv(loader, loader32)
        assertEquals(loader.absolutePath, env["PROOT_LOADER"])
        assertEquals(loader32.absolutePath, env["PROOT_LOADER_32"])
    }

    @Test
    fun loaderEnvOmitsMissingHelpers() {
        val missing = java.io.File(tmp.root, "nope-loader")
        val env = ProotLauncher.loaderEnv(missing, null)
        assertTrue(env.isEmpty())
    }
}
