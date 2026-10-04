package com.okb.whatsappbridge.integration

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.data.remote.api.OkHttpBridgeApi
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.domain.usecase.ProcessingOutcome
import com.okb.whatsappbridge.domain.usecase.SyncOutcome
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.fakes.Snapshots.T0
import com.okb.whatsappbridge.fakes.TestBridge
import com.okb.whatsappbridge.service.NotificationSnapshotExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * JVM end-to-end test of the background pipeline with no Activity involved:
 * real Android Notification (MessagingStyle) → StatusBarNotification → extractor → filter/parser →
 * Room → sync engine → real HTTP client → backend (MockWebServer).
 *
 * This is NOT a substitute for the physical-device acceptance test (see README), but it exercises the
 * exact code the NotificationListenerService and MessageUploadWorker run.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BackgroundPipelineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var bridge: TestBridge

    @Before
    fun setUp() = runTest {
        server = MockWebServer().apply { start() }
        bridge = TestBridge(context, OkHttpBridgeApi(), clock = { T0 + 1_000 })
        bridge.settings.setMonitoringEnabled(true)
        bridge.settings.setBackendUrl(server.url("/").toString())
        bridge.identity.setDeviceToken("device-token-1")
        bridge.groups.addAuthorizedGroup("OKB Monitoring", T0)
    }

    @After
    fun tearDown() {
        bridge.db.close()
        runCatching { server.shutdown() }
    }

    private fun whatsAppNotification(
        packageName: String,
        group: String,
        vararg messages: Triple<String, Long, String>,
        groupJid: String = "120363025246125486@g.us",
    ): StatusBarNotification {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setConversationTitle(group)
            .setGroupConversation(true)
        messages.forEach { (text, time, sender) -> style.addMessage(text, time, Person.Builder().setName(sender).build()) }
        val notification: Notification = NotificationCompat.Builder(context, "group_chat_defaults")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(group)
            .setContentText(messages.last().first)
            .setStyle(style)
            .setShortcutId(groupJid)
            .setWhen(messages.last().second)
            .build()
        @Suppress("DEPRECATION")
        return StatusBarNotification(packageName, packageName, 1, groupJid, 10123, 0, 0, notification, Process.myUserHandle(), T0 + 500)
    }

    private suspend fun capture(sbn: StatusBarNotification) = bridge.process(NotificationSnapshotExtractor.extract(sbn))

    @Test
    fun `whatsapp group notification is captured, stored and uploaded`() = runTest {
        val outcome = capture(
            whatsAppNotification("com.whatsapp", "OKB Monitoring", Triple("Flooding observed at Barangay San Jose", T0, "Juan Santos")),
        )
        assertEquals(ProcessingOutcome.Captured("OKB Monitoring", 1, 0), outcome)

        val stored = bridge.messages.observeRecent(null, 10).first().single()
        assertEquals(UploadStatus.PENDING_UPLOAD, stored.uploadStatus)
        assertEquals("Juan Santos", stored.senderName)
        assertEquals(T0, stored.timestamp)

        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"srv-77"}"""))
        val sync = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(1, sync.uploaded)

        val request = server.takeRequest()
        assertEquals("/api/v1/messages", request.path)
        assertEquals("Bearer device-token-1", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("OKB Monitoring", body["groupName"]!!.jsonPrimitive.content)
        assertEquals("Juan Santos", body["senderName"]!!.jsonPrimitive.content)
        assertEquals("Flooding observed at Barangay San Jose", body["messageText"]!!.jsonPrimitive.content)
        assertEquals("2026-10-04T08:22:00+08:00", body["timestamp"]!!.jsonPrimitive.content)
        assertEquals("TEXT", body["mediaType"]!!.jsonPrimitive.content)

        val uploaded = bridge.messages.observeRecent(null, 10).first().single()
        assertEquals(UploadStatus.UPLOADED, uploaded.uploadStatus)
        assertEquals("srv-77", uploaded.serverId)
    }

    @Test
    fun `offline test - message stays PENDING then RETRYING and uploads when the backend returns`() = runTest {
        capture(whatsAppNotification("com.whatsapp.w4b", "OKB Monitoring", Triple("📷 Water at 2 meters", T0, "Ana")))
        val stored = bridge.messages.observeRecent(null, 10).first().single()
        assertEquals(UploadStatus.PENDING_UPLOAD, stored.uploadStatus)
        assertEquals(MediaType.IMAGE, stored.mediaType)

        // Backend unreachable ("internet disabled").
        val url = server.url("/").toString()
        server.shutdown()
        val offline = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertTrue(offline.retryNeeded)
        assertEquals(UploadStatus.RETRYING, bridge.messages.observeRecent(null, 10).first().single().uploadStatus)

        // "Internet re-enabled": a backend is listening again at a new address.
        server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"srv-1"}"""))
        bridge.settings.setBackendUrl(server.url("/").toString())
        assertTrue(url.isNotEmpty())
        val online = bridge.sync(SyncTrigger.IMMEDIATE) as SyncOutcome.Completed
        assertEquals(1, online.uploaded)
        assertEquals(UploadStatus.UPLOADED, bridge.messages.observeRecent(null, 10).first().single().uploadStatus)
    }

    @Test
    fun `reposted conversation and unauthorized groups do not leak or duplicate`() = runTest {
        capture(whatsAppNotification("com.whatsapp", "OKB Monitoring", Triple("First", T0, "Ana")))
        capture(
            whatsAppNotification(
                "com.whatsapp", "OKB Monitoring (2 messages)",
                Triple("First", T0, "Ana"), Triple("Second", T0 + 1000, "Ben"),
            ),
        )
        capture(whatsAppNotification("com.whatsapp", "Family Group", Triple("Dinner at 7", T0, "Mom"), groupJid = "999@g.us"))

        val stored = bridge.messages.observeRecent(null, 10).first()
        assertEquals(listOf("Second", "First"), stored.map { it.messageText })
        assertTrue(stored.all { it.groupName == "OKB Monitoring" })
        val family = bridge.groups.observeGroups().first().single { it.name == "Family Group" }
        assertEquals(false, family.authorized)
    }
}
