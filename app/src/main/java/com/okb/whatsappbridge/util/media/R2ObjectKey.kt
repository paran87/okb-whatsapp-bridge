package com.okb.whatsappbridge.util.media

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Deterministic, content-addressed R2 object key:
 * `whatsapp/{deviceId}/{yyyy}/{MM}/{dd}/{sha256}.{ext}`.
 *
 * Content-addressed (sha256) so identical media maps to one object; the date path is derived from the
 * capture time. No secrets are ever placed in the key. The same inputs always produce the same key,
 * so a retried upload targets the same object.
 */
object R2ObjectKey {
    private val DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    fun build(deviceId: String, sha256: String, extension: String, capturedAtMillis: Long, zone: ZoneId = ZoneId.of("UTC")): String {
        val datePath = Instant.ofEpochMilli(capturedAtMillis).atZone(zone).format(DATE)
        val ext = extension.lowercase().trim('.').ifEmpty { "bin" }
        return "whatsapp/$deviceId/$datePath/$sha256.$ext"
    }
}
