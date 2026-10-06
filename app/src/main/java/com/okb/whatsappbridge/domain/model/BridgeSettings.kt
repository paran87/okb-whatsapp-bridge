package com.okb.whatsappbridge.domain.model

/**
 * Non-secret, persisted configuration and activity timestamps.
 *
 * Secrets (device token) are not part of this model; they live in the Keystore-backed
 * [com.okb.whatsappbridge.util.security.SecretStore].
 */
data class BridgeSettings(
    val monitoringEnabled: Boolean = false,
    val syncPaused: Boolean = false,
    val backendUrl: String = "",
    val deviceName: String = "",
    val deviceRegisteredAt: Long? = null,
    val lastNotificationAt: Long? = null,
    val lastProcessedAt: Long? = null,
    val lastUploadSuccessAt: Long? = null,
    val lastUploadFailureAt: Long? = null,
    val lastUploadError: String? = null,
    val lastListenerConnectedAt: Long? = null,
    val lastListenerDisconnectedAt: Long? = null,
    val lastBackendCheckAt: Long? = null,
    val lastBackendCheckOk: Boolean? = null,
    val lastBackendCheckMessage: String? = null,
    val lastHealthCheckAt: Long? = null,
    val lastBootAt: Long? = null,
    // Phase 2 (media)
    val captureMedia: Boolean = true,
    val deleteLocalAfterUpload: Boolean = true,
    val lastMediaCaptureAt: Long? = null,
    val lastMediaUploadAt: Long? = null,
    val lastMediaError: String? = null,
    // WhatsApp report groups: two independent settings with different purposes.
    /** Group whose notifications are captured as field reports (blank = use the Groups allowlist). */
    val sourceGroupName: String = "",
    /** Group the consolidated report PDF is shared to. Never used to capture incoming reports. */
    val destinationGroupName: String = "",
) {
    val backendConfigured: Boolean get() = backendUrl.isNotBlank()
    val sourceGroupConfigured: Boolean get() = sourceGroupName.isNotBlank()
    val destinationGroupConfigured: Boolean get() = destinationGroupName.isNotBlank()
}

/** Keys of the key/value settings table. */
object SettingKeys {
    const val MONITORING_ENABLED = "monitoring_enabled"
    const val SYNC_PAUSED = "sync_paused"
    const val BACKEND_URL = "backend_url"
    const val DEVICE_NAME = "device_name"
    const val DEVICE_REGISTERED_AT = "device_registered_at"
    const val LAST_NOTIFICATION_AT = "last_notification_at"
    const val LAST_PROCESSED_AT = "last_processed_at"
    const val LAST_UPLOAD_SUCCESS_AT = "last_upload_success_at"
    const val LAST_UPLOAD_FAILURE_AT = "last_upload_failure_at"
    const val LAST_UPLOAD_ERROR = "last_upload_error"
    const val LAST_LISTENER_CONNECTED_AT = "last_listener_connected_at"
    const val LAST_LISTENER_DISCONNECTED_AT = "last_listener_disconnected_at"
    const val LAST_BACKEND_CHECK_AT = "last_backend_check_at"
    const val LAST_BACKEND_CHECK_OK = "last_backend_check_ok"
    const val LAST_BACKEND_CHECK_MESSAGE = "last_backend_check_message"
    const val LAST_HEALTH_CHECK_AT = "last_health_check_at"
    const val LAST_BOOT_AT = "last_boot_at"
    const val CAPTURE_MEDIA = "capture_media"
    const val DELETE_LOCAL_AFTER_UPLOAD = "delete_local_after_upload"
    const val LAST_MEDIA_CAPTURE_AT = "last_media_capture_at"
    const val LAST_MEDIA_UPLOAD_AT = "last_media_upload_at"
    const val LAST_MEDIA_ERROR = "last_media_error"
    const val SOURCE_GROUP_NAME = "source_group_name"
    const val DESTINATION_GROUP_NAME = "destination_group_name"
}
