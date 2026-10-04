package com.okb.whatsappbridge.media

import java.io.InputStream

/** Metadata the OS exposes about a content URI (all best-effort). */
data class MediaUriMetadata(
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
)

/**
 * Opens media that Android legitimately grants the bridge (a content URI supplied on a notification).
 *
 * The bridge never reads WhatsApp's private sandbox; this only resolves URIs the OS has already
 * authorized. Any failure (no grant, revoked, missing file) surfaces as a null/throw and becomes a
 * clean MEDIA_UNAVAILABLE state upstream.
 */
interface MediaContentAccess {
    /** Returns true if the device/app has any capability to acquire notification-provided media. */
    fun isMediaAcquisitionSupported(): Boolean

    fun queryMetadata(uri: String): MediaUriMetadata?

    /** Opens an input stream for [uri]; throws (SecurityException/IOException) if not legitimately readable. */
    fun openStream(uri: String): InputStream
}
