package com.okb.whatsappbridge.util.media

import java.io.File
import java.io.InputStream

/** Result of copying media into app-controlled storage. */
data class StoredMedia(val file: File, val sizeBytes: Long, val sha256: String)

/**
 * App-controlled local media storage. The original WhatsApp file is never depended upon once copied.
 * Copying streams the input so large files never load into memory; the SHA-256 is computed during the
 * same streaming pass.
 */
interface MediaFileStore {
    /** Copies [input] into storage under [id], returning the stored file with its size and hash. */
    fun store(id: String, extension: String, input: InputStream): StoredMedia
    fun delete(path: String): Boolean
    /** Total bytes currently used by stored media. */
    fun totalBytes(): Long
    /** Free space available on the storage volume, or null if unknown. */
    fun usableSpaceBytes(): Long?
}

/** Filesystem implementation writing to a private directory (app sandbox). */
class FileSystemMediaFileStore(private val baseDir: File) : MediaFileStore {

    init {
        baseDir.mkdirs()
    }

    override fun store(id: String, extension: String, input: InputStream): StoredMedia {
        val target = File(baseDir, "$id.$extension")
        val tmp = File(baseDir, "$id.$extension.part")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        tmp.outputStream().buffered().use { out ->
            val buffer = ByteArray(64 * 1024)
            input.use { src ->
                while (true) {
                    val read = src.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    size += read
                }
            }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return StoredMedia(target, size, sha)
    }

    override fun delete(path: String): Boolean = runCatching { File(path).takeIf { it.exists() }?.delete() ?: false }
        .getOrDefault(false)

    override fun totalBytes(): Long =
        baseDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    @android.annotation.SuppressLint("UsableSpace")
    override fun usableSpaceBytes(): Long? = runCatching { baseDir.usableSpace }.getOrNull()
}
