package com.okb.whatsappbridge.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.data.local.database.BridgeDatabase
import com.okb.whatsappbridge.data.repository.RoomGroupRepository
import com.okb.whatsappbridge.data.repository.RoomSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SettingsAndGroupsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun open(name: String) = Room.databaseBuilder(context, BridgeDatabase::class.java, name)
        .allowMainThreadQueries()
        .build()

    @Test
    fun `defaults are safe - monitoring off, sync on, nothing configured`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java).allowMainThreadQueries().build()
        val settings = RoomSettingsRepository(db.settingsDao()).current()
        assertFalse(settings.monitoringEnabled)
        assertFalse(settings.syncPaused)
        assertFalse(settings.backendConfigured)
        assertNull(settings.lastNotificationAt)
        db.close()
    }

    @Test
    fun `settings and allowlist survive a process or device restart`() = runTest {
        val name = "restart-test.db"
        context.deleteDatabase(name)

        val first = open(name)
        val settings = RoomSettingsRepository(first.settingsDao(), clock = { 42L })
        settings.setMonitoringEnabled(true)
        settings.setBackendUrl(" https://okb.example.org ")
        settings.recordUploadFailure(7L, "HTTP 503")
        RoomGroupRepository(first.groupDao()).addAuthorizedGroup("OKB Monitoring", 1L)
        first.close()

        // Simulates the app process being recreated after reboot: a brand-new database instance.
        val second = open(name)
        val restored = RoomSettingsRepository(second.settingsDao()).settings.first()
        assertTrue(restored.monitoringEnabled)
        assertEquals("https://okb.example.org", restored.backendUrl)
        assertEquals(7L, restored.lastUploadFailureAt)
        assertEquals("HTTP 503", restored.lastUploadError)
        assertEquals(listOf("OKB Monitoring"), RoomGroupRepository(second.groupDao()).authorizedGroupNames())
        second.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `group allowlist supports add, discover, authorize and remove`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java).allowMainThreadQueries().build()
        val groups = RoomGroupRepository(db.groupDao())

        assertFalse(groups.addAuthorizedGroup("   ", 1))
        assertTrue(groups.addAuthorizedGroup("OKB  Monitoring", 1))
        groups.recordSeen("Family Group", 2)
        groups.recordSeen("okb monitoring", 3)

        val all = groups.observeGroups().first()
        assertEquals(2, all.size)
        val okb = all.single { it.name == "OKB Monitoring" }
        assertTrue(okb.authorized)
        assertEquals(3L, okb.lastSeenAt)
        val family = all.single { it.name == "Family Group" }
        assertFalse(family.authorized)
        assertTrue(family.discoveredAutomatically)
        assertEquals(listOf("OKB Monitoring"), groups.authorizedGroupNames())

        // Authorizing an already-discovered group by name does not create a duplicate row.
        groups.addAuthorizedGroup("family group", 4)
        assertEquals(2, groups.observeGroups().first().size)
        assertEquals(setOf("OKB Monitoring", "Family Group"), groups.authorizedGroupNames().toSet())

        groups.setAuthorized(family.id, false)
        groups.delete(okb.id)
        assertTrue(groups.authorizedGroupNames().isEmpty())
        db.close()
    }
}
