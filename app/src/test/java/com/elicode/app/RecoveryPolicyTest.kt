package com.elicode.app

import com.elicode.app.runtime.RuntimeInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun firstFailureRetries() {
        assertTrue(RuntimeInstaller.RecoveryPolicy.shouldRedownload(0))
    }

    @Test
    fun secondFailureGivesUp() {
        assertFalse(RuntimeInstaller.RecoveryPolicy.shouldRedownload(1))
        assertFalse(RuntimeInstaller.RecoveryPolicy.shouldRedownload(5))
    }

    @Test
    fun missingResolvNeedsWrite() {
        assertTrue(RuntimeInstaller.DnsPolicy.needsWrite(tmp.root.resolve("resolv.conf")))
    }

    @Test
    fun emptyResolvNeedsWrite() {
        // Ubuntu-base ships a 0-byte etc/resolv.conf: existence alone
        // must not count as configured, or the guest has no DNS.
        val f = tmp.newFile("resolv.conf")
        assertTrue(RuntimeInstaller.DnsPolicy.needsWrite(f))
    }

    @Test
    fun configuredResolvIsKept() {
        val f = tmp.newFile("resolv.conf")
        f.writeText("nameserver 1.1.1.1\n")
        assertFalse(RuntimeInstaller.DnsPolicy.needsWrite(f))
    }
}
