package com.okb.whatsappbridge.util.security

/** Storage for sensitive values (device token, credentials). Implementations must never log values. */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** In-memory implementation used by tests. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    @Synchronized
    override fun get(key: String): String? = values[key]

    @Synchronized
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
