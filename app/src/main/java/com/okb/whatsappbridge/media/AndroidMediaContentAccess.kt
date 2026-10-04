package com.okb.whatsappbridge.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Resolves content URIs that Android legitimately grants the bridge via a notification's
 * `MessagingStyle.Message` data. Uses only the public [android.content.ContentResolver]; it never
 * touches WhatsApp's private storage and requests no storage permission.
 */
class AndroidMediaContentAccess(context: Context) : MediaContentAccess {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    override fun isMediaAcquisitionSupported(): Boolean = true

    override fun queryMetadata(uri: String): MediaUriMetadata? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        val mime = runCatching { resolver.getType(parsed) }.getOrNull()
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }
        return MediaUriMetadata(displayName = name, mimeType = mime, sizeBytes = size)
    }

    override fun openStream(uri: String): InputStream {
        val parsed = Uri.parse(uri)
        return resolver.openInputStream(parsed) ?: throw FileNotFoundException("No stream for $uri")
    }
}
