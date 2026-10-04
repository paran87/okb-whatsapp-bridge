package com.okb.whatsappbridge.util

import com.okb.whatsappbridge.util.fingerprint.MessageFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFingerprintTest {

    private val base = MessageFingerprint.compute("OKB Monitoring", "Juan Santos", "Flooding at San Jose", 1000L)

    @Test
    fun `fingerprint is a deterministic sha256 hex`() {
        assertEquals(base, MessageFingerprint.compute("OKB Monitoring", "Juan Santos", "Flooding at San Jose", 1000L))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(base))
    }

    @Test
    fun `group and sender are compared case and whitespace insensitively`() {
        assertEquals(base, MessageFingerprint.compute(" okb  monitoring", "JUAN SANTOS ", "Flooding at San Jose", 1000L))
    }

    @Test
    fun `any change in content or time produces a different fingerprint`() {
        assertNotEquals(base, MessageFingerprint.compute("OKB Monitoring", "Juan Santos", "Flooding at San Jose!", 1000L))
        assertNotEquals(base, MessageFingerprint.compute("OKB Monitoring", "Juan Santos", "Flooding at San Jose", 1001L))
        assertNotEquals(base, MessageFingerprint.compute("OKB Monitoring", "Ana", "Flooding at San Jose", 1000L))
        assertNotEquals(base, MessageFingerprint.compute("Other", "Juan Santos", "Flooding at San Jose", 1000L))
    }

    @Test
    fun `field boundaries cannot collide`() {
        assertNotEquals(
            MessageFingerprint.compute("ab", "c", "x", 1L),
            MessageFingerprint.compute("a", "bc", "x", 1L),
        )
    }
}
