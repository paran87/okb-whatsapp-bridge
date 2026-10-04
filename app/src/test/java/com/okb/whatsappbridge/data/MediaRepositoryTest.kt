package com.okb.whatsappbridge.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.repository.NewMediaAttachment
import com.okb.whatsappbridge.fakes.FakeBridgeApi
import com.okb.whatsappbridge.fakes.Snapshots.T0
import com.okb.whatsappbridge.fakes.TestBridge
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
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MediaRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var bridge: TestBridge

    @Before
    fun setUp() = runTest {
        bridge = TestBridge(ApplicationProvider.getApplicationContext(), FakeBridgeApi(), tmp.newFolder("media"))
        // A message row is needed for the media FK.
        bridge.settings.setMonitoringEnabled(true)
        bridge.groups.addAuthorizedGroup("G", T0)
    }

    @After
    fun tearDown() = bridge.db.close()

    private suspend fun messageId(fp: String): String {
        bridge.messages.saveCaptured(
            com.okb.whatsappbridge.domain.repository.NewCapturedMessage(
                "G", "Ana", "hi", T0, MediaType.IMAGE, com.okb.whatsappbridge.domain.model.MediaStatus.UNAVAILABLE,
                fp, "com.whatsapp", "key", T0,
            ),
        )
        return bridge.db.messageDao().getById(bridge.messages.observeRecent(null, 1).first().first().id)!!.id
    }

    private suspend fun acquired(mid: String, sha: String, size: Long = 100): String {
        val id = bridge.media.createDetected(NewMediaAttachment(mid, "OKB-ANDROID-A82F19", "G", "Ana", MediaType.IMAGE, "image/jpeg", "p.jpg", T0))!!
        // Write a real file inside the store's directory so storageUsedBytes() sees it.
        val stored = bridge.mediaStore.store(id, "jpg", ByteArrayInputStream(ByteArray(size.toInt())))
        bridge.media.markAvailableAndEnqueue(id, stored.file.absolutePath, size, sha, "image/jpeg", "p.jpg", T0)
        return id
    }

    @Test
    fun `available media is queued and content dedupe finds an uploaded twin`() = runTest {
        val sha = "a".repeat(64)
        val id1 = acquired(messageId("f1"), sha)
        assertEquals(1, bridge.media.countUploadable(includeFailed = false))
        assertNull(bridge.media.findUploadedBySha256(sha))

        bridge.media.markUploaded(id1, "whatsapp/k1", "etag1", "r2://b/k1", T0 + 1)
        val twin = bridge.media.findUploadedBySha256(sha)
        assertNotNull(twin)
        assertEquals("whatsapp/k1", twin!!.objectKey)
        assertEquals(0, bridge.media.countUploadable(includeFailed = false))
    }

    @Test
    fun `failed media can be reset, interrupted uploads recovered, queue repaired`() = runTest {
        val id = acquired(messageId("f2"), "b".repeat(64))
        bridge.media.markUploadFailed(id, "HTTP 400", T0 + 1)
        assertEquals(0, bridge.media.countUploadable(includeFailed = false))
        assertEquals(1, bridge.media.countUploadable(includeFailed = true))
        assertEquals(1, bridge.media.resetFailedToPending(T0 + 2))
        assertEquals(1, bridge.media.countUploadable(includeFailed = false))

        bridge.media.markUploading(id, T0 + 3)
        assertEquals(1, bridge.media.recoverInterruptedUploads(T0 + 4))

        // Deleting the queue row and repairing restores it.
        bridge.db.openHelper.writableDatabase.execSQL("DELETE FROM media_upload_queue")
        assertEquals(0, bridge.media.countUploadable(includeFailed = false))
        bridge.media.repairQueue(T0 + 5)
        assertEquals(1, bridge.media.countUploadable(includeFailed = false))
    }

    @Test
    fun `uploaded local files are cleaned up only after confirmed upload`() = runTest {
        val mid = messageId("f3")
        val id = acquired(mid, "c".repeat(64), size = 200)
        assertEquals(200L, bridge.media.storageUsedBytes())
        // Not uploaded yet: cleanup removes nothing (never delete a queued file).
        assertEquals(0, bridge.media.cleanupUploadedLocalFiles(T0 + 1000, T0 + 1000))
        assertEquals(200L, bridge.media.storageUsedBytes())

        bridge.media.markUploaded(id, "k", "e", "r2://b/k", T0 + 10)
        val removed = bridge.media.cleanupUploadedLocalFiles(cutoff = T0 + 1000, at = T0 + 1000)
        assertEquals(1, removed)
        assertEquals(0L, bridge.media.storageUsedBytes())
        // The media row survives (localPath cleared); only the local copy is gone.
        val row = bridge.media.observeForMessage(mid).first().single()
        assertFalse(row.hasLocalFile)
        assertEquals(com.okb.whatsappbridge.domain.model.MediaUploadStatus.UPLOADED, row.uploadStatus)
    }

    @Test
    fun `media counts reflect acquisition and upload state`() = runTest {
        val available = acquired(messageId("f4"), "d".repeat(64))
        // One UNAVAILABLE row.
        val unavailId = bridge.media.createDetected(NewMediaAttachment(messageId("f5"), "OKB-ANDROID-A82F19", "G", "Ana", MediaType.VIDEO, null, null, T0))!!
        bridge.media.markUnavailable(unavailId, "no file", T0)

        val counts = bridge.media.observeMediaCounts().first()
        assertEquals(1, counts.available)
        assertEquals(1, counts.unavailable)
        assertEquals(1, counts.pendingUpload)
        bridge.media.markUploaded(available, "k", "e", "r", T0 + 1)
        assertEquals(1, bridge.media.observeMediaCounts().first().uploaded)
    }

    @Test
    fun `createDetected is idempotent per message`() = runTest {
        val mid = messageId("f6")
        assertNotNull(bridge.media.createDetected(NewMediaAttachment(mid, "d", "G", "A", MediaType.IMAGE, null, null, T0)))
        assertNull(bridge.media.createDetected(NewMediaAttachment(mid, "d", "G", "A", MediaType.IMAGE, null, null, T0)))
    }

    @Test
    fun `store streams and hashes consistently`() = runTest {
        val stored = bridge.mediaStore.store("x", "bin", ByteArrayInputStream(ByteArray(1234) { 7 }))
        assertEquals(1234L, stored.sizeBytes)
        assertTrue(stored.sha256.matches(Regex("[0-9a-f]{64}")))
    }
}
