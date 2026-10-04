package com.okb.whatsappbridge.util

import com.okb.whatsappbridge.data.repository.SecureDeviceIdentityRepository
import com.okb.whatsappbridge.util.security.InMemorySecretStore
import com.okb.whatsappbridge.util.security.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTest {

    @Test
    fun `device id has the OKB format and is stable`() {
        val store = InMemorySecretStore()
        val repo = SecureDeviceIdentityRepository(store)
        val id = repo.deviceId()
        assertTrue(id, SecureDeviceIdentityRepository.DEVICE_ID_PATTERN.matches(id))
        assertEquals(id, repo.deviceId())
        // A new repository instance (e.g. after process restart) reads the same stored id.
        assertEquals(id, SecureDeviceIdentityRepository(store).deviceId())
    }

    @Test
    fun `device token can be set, trimmed and cleared`() {
        val repo = SecureDeviceIdentityRepository(InMemorySecretStore())
        assertFalse(repo.hasDeviceToken())
        repo.setDeviceToken("  secret-token ")
        assertEquals("secret-token", repo.deviceToken())
        repo.setDeviceToken("")
        assertNull(repo.deviceToken())
    }

    @Test
    fun `redactor masks credentials`() {
        val out = Redactor.redact("Authorization: Bearer abc.def-123 token=xyz password: hunter2")
        assertFalse(out.contains("abc.def-123"))
        assertFalse(out.contains("xyz"))
        assertFalse(out.contains("hunter2"))
        assertEquals("Not set", Redactor.mask(null))
        assertEquals("••••••••6789", Redactor.mask("abcdef123456789"))
        assertEquals("••••••••", Redactor.mask("short"))
    }
}
