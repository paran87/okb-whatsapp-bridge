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
