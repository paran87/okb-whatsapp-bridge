package com.okb.whatsappbridge.util

import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.util.media.FileSystemMediaFileStore
import com.okb.whatsappbridge.util.media.MediaHashing
import com.okb.whatsappbridge.util.media.MediaMimeTypes
import com.okb.whatsappbridge.util.media.R2ObjectKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class MediaUtilTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun expectedSha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `streaming sha256 matches a one-shot digest`() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        assertEquals(expectedSha(bytes), MediaHashing.sha256(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `file store copies, sizes and hashes in one streaming pass`() {
        val store = FileSystemMediaFileStore(tmp.newFolder("media"))
        val bytes = ByteArray(1_000_000) { (it % 255).toByte() }
        val stored = store.store("abc", "jpg", ByteArrayInputStream(bytes))
        assertTrue(stored.file.exists())
        assertTrue(stored.file.name.endsWith(".jpg"))
        assertEquals(bytes.size.toLong(), stored.sizeBytes)
        assertEquals(expectedSha(bytes), stored.sha256)
        assertEquals(bytes.size.toLong(), store.totalBytes())
        assertTrue(store.delete(stored.file.absolutePath))
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun `large file is streamed from disk, not read into memory`() {
        // A 40 MB source file is streamed through the store in 64 KB chunks and hashed in one pass.
        val store = FileSystemMediaFileStore(tmp.newFolder("big"))
        val source = tmp.newFile("source.mp4")
        val digest = MessageDigest.getInstance("SHA-256")
        source.outputStream().buffered().use { out ->
            val chunk = ByteArray(1_000_000) { (it % 97).toByte() }
            repeat(40) { out.write(chunk); digest.update(chunk) }
        }
        val stored = source.inputStream().use { store.store("big", "mp4", it) }
        assertEquals(40_000_000L, stored.sizeBytes)
        assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, stored.sha256)
        assertEquals(stored.sha256, MediaHashing.sha256(stored.file))
    }

    @Test
    fun `extensions are derived from mime, then filename, then media type`() {
        assertEquals("jpg", MediaMimeTypes.extension("image/jpeg", MediaType.IMAGE, null))
        assertEquals("mp4", MediaMimeTypes.extension("video/mp4", MediaType.VIDEO, null))
        assertEquals("pdf", MediaMimeTypes.extension(null, MediaType.DOCUMENT, "report.pdf"))
        assertEquals("mp4", MediaMimeTypes.extension(null, MediaType.VIDEO, null))
        assertEquals("bin", MediaMimeTypes.extension(null, MediaType.DOCUMENT, null))
        assertEquals("jpg", MediaMimeTypes.extension("image/jpeg; charset=binary", MediaType.IMAGE, null))
        assertEquals("image/jpeg", MediaMimeTypes.normalizeMime("image/jpeg; x=y"))
        org.junit.Assert.assertNull(MediaMimeTypes.normalizeMime("notamime"))
    }

    @Test
    fun `object key is deterministic, content-addressed and date-pathed`() {
        val at = 1_791_073_320_000L // 2026-10-04 UTC
        val key = R2ObjectKey.build("OKB-ANDROID-A82F19", "a".repeat(64), "jpg", at)
        assertEquals("whatsapp/OKB-ANDROID-A82F19/2026/10/04/${"a".repeat(64)}.jpg", key)
        assertEquals(key, R2ObjectKey.build("OKB-ANDROID-A82F19", "a".repeat(64), "jpg", at))
        assertNotEquals(key, R2ObjectKey.build("OKB-ANDROID-A82F19", "b".repeat(64), "jpg", at))
    }
}
