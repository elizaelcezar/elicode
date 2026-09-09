package com.elicode.app

import com.elicode.app.runtime.RuntimeInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {

    @Test
    fun firstFailureRetries() {
        assertTrue(RuntimeInstaller.RecoveryPolicy.shouldRedownload(0))
    }

    @Test
    fun secondFailureGivesUp() {
        assertFalse(RuntimeInstaller.RecoveryPolicy.shouldRedownload(1))
        assertFalse(RuntimeInstaller.RecoveryPolicy.shouldRedownload(5))
    }
}
