package com.okb.whatsappbridge.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String? = null,
    val version: String? = null,
    val time: String? = null,
)

@Serializable
data class DeviceRegistrationRequest(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val appVersion: String,
    val osVersion: String,
    val manufacturer: String,
    val model: String,
    /** WhatsApp group reports are captured from (null = not configured). Older backends ignore it. */
    val sourceGroupName: String? = null,
    /** WhatsApp group consolidated reports are shared to (null = not configured). */
    val destinationGroupName: String? = null,
)

@Serializable
data class DeviceRegistrationResponse(
    val deviceId: String? = null,
    val registered: Boolean? = null,
    val message: String? = null,
)

/**
 * Body of `POST /api/v1/messages`.
 *
 * [clientMessageId] and [fingerprint] let the backend deduplicate retries; the same fingerprint is
 * also sent as the `Idempotency-Key` header.
 */
@Serializable
data class MessageUploadRequest(
    val deviceId: String,
    val clientMessageId: String,
    val fingerprint: String,
    val groupName: String?,
    val senderName: String?,
    val messageText: String?,
    /** ISO-8601 with offset, e.g. `2026-10-04T08:42:00+08:00`. */
    val timestamp: String,
    val timestampMillis: Long,
    val mediaType: String,
    val mediaStatus: String,
    val sourcePackage: String,
    val capturedAt: String,
    /**
     * Messaging platform (`whatsapp` / `viber`), derived from [sourcePackage]. Older backends ignore it;
     * the Phase 3 backend uses it as provenance and to keep platforms from deduplicating against each other.
     */
    val platform: String?,
)

@Serializable
data class MessageUploadResponse(
    val id: String? = null,
    val serverId: String? = null,
    val status: String? = null,
    val duplicate: Boolean? = null,
) {
    val resolvedServerId: String? get() = serverId ?: id
}

/** Body of `POST /api/v1/consolidated-reports/run-due`: keeps the backend's copy of this phone's groups current. */
@Serializable
data class ConsolidatedRunDueRequest(
    val sourceGroupName: String? = null,
    val destinationGroupName: String? = null,
)

/**
 * `POST /api/v1/consolidated-reports/run-due`: the phone's work. [textDeliveries] are sent automatically;
 * [deliveries] are PDFs offered to the operator. Older backends send neither the TEXT jobs nor the times.
 */
@Serializable
data class ConsolidatedRunDueResponse(
    val checkedAt: String? = null,
    val skipped: String? = null,
    /** Configuration problem the backend reports, e.g. no destination group configured. */
    val warning: String? = null,
    val deliveries: List<ConsolidatedDelivery> = emptyList(),
    val textDeliveries: List<TextDeliveryJob> = emptyList(),
    /** Next scheduled cut-off (ISO-8601): the phone sets an exact alarm just after it. */
    val nextCutoffAt: String? = null,
    /** Earliest retry of a failed automatic TEXT attempt (ISO-8601). */
    val nextRetryAt: String? = null,
)

/** One part of a consolidated TEXT report: the WhatsApp message and the reference printed in it. */
@Serializable
data class TextMessagePart(val text: String, val ref: String)

/** A consolidated TEXT report the phone sends automatically to [destinationGroup]. */
@Serializable
data class TextDeliveryJob(
    val id: String,
    val reportId: String,
    val kind: String? = null,
    val status: String? = null,
    val destinationGroup: String,
    val sourceGroup: String? = null,
    /** One automatic send per reporting period and group; also unique on the phone. */
    val dedupeKey: String,
    val parts: List<TextMessagePart> = emptyList(),
    val attempts: Int = 0,
    val maxAttempts: Int? = null,
    /** An earlier attempt was claimed but never reported: the chat is checked before anything is sent. */
    val previousAttemptUncertain: Boolean = false,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val reportCount: Int? = null,
    /** Why the last attempt failed (or "Cancelled from the Command Center"). */
    val errorMessage: String? = null,
    val cancelled: Boolean = false,
    val sentAt: String? = null,
) {
    val isTest: Boolean get() = kind == "test"
}

/** `POST …/text-deliveries/{id}/claim`. [reason] when not claimed: already_sent | failed | not_due | in_progress. */
@Serializable
data class TextClaimResponse(
    val claimed: Boolean = false,
    val reason: String? = null,
    val delivery: TextDeliveryJob? = null,
)

/** `POST …/text-deliveries/{id}/result`: sent | failed for attempt [attempt]. */
@Serializable
data class TextResultRequest(
    val state: String,
    val attempt: Int,
    val error: String? = null,
    val retryable: Boolean = true,
    val verification: String? = null,
    val sentAt: String? = null,
)

@Serializable
data class TextResultResponse(val id: String? = null)

/** One generated consolidated report PDF waiting to be handed to WhatsApp. */
@Serializable
data class ConsolidatedDelivery(
    val id: String,
    val kind: String? = null,
    val fileName: String,
    val caption: String,
    val destinationGroup: String? = null,
    /** WhatsApp group(s) the included field reports came from. */
    val sourceGroup: String? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val reportCount: Int? = null,
    /** API path of the PDF, relative to the backend base URL. */
    val pdfPath: String,
) {
    val isTest: Boolean get() = kind == "test"
}

/** Delivery acknowledgement: notified | opened | sent | not_sent | failed (with [error]). */
@Serializable
data class ConsolidatedDeliveryAck(val state: String, val error: String? = null)

@Serializable
data class ConsolidatedDeliveryAckResponse(
    val id: String? = null,
    val whatsappStatus: String? = null,
)
