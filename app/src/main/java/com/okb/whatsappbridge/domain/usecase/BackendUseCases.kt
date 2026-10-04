package com.okb.whatsappbridge.domain.usecase

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.BridgeApi
import com.okb.whatsappbridge.data.remote.dto.DeviceRegistrationRequest
import com.okb.whatsappbridge.domain.repository.DeviceIdentityRepository
import com.okb.whatsappbridge.domain.repository.SettingsRepository
import com.okb.whatsappbridge.util.logging.BridgeLogger

data class BackendCheckResult(val ok: Boolean, val message: String)

/** Device facts sent at registration. */
data class DeviceInfo(
    val appVersion: String,
    val osVersion: String,
    val manufacturer: String,
    val model: String,
)

class BackendUseCases(
    private val settings: SettingsRepository,
    private val identity: DeviceIdentityRepository,
    private val api: BridgeApi,
    private val deviceInfo: DeviceInfo,
    private val logger: BridgeLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** `GET /api/v1/health`; the result is persisted and shown as the backend status. */
    suspend fun checkHealth(): BackendCheckResult {
        val current = settings.current()
        if (!current.backendConfigured) return BackendCheckResult(false, "Backend URL not configured")
        val result = when (val r = api.health(config(current.backendUrl))) {
            is ApiResult.Success -> BackendCheckResult(true, "Connected" + (r.value.version?.let { " (v$it)" } ?: ""))
            is ApiResult.HttpError -> BackendCheckResult(false, r.message)
            is ApiResult.NetworkError -> BackendCheckResult(false, "Unreachable: ${r.message}")
            is ApiResult.ConfigurationError -> BackendCheckResult(false, r.message)
        }
        settings.recordBackendCheck(clock(), result.ok, result.message)
        return result
    }

    /** `POST /api/v1/devices/register` */
    suspend fun registerDevice(): BackendCheckResult {
        val current = settings.current()
        if (!current.backendConfigured) return BackendCheckResult(false, "Backend URL not configured")
        val request = DeviceRegistrationRequest(
            deviceId = identity.deviceId(),
            deviceName = current.deviceName.ifBlank { "${deviceInfo.manufacturer} ${deviceInfo.model}" },
            appVersion = deviceInfo.appVersion,
            osVersion = deviceInfo.osVersion,
            manufacturer = deviceInfo.manufacturer,
            model = deviceInfo.model,
        )
        val result = when (val r = api.registerDevice(config(current.backendUrl), request)) {
            is ApiResult.Success -> {
                settings.setDeviceRegisteredAt(clock())
                BackendCheckResult(true, "Device registered")
            }
            is ApiResult.HttpError -> BackendCheckResult(false, r.message)
            is ApiResult.NetworkError -> BackendCheckResult(false, "Unreachable: ${r.message}")
            is ApiResult.ConfigurationError -> BackendCheckResult(false, r.message)
        }
        logger.info("Backend", "Device registration: ${result.message}")
        return result
    }

    private fun config(baseUrl: String) = BackendConfig(baseUrl, identity.deviceId(), identity.deviceToken())
}
