package com.okb.whatsappbridge.ui

import com.okb.whatsappbridge.domain.model.MediaAcquisitionStatus
import com.okb.whatsappbridge.domain.model.MediaSummary
import com.okb.whatsappbridge.domain.model.MediaType
import com.okb.whatsappbridge.domain.model.MediaUploadStatus
import com.okb.whatsappbridge.ui.components.StatusLevel

/** Presentation mapping for media acquisition/upload state. Kept beside the message mapping. */
object MediaPresentation {

    /** Short chip label shown next to a message. */
    fun chipLabel(summary: MediaSummary): String = when (summary.acquisitionStatus) {
        MediaAcquisitionStatus.AVAILABLE -> summary.mediaType.label()
        MediaAcquisitionStatus.UNAVAILABLE -> "${summary.mediaType.label()} · UNAVAILABLE"
        MediaAcquisitionStatus.FAILED -> "${summary.mediaType.label()} · FAILED"
        MediaAcquisitionStatus.ACQUIRING -> "${summary.mediaType.label()} · ACQUIRING"
        MediaAcquisitionStatus.DETECTED -> "${summary.mediaType.label()} · DETECTED"
    }

    fun chipLevel(summary: MediaSummary): StatusLevel = when (summary.acquisitionStatus) {
        MediaAcquisitionStatus.AVAILABLE -> when (summary.uploadStatus) {
            MediaUploadStatus.UPLOADED -> StatusLevel.OK
            MediaUploadStatus.FAILED -> StatusLevel.ERROR
            MediaUploadStatus.RETRYING -> StatusLevel.WARNING
            else -> StatusLevel.INFO
        }
        MediaAcquisitionStatus.UNAVAILABLE -> StatusLevel.NEUTRAL
        MediaAcquisitionStatus.FAILED -> StatusLevel.ERROR
        else -> StatusLevel.INFO
    }

    fun acquisition(status: MediaAcquisitionStatus): Presented = when (status) {
        MediaAcquisitionStatus.DETECTED -> Presented(StatusLevel.INFO, "Detected")
        MediaAcquisitionStatus.ACQUIRING -> Presented(StatusLevel.INFO, "Acquiring")
        MediaAcquisitionStatus.AVAILABLE -> Presented(StatusLevel.OK, "Available")
        MediaAcquisitionStatus.UNAVAILABLE -> Presented(StatusLevel.NEUTRAL, "Unavailable")
        MediaAcquisitionStatus.FAILED -> Presented(StatusLevel.ERROR, "Failed")
    }

    fun upload(status: MediaUploadStatus): Presented = when (status) {
        MediaUploadStatus.PENDING -> Presented(StatusLevel.INFO, "Pending")
        MediaUploadStatus.UPLOADING -> Presented(StatusLevel.INFO, "Uploading")
        MediaUploadStatus.UPLOADED -> Presented(StatusLevel.OK, "Uploaded")
        MediaUploadStatus.RETRYING -> Presented(StatusLevel.WARNING, "Retrying")
        MediaUploadStatus.FAILED -> Presented(StatusLevel.ERROR, "Failed")
    }
}

fun MediaType.label(): String = when (this) {
    MediaType.TEXT -> "TEXT"
    MediaType.IMAGE -> "IMAGE"
    MediaType.VIDEO -> "VIDEO"
    MediaType.AUDIO -> "AUDIO"
    MediaType.DOCUMENT -> "DOCUMENT"
    MediaType.LOCATION -> "LOCATION"
    MediaType.STICKER -> "STICKER"
    MediaType.UNKNOWN -> "MEDIA"
}
