package com.okb.whatsappbridge.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupAllowlistTest {

    private val authorized = listOf("OKB Monitoring", "Regional Flood Reports")

    @Test
    fun `matching ignores case and extra whitespace`() {
        assertEquals("OKB Monitoring", GroupAllowlist.match(listOf("  okb   monitoring "), authorized))
    }

    @Test
    fun `first matching candidate wins`() {
        assertEquals(
            "Regional Flood Reports",
            GroupAllowlist.match(listOf("Regional Flood Reports (2 messages)", "Regional Flood Reports"), authorized),
        )
    }

    @Test
    fun `unauthorized groups are rejected`() {
        assertNull(GroupAllowlist.match(listOf("Family Group"), authorized))
        assertNull(GroupAllowlist.match(listOf("OKB"), authorized))
        assertNull(GroupAllowlist.match(listOf("OKB Monitoring"), emptyList()))
        assertNull(GroupAllowlist.match(emptyList(), authorized))
    }

    @Test
    fun `normalize is stable`() {
        assertEquals("okb monitoring", GroupAllowlist.normalize("OKB\tMonitoring "))
    }
}
