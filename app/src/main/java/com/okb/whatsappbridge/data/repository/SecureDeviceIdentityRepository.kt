package com.okb.whatsappbridge.data.repository

import com.okb.whatsappbridge.domain.repository.DeviceIdentityRepository
import com.okb.whatsappbridge.util.security.SecretStore
import java.security.SecureRandom

/** Device id and token, both kept in the Keystore-backed [SecretStore]. */
class SecureDeviceIdentityRepository(
    private val store: SecretStore,
    private val random: SecureRandom = SecureRandom(),
) : DeviceIdentityRepository {

    @Volatile private var cachedDeviceId: String? = null

    override fun deviceId(): String {
        cachedDeviceId?.let { return it }
        synchronized(this) {
            cachedDeviceId?.let { return it }
            val id = store.get(KEY_DEVICE_ID) ?: generateDeviceId(random).also { store.put(KEY_DEVICE_ID, it) }
            cachedDeviceId = id
            return id
        }
    }

    override fun deviceToken(): String? = store.get(KEY_DEVICE_TOKEN)?.takeIf { it.isNotEmpty() }

    override fun setDeviceToken(token: String?) {
        store.put(KEY_DEVICE_TOKEN, token?.trim()?.takeIf { it.isNotEmpty() })
    }

    companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        private const val PREFIX = "OKB-ANDROID-"
        val DEVICE_ID_PATTERN = Regex("^OKB-ANDROID-[0-9A-F]{6}$")

        fun generateDeviceId(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(3).also(random::nextBytes)
            return PREFIX + bytes.joinToString("") { "%02X".format(it) }
        }
    }
}
