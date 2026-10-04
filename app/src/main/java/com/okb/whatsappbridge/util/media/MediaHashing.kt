package com.okb.whatsappbridge.util.media

import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Streaming SHA-256 so large videos are never loaded into memory. */
object MediaHashing {
    private const val BUFFER = 64 * 1024

    /** Hashes a file by streaming it in [BUFFER]-sized chunks. */
    fun sha256(file: File): String = file.inputStream().buffered().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        DigestInputStream(input, digest).use { stream ->
            while (stream.read(buffer) != -1) { /* digest updated as a side effect */ }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
