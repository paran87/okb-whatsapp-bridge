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

/** `POST /api/v1/consolidated-reports/run-due`: deliveries the phone should offer to the operator. */
@Serializable
data class ConsolidatedRunDueResponse(
    val checkedAt: String? = null,
    val skipped: String? = null,
    /** Configuration problem the backend reports, e.g. no destination group configured. */
    val warning: String? = null,
    val deliveries: List<ConsolidatedDelivery> = emptyList(),
)

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
