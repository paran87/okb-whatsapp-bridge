package com.okb.whatsappbridge.data.repository

import com.okb.whatsappbridge.data.local.dao.SettingsDao
import com.okb.whatsappbridge.data.local.entity.BridgeSettingsEntity
import com.okb.whatsappbridge.domain.model.BridgeSettings
import com.okb.whatsappbridge.domain.model.SettingKeys
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Settings persisted in Room so they survive app restarts, process death and device reboots. */
class RoomSettingsRepository(
    private val dao: SettingsDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : SettingsRepository {

    override val settings: Flow<BridgeSettings> =
        dao.observeAll().map { rows -> rows.toSettings() }.distinctUntilChanged()

    override suspend fun current(): BridgeSettings = dao.getAll().toSettings()

    override suspend fun setMonitoringEnabled(enabled: Boolean) = put(SettingKeys.MONITORING_ENABLED, enabled.toString())
    override suspend fun setSyncPaused(paused: Boolean) = put(SettingKeys.SYNC_PAUSED, paused.toString())
    override suspend fun setBackendUrl(url: String) = put(SettingKeys.BACKEND_URL, url.trim())
    override suspend fun setDeviceName(name: String) = put(SettingKeys.DEVICE_NAME, name.trim())
    override suspend fun setDeviceRegisteredAt(at: Long?) = put(SettingKeys.DEVICE_REGISTERED_AT, at?.toString())
    override suspend fun recordNotificationReceived(at: Long) = put(SettingKeys.LAST_NOTIFICATION_AT, at.toString())
    override suspend fun recordProcessed(at: Long) = put(SettingKeys.LAST_PROCESSED_AT, at.toString())
    override suspend fun recordUploadSuccess(at: Long) = put(SettingKeys.LAST_UPLOAD_SUCCESS_AT, at.toString())

    override suspend fun recordUploadFailure(at: Long, error: String) = putAll(
        SettingKeys.LAST_UPLOAD_FAILURE_AT to at.toString(),
        SettingKeys.LAST_UPLOAD_ERROR to error.take(300),
    )

    override suspend fun recordListenerConnected(at: Long) = put(SettingKeys.LAST_LISTENER_CONNECTED_AT, at.toString())
    override suspend fun recordListenerDisconnected(at: Long) = put(SettingKeys.LAST_LISTENER_DISCONNECTED_AT, at.toString())

    override suspend fun recordBackendCheck(at: Long, ok: Boolean, message: String) = putAll(
        SettingKeys.LAST_BACKEND_CHECK_AT to at.toString(),
        SettingKeys.LAST_BACKEND_CHECK_OK to ok.toString(),
        SettingKeys.LAST_BACKEND_CHECK_MESSAGE to message.take(300),
    )

    override suspend fun recordHealthCheck(at: Long) = put(SettingKeys.LAST_HEALTH_CHECK_AT, at.toString())
    override suspend fun recordBoot(at: Long) = put(SettingKeys.LAST_BOOT_AT, at.toString())
    override suspend fun setCaptureMedia(enabled: Boolean) = put(SettingKeys.CAPTURE_MEDIA, enabled.toString())
    override suspend fun setDeleteLocalAfterUpload(enabled: Boolean) = put(SettingKeys.DELETE_LOCAL_AFTER_UPLOAD, enabled.toString())
    override suspend fun recordMediaCapture(at: Long) = put(SettingKeys.LAST_MEDIA_CAPTURE_AT, at.toString())
    override suspend fun recordMediaUploadSuccess(at: Long) = put(SettingKeys.LAST_MEDIA_UPLOAD_AT, at.toString())
    override suspend fun recordMediaFailure(at: Long, error: String) = put(SettingKeys.LAST_MEDIA_ERROR, error.take(300))
    override suspend fun setSourceGroupName(name: String) = put(SettingKeys.SOURCE_GROUP_NAME, name.trim())
    override suspend fun setDestinationGroupName(name: String) = put(SettingKeys.DESTINATION_GROUP_NAME, name.trim())

    private suspend fun put(key: String, value: String?) = dao.put(BridgeSettingsEntity(key, value, clock()))

    private suspend fun putAll(vararg values: Pair<String, String?>) {
        val now = clock()
        dao.putAll(values.map { (k, v) -> BridgeSettingsEntity(k, v, now) })
    }

    private fun List<BridgeSettingsEntity>.toSettings(): BridgeSettings {
        val map = associate { it.key to it.value }
        fun bool(key: String) = map[key]?.toBooleanStrictOrNull()
        fun long(key: String) = map[key]?.toLongOrNull()
        return BridgeSettings(
            monitoringEnabled = bool(SettingKeys.MONITORING_ENABLED) ?: false,
            syncPaused = bool(SettingKeys.SYNC_PAUSED) ?: false,
            backendUrl = map[SettingKeys.BACKEND_URL].orEmpty(),
            deviceName = map[SettingKeys.DEVICE_NAME].orEmpty(),
            deviceRegisteredAt = long(SettingKeys.DEVICE_REGISTERED_AT),
            lastNotificationAt = long(SettingKeys.LAST_NOTIFICATION_AT),
            lastProcessedAt = long(SettingKeys.LAST_PROCESSED_AT),
            lastUploadSuccessAt = long(SettingKeys.LAST_UPLOAD_SUCCESS_AT),
            lastUploadFailureAt = long(SettingKeys.LAST_UPLOAD_FAILURE_AT),
            lastUploadError = map[SettingKeys.LAST_UPLOAD_ERROR],
            lastListenerConnectedAt = long(SettingKeys.LAST_LISTENER_CONNECTED_AT),
            lastListenerDisconnectedAt = long(SettingKeys.LAST_LISTENER_DISCONNECTED_AT),
            lastBackendCheckAt = long(SettingKeys.LAST_BACKEND_CHECK_AT),
            lastBackendCheckOk = bool(SettingKeys.LAST_BACKEND_CHECK_OK),
            lastBackendCheckMessage = map[SettingKeys.LAST_BACKEND_CHECK_MESSAGE],
            lastHealthCheckAt = long(SettingKeys.LAST_HEALTH_CHECK_AT),
            lastBootAt = long(SettingKeys.LAST_BOOT_AT),
            captureMedia = bool(SettingKeys.CAPTURE_MEDIA) ?: true,
            deleteLocalAfterUpload = bool(SettingKeys.DELETE_LOCAL_AFTER_UPLOAD) ?: true,
            lastMediaCaptureAt = long(SettingKeys.LAST_MEDIA_CAPTURE_AT),
            lastMediaUploadAt = long(SettingKeys.LAST_MEDIA_UPLOAD_AT),
            lastMediaError = map[SettingKeys.LAST_MEDIA_ERROR],
            sourceGroupName = map[SettingKeys.SOURCE_GROUP_NAME].orEmpty(),
            destinationGroupName = map[SettingKeys.DESTINATION_GROUP_NAME].orEmpty(),
        )
    }
}
