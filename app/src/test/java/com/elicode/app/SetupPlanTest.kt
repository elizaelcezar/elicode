package com.elicode.app

import com.elicode.app.runtime.SetupOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPlanTest {

    @Test
    fun freshDeviceRunsEverything() {
        val steps = SetupOrchestrator.planSteps(SetupOrchestrator.InstallState())
        assertEquals(4, steps.size)
        assertTrue(steps.all { it.state == SetupOrchestrator.State.PENDING })
        assertEquals(
            listOf("runtime", "node", "opencode", "android"),
            steps.map { it.id }
        )
    }

    @Test
    fun installedStepsAreSkipped() {
        val steps = SetupOrchestrator.planSteps(
            SetupOrchestrator.InstallState(
                runtimeInstalled = true,
                nodeVersion = "v20.11.0",
                opencodeVersion = null,
                androidReady = true
            )
        )
        val byId = steps.associateBy { it.id }
        assertEquals(SetupOrchestrator.State.SKIPPED, byId["runtime"]!!.state)
        assertEquals(SetupOrchestrator.State.SKIPPED, byId["node"]!!.state)
        assertEquals(SetupOrchestrator.State.PENDING, byId["opencode"]!!.state)
        assertEquals(SetupOrchestrator.State.SKIPPED, byId["android"]!!.state)
    }

    @Test
    fun fullyInstalledSkipsAll() {
        val steps = SetupOrchestrator.planSteps(
            SetupOrchestrator.InstallState(
                runtimeInstalled = true,
                nodeVersion = "v20.11.0",
                opencodeVersion = "0.1.0",
                androidReady = true
            )
        )
        assertTrue(steps.all { it.state == SetupOrchestrator.State.SKIPPED })
    }
}
