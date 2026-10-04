package com.okb.whatsappbridge.data.remote.api

/** Outcome of a backend call, classified so the sync engine can decide between retry and failure. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T, val httpCode: Int) : ApiResult<T>

    data class HttpError(val httpCode: Int, val message: String) : ApiResult<Nothing> {
        /** Timeouts, rate limiting and server errors are transient. */
        val retryable: Boolean get() = httpCode == 408 || httpCode == 425 || httpCode == 429 || httpCode >= 500
        val authError: Boolean get() = httpCode == 401 || httpCode == 403
    }

    /** No connectivity, DNS failure, TLS failure, timeout… always retryable. */
    data class NetworkError(val message: String) : ApiResult<Nothing>

    /** Invalid or missing configuration (e.g. malformed backend URL). Not retryable until fixed. */
    data class ConfigurationError(val message: String) : ApiResult<Nothing>
}
