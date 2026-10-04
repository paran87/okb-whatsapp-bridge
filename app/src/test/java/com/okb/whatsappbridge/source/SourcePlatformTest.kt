package com.okb.whatsappbridge.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlatformTest {

    @Test
    fun `packages map to their platform`() {
        assertEquals(SourcePlatform.WHATSAPP, SourcePlatform.fromPackage("com.whatsapp"))
        assertEquals(SourcePlatform.WHATSAPP, SourcePlatform.fromPackage("com.whatsapp.w4b"))
        assertEquals(SourcePlatform.VIBER, SourcePlatform.fromPackage("com.viber.voip"))
        assertNull(SourcePlatform.fromPackage("org.telegram.messenger"))
        assertNull(SourcePlatform.fromPackage("com.viber.voip.fake"))
        assertNull(SourcePlatform.fromPackage(null))
        assertTrue(SourcePlatform.isSupported("com.viber.voip"))
        assertFalse(SourcePlatform.isSupported("com.google.android.gm"))
    }

    @Test
    fun `wire names and display names`() {
        assertEquals("whatsapp", SourcePlatform.WHATSAPP.wireName)
        assertEquals("viber", SourcePlatform.VIBER.wireName)
        assertEquals("WhatsApp Business", SourcePlatform.displayNameFor("com.whatsapp.w4b"))
        assertEquals("Viber", SourcePlatform.displayNameFor("com.viber.voip"))
        assertEquals("org.example", SourcePlatform.displayNameFor("org.example"))
    }

    @Test
    fun `whatsapp keeps an unscoped fingerprint so existing messages still deduplicate`() {
        assertNull(SourcePlatform.WHATSAPP.fingerprintScope)
        assertEquals("viber", SourcePlatform.VIBER.fingerprintScope)
    }
}
