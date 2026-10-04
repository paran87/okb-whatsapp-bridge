package com.okb.whatsappbridge.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.data.local.database.BridgeDatabase
import com.okb.whatsappbridge.data.repository.RoomMessageRepository
import com.okb.whatsappbridge.data.repository.SecureDeviceIdentityRepository
import com.okb.whatsappbridge.domain.model.MediaStatus
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.domain.repository.NewCapturedMessage
import com.okb.whatsappbridge.domain.repository.SaveResult
import com.okb.whatsappbridge.util.fingerprint.MessageFingerprint
import com.okb.whatsappbridge.util.security.InMemorySecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MessageDaoTest {

    private lateinit var db: BridgeDatabase
    private lateinit var repo: RoomMessageRepository
    private var ids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BridgeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomMessageRepository(
            db.messageDao(), db.settingsDao(), SecureDeviceIdentityRepository(InMemorySecretStore()),
            idGenerator = { "msg-${++ids}" },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun message(text: String, ts: Long) = NewCapturedMessage(
        groupName = "OKB Monitoring",
        senderName = "Juan",
        messageText = text,
        timestamp = ts,
        mediaType = MediaType.TEXT,
        mediaStatus = MediaStatus.NONE,
        fingerprint = MessageFingerprint.compute("OKB Monitoring", "Juan", text, ts),
        packageName = "com.whatsapp",
        notificationKey = "key",
        capturedAt = ts + 10,
    )

    @Test
    fun `message and queue entry are stored atomically and duplicates are rejected`() = runTest {
        assertEquals(SaveResult.INSERTED, repo.saveCaptured(message("Flood", 1000)).result)
        assertEquals(SaveResult.DUPLICATE, repo.saveCaptured(message("Flood", 1000)).result)
        assertEquals(1, db.messageDao().countAll())
        assertEquals(1, db.uploadQueueDao().count())
        val stored = db.messageDao().getById("msg-1")!!
        assertEquals(UploadStatus.PENDING_UPLOAD.name, stored.uploadStatus)
        assertTrue(stored.deviceId.startsWith("OKB-ANDROID-"))
    }

    @Test
    fun `upload lifecycle updates status, attempts and queue`() = runTest {
        repo.saveCaptured(message("A", 1000))
        repo.saveCaptured(message("B", 2000))

        val batch = repo.nextUploadBatch(emptySet(), includeFailed = false, limit = 10)
        assertEquals(listOf("msg-1", "msg-2"), batch.map { it.id })

        repo.markUploading("msg-1")
        repo.markRetrying("msg-1", "offline", null, 3000)
        assertEquals(1, db.uploadQueueDao().get("msg-1")!!.attemptCount)
        assertEquals(UploadStatus.RETRYING.name, db.messageDao().getById("msg-1")!!.uploadStatus)

        repo.markUploaded("msg-1", "srv-9", 4000)
        val uploaded = db.messageDao().getById("msg-1")!!
        assertEquals(UploadStatus.UPLOADED.name, uploaded.uploadStatus)
        assertEquals("srv-9", uploaded.serverId)
        assertNull(uploaded.lastError)
        assertNull(db.uploadQueueDao().get("msg-1"))

        repo.markFailed("msg-2", "HTTP 400", 400, 5000)
        assertTrue(repo.nextUploadBatch(emptySet(), includeFailed = false, limit = 10).isEmpty())
        assertEquals(listOf("msg-2"), repo.nextUploadBatch(emptySet(), includeFailed = true, limit = 10).map { it.id })
        assertTrue(repo.nextUploadBatch(setOf("msg-2"), includeFailed = true, limit = 10).isEmpty())

        val counts = repo.observeQueueCounts().first()
        assertEquals(1, counts.uploaded)
        assertEquals(1, counts.failed)
    }

    @Test
    fun `failed messages can be reset and interrupted uploads are recovered`() = runTest {
        repo.saveCaptured(message("A", 1000))
        repo.saveCaptured(message("B", 2000))
        repo.markFailed("msg-1", "HTTP 401", 401, 3000)
        repo.markUploading("msg-2")

        assertEquals(1, repo.recoverInterruptedUploads())
        assertEquals(UploadStatus.RETRYING.name, db.messageDao().getById("msg-2")!!.uploadStatus)

        assertEquals(1, repo.resetFailedToPending())
        assertEquals(UploadStatus.PENDING_UPLOAD.name, db.messageDao().getById("msg-1")!!.uploadStatus)
        assertEquals(0, db.uploadQueueDao().get("msg-1")!!.attemptCount)
        assertEquals(2, repo.countUploadable(includeFailed = false))
    }

    @Test
    fun `failed messages stop being retried automatically after the attempt cap`() = runTest {
        repo.saveCaptured(message("A", 1000))
        repeat(20) { repo.markFailed("msg-1", "HTTP 422", 422, 3000L + it) }
        assertEquals(0, repo.countUploadable(includeFailed = true))
        assertEquals(1, repo.resetFailedToPending())
        assertEquals(1, repo.countUploadable(includeFailed = true))
    }

    @Test
    fun `repairQueue restores missing queue entries`() = runTest {
        repo.saveCaptured(message("A", 1000))
        db.openHelper.writableDatabase.execSQL("DELETE FROM upload_queue")
        assertEquals(0, repo.countUploadable(includeFailed = false))
        repo.repairQueue(5000)
        assertNotNull(db.uploadQueueDao().get("msg-1"))
        assertEquals(1, repo.countUploadable(includeFailed = false))
    }

    @Test
    fun `retention removes only old uploaded messages`() = runTest {
        repo.saveCaptured(message("A", 1000))
        repo.saveCaptured(message("B", 2000))
        repo.markUploaded("msg-1", null, 10_000)
        assertEquals(1, repo.deleteUploadedBefore(20_000))
        assertNull(db.messageDao().getById("msg-1"))
        assertNotNull(db.messageDao().getById("msg-2"))
    }

    @Test
    fun `recent messages and daily count come from the database`() = runTest {
        repo.saveCaptured(message("A", 1000))
        repo.saveCaptured(message("B", 2000))
        val recent = repo.observeRecent(null, 10).first()
        assertEquals(listOf("B", "A"), recent.map { it.messageText })
        assertEquals(2, repo.observeCapturedSince(0).first())
        assertEquals(0, repo.observeCapturedSince(1_000_000).first())
        assertEquals(2000L, repo.observeLatestMessageTimestamp().first())
        assertEquals(1, repo.observeRecent(UploadStatus.PENDING_UPLOAD, 1).first().size)
        assertTrue(repo.isDatabaseHealthy())
    }
}
