package com.okb.whatsappbridge.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okb.whatsappbridge.domain.model.UploadStatus
import com.okb.whatsappbridge.domain.usecase.RecycleBinUseCase
import com.okb.whatsappbridge.domain.usecase.SyncOutcome
import com.okb.whatsappbridge.domain.usecase.SyncTrigger
import com.okb.whatsappbridge.fakes.FakeBridgeApi
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
class RecycleBinTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val api = FakeBridgeApi()
    private var now = T0 + 10_000
    private lateinit var bridge: TestBridge
    private lateinit var bin: RecycleBinUseCase

    private val water = SnapshotMessage("Water rising at Molino", T0, "Ana")
    private val road = SnapshotMessage("Road closed at Daang Hari", T0 + 60_000, "Ben")

    @Before
    fun setUp() = runTest {
        bridge = TestBridge(ApplicationProvider.getApplicationContext(), api, tmp.newFolder("media"), clock = { now })
        bin = RecycleBinUseCase(bridge.messages, bridge.media, bridge.scheduler, bridge.logger, clock = { now })
        bridge.settings.setMonitoringEnabled(true)
        bridge.settings.setBackendUrl("https://okb.test")
        bridge.groups.addAuthorizedGroup("OKB Monitoring", T0)
        bridge.process(Snapshots.groupMessaging(messages = listOf(water, road)))
    }

    @After
    fun tearDown() = bridge.db.close()

    private suspend fun visible() = bridge.messages.observeRecent(null, 50).first()
    private suspend fun inBin() = bridge.messages.observeRecycleBin(50).first()
    private suspend fun idOf(text: String) = (visible() + inBin()).first { it.messageText == text }.id

    @Test
    fun `moving to the bin hides the message, keeps it restorable, and pauses its upload`() = runTest {
        val id = idOf(water.text!!)
        assertEquals(1, bin.moveToBin(listOf(id)))

        assertEquals(listOf(road.text), visible().map { it.messageText })
        assertEquals(listOf(id), inBin().map { it.id })
        assertTrue(inBin().single().deletedAt != null)
        assertEquals(1, bridge.messages.observeRecycleBinCount().first())
        assertEquals("deleted message not counted as pending", 1, bridge.messages.observeQueueCounts().first().pending)

        // Only the message that is not in the bin is uploaded.
        val outcome = bridge.sync(SyncTrigger.MANUAL) as SyncOutcome.Completed
        assertEquals(1, outcome.uploaded)
        assertEquals(listOf(road.text), api.uploaded.map { it.messageText })
    }

    @Test
    fun `restoring brings it back and queues the upload again`() = runTest {
        val id = idOf(water.text!!)
        bin.moveToBin(listOf(id))
        bridge.scheduler.requests.clear()
        assertEquals(1, bin.restore(listOf(id)))

        assertEquals(2, visible().size)
        assertTrue(inBin().isEmpty())
        assertNull(visible().first { it.id == id }.deletedAt)
        assertEquals(1, bridge.scheduler.requests.size)
        bridge.sync(SyncTrigger.MANUAL)
        assertEquals(setOf(water.text, road.text), api.uploaded.map { it.messageText }.toSet())
    }

    @Test
    fun `a deleted message is not captured again when the notification is re-posted`() = runTest {
        val id = idOf(water.text!!)
        bin.moveToBin(listOf(id))
        bridge.process(Snapshots.groupMessaging(messages = listOf(water, road)))
        assertEquals(1, visible().size)

        bin.deleteForever(listOf(id))
        bridge.process(Snapshots.groupMessaging(messages = listOf(water, road)))
        assertEquals("tombstone keeps the fingerprint so it is not re-captured", 1, visible().size)
        assertTrue(inBin().isEmpty())
    }

    @Test
    fun `delete forever removes content and only acts on messages that are in the bin`() = runTest {
        val waterId = idOf(water.text!!)
        val roadId = idOf(road.text!!)
        bin.moveToBin(listOf(waterId))

        assertEquals(1, bin.deleteForever(listOf(waterId, roadId)))
        assertEquals("active message untouched", listOf(road.text), visible().map { it.messageText })
        val tombstone = bridge.db.messageDao().getById(waterId)!!
        assertNull(tombstone.messageText)
        assertNull(tombstone.senderName)
        assertNull(tombstone.groupName)
        assertTrue(tombstone.purgedAt != null)
        assertEquals("deleted forever cannot be restored", 0, bin.restore(listOf(waterId)))
    }

    @Test
    fun `delete forever removes the local media file and media row`() = runTest {
        val bytes = ByteArray(2048) { 7 }
        bridge.content.bytesByUri["content://x/photo"] = bytes
        bridge.content.metadataByUri["content://x/photo"] = MediaUriMetadata("p.jpg", "image/jpeg", bytes.size.toLong())
        bridge.process(
            Snapshots.groupMessaging(
                messages = listOf(SnapshotMessage("📷 Flood photo", T0 + 120_000, "Cora", dataUri = "content://x/photo", dataMimeType = "image/jpeg")),
                hasPicture = true,
            ),
        )
        val id = idOf("📷 Flood photo")
        val mediaRow = bridge.media.observeForMessage(id).first().single()
        val localPath = bridge.db.mediaDao().getById(mediaRow.id)!!.localPath!!
        assertTrue(File(localPath).exists())

        bin.moveToBin(listOf(id))
        val mediaOutcome = bridge.mediaSync(SyncTrigger.MANUAL) as SyncOutcome.Completed
        assertEquals("media of a deleted message is not uploaded", 0, mediaOutcome.uploaded)
        assertTrue(bridge.uploader.puts.isEmpty())

        bin.deleteForever(listOf(id))
        assertFalse(File(localPath).exists())
        assertNull(bridge.db.mediaDao().getById(mediaRow.id))
    }

    @Test
    fun `empty bin, and automatic removal after 30 days`() = runTest {
        val waterId = idOf(water.text!!)
        val roadId = idOf(road.text!!)
        bin.moveToBin(listOf(waterId))
        now += 10L * DAY
        bin.moveToBin(listOf(roadId))

        now += 21L * DAY // water: 31 days in the bin, road: 21 days
        assertEquals(1, bin.purgeExpired())
        assertEquals(listOf(roadId), inBin().map { it.id })

        assertEquals(1, bin.emptyBin())
        assertTrue(inBin().isEmpty())

        // Tombstones are dropped a week later.
        now += 8L * DAY
        bin.purgeExpired()
        assertNull(bridge.db.messageDao().getById(waterId))
        assertNull(bridge.db.messageDao().getById(roadId))
    }

    @Test
    fun `uploaded messages can be deleted locally too`() = runTest {
        bridge.sync(SyncTrigger.MANUAL)
        val id = idOf(water.text!!)
        assertEquals(UploadStatus.UPLOADED, visible().first { it.id == id }.uploadStatus)
        assertEquals(1, bin.moveToBin(listOf(id)))
        assertEquals(1, bin.deleteForever(listOf(id)))
        assertEquals(1, visible().size)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
