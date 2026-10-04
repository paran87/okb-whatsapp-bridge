package com.okb.whatsappbridge.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.domain.model.MediaAcquisitionStatus
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.usecase.ProcessingOutcome
import com.okb.whatsappbridge.fakes.Snapshots
import com.okb.whatsappbridge.fakes.Snapshots.T0
import com.okb.whatsappbridge.fakes.TestBridge
import com.okb.whatsappbridge.media.MediaUriMetadata
import com.okb.whatsappbridge.whatsapp.SnapshotMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MediaCaptureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var bridge: TestBridge
    private val imageBytes = ByteArray(5000) { (it % 255).toByte() }

    @Before
    fun setUp() = runTest {
        bridge = TestBridge(ApplicationProvider.getApplicationContext(), com.okb.whatsappbridge.fakes.FakeBridgeApi(), tmp.newFolder("media"), clock = { T0 + 1000 })
        bridge.settings.setMonitoringEnabled(true)
        bridge.groups.addAuthorizedGroup("OKB Monitoring", T0)
    }

    @After
    fun tearDown() = bridge.db.close()

    private fun photo(uri: String?, caption: String = "📷 Flooding near bridge") =
        Snapshots.groupMessaging(
            messages = listOf(SnapshotMessage(caption, T0, "Ana", dataUri = uri, dataMimeType = "image/jpeg")),
            hasPicture = true,
        )

    private suspend fun mediaRow(messageId: String) = bridge.media.observeForMessage(messageId).first().firstOrNull()
    private suspend fun lastMessageId() = bridge.db.messageDao().getById(
        bridge.messages.observeRecent(null, 1).first().first().id,
    )!!.id

    @Test
    fun `photo with a readable notification URI is acquired, hashed and queued`() = runTest {
        val uri = "content://com.whatsapp.provider/media/1"
        bridge.content.bytesByUri[uri] = imageBytes
        bridge.content.metadataByUri[uri] = MediaUriMetadata("IMG-1.jpg", "image/jpeg", imageBytes.size.toLong())

        val outcome = bridge.process(photo(uri))
        assertTrue(outcome is ProcessingOutcome.Captured)
        assertEquals(1, (outcome as ProcessingOutcome.Captured).mediaDetected)

        val messageId = lastMessageId()
        val media = mediaRow(messageId)!!
        assertEquals(MediaType.IMAGE, media.mediaType)
        assertEquals(MediaAcquisitionStatus.AVAILABLE, media.acquisitionStatus)
        assertEquals(imageBytes.size.toLong(), media.fileSizeBytes)
        assertNotNull(media.sha256)
        assertTrue(media.hasLocalFile)
        assertEquals("IMG-1.jpg", media.originalFileName)
        // Caption preserved on the message.
        assertEquals("📷 Flooding near bridge", bridge.db.messageDao().getById(messageId)!!.messageText)
        // Media upload scheduled and queue has one uploadable row.
        assertTrue(bridge.scheduler.mediaRequests.isNotEmpty())
        assertEquals(1, bridge.media.countUploadable(includeFailed = false))
        assertNotNull(bridge.settings.current().lastMediaCaptureAt)
    }

    @Test
    fun `photo without a notification URI is a clean UNAVAILABLE, text still captured`() = runTest {
        val outcome = bridge.process(photo(uri = null))
        assertEquals(1, (outcome as ProcessingOutcome.Captured).mediaDetected)
        val messageId = lastMessageId()
        val media = mediaRow(messageId)!!
        assertEquals(MediaAcquisitionStatus.UNAVAILABLE, media.acquisitionStatus)
        assertFalse(media.hasLocalFile)
        assertNotNull(media.statusDetail)
        assertTrue(media.statusDetail!!.contains("notification", ignoreCase = true))
        assertEquals("📷 Flooding near bridge", bridge.db.messageDao().getById(messageId)!!.messageText)
        assertEquals(0, bridge.media.countUploadable(includeFailed = false))
        assertTrue(bridge.scheduler.mediaRequests.isEmpty())
    }

    @Test
    fun `revoked URI access becomes UNAVAILABLE, not a crash`() = runTest {
        val uri = "content://com.whatsapp.provider/media/denied"
        bridge.content.throwOnOpen[uri] = SecurityException("no grant")
        bridge.process(photo(uri))
        val media = mediaRow(lastMessageId())!!
        assertEquals(MediaAcquisitionStatus.UNAVAILABLE, media.acquisitionStatus)
    }

    @Test
    fun `IO error during copy becomes acquisition FAILED`() = runTest {
        val uri = "content://x/io"
        bridge.content.throwOnOpen[uri] = java.io.IOException("boom")
        bridge.process(photo(uri))
        assertEquals(MediaAcquisitionStatus.FAILED, mediaRow(lastMessageId())!!.acquisitionStatus)
    }

    @Test
    fun `text message creates no media row`() = runTest {
        bridge.process(Snapshots.groupMessaging(messages = listOf(SnapshotMessage("Just text", T0, "Ana"))))
        assertNull(mediaRow(lastMessageId()))
    }

    @Test
    fun `reposted conversation does not duplicate media`() = runTest {
        val uri = "content://x/dup"
        bridge.content.bytesByUri[uri] = imageBytes
        bridge.process(photo(uri))
        bridge.process(photo(uri)) // same message reposted
        val messageId = lastMessageId()
        assertEquals(1, bridge.db.mediaDao().countForMessage(messageId))
        assertEquals(1, bridge.db.mediaDao().countAll())
    }

    @Test
    fun `media capture can be disabled without affecting text capture`() = runTest {
        bridge.settings.setCaptureMedia(false)
        val outcome = bridge.process(photo("content://x/off").also { bridge.content.bytesByUri["content://x/off"] = imageBytes })
        assertEquals(0, (outcome as ProcessingOutcome.Captured).mediaDetected)
        assertNull(mediaRow(lastMessageId()))
        assertEquals("📷 Flooding near bridge", bridge.db.messageDao().getById(lastMessageId())!!.messageText)
    }

    @Test
    fun `empty file is treated as UNAVAILABLE and the local copy removed`() = runTest {
        val uri = "content://x/empty"
        bridge.content.bytesByUri[uri] = ByteArray(0)
        bridge.process(photo(uri))
        val media = mediaRow(lastMessageId())!!
        assertEquals(MediaAcquisitionStatus.UNAVAILABLE, media.acquisitionStatus)
        assertEquals(0L, bridge.mediaStore.totalBytes())
    }
}
