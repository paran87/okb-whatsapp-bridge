package com.okb.whatsappbridge.data

import com.okb.whatsappbridge.data.remote.api.ApiResult
import com.okb.whatsappbridge.data.remote.api.BackendConfig
import com.okb.whatsappbridge.data.remote.api.OkHttpBridgeApi
import com.okb.whatsappbridge.data.remote.dto.DeviceRegistrationRequest
import com.okb.whatsappbridge.data.remote.dto.MessageUploadRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpBridgeApiTest {

    private lateinit var server: MockWebServer
    private val api = OkHttpBridgeApi()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun config(token: String? = "tok-123") = BackendConfig(server.url("/").toString(), "OKB-ANDROID-A82F19", token)

    private val upload = MessageUploadRequest(
        deviceId = "OKB-ANDROID-A82F19",
        clientMessageId = "uuid-1",
        fingerprint = "f".repeat(64),
        groupName = "OKB Monitoring",
        senderName = "Juan Santos",
        messageText = "Flooding observed at Barangay San Jose",
        timestamp = "2026-10-04T08:42:00+08:00",
        timestampMillis = 1_791_074_520_000,
        mediaType = "TEXT",
        mediaStatus = "NONE",
        sourcePackage = "com.whatsapp",
        capturedAt = "2026-10-04T08:42:01+08:00",
        platform = "whatsapp",
    )

    @Test
    fun `health check calls GET api v1 health`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok","version":"1.0.0"}"""))
        val result = api.health(config())
        assertTrue(result is ApiResult.Success)
        assertEquals("1.0.0", (result as ApiResult.Success).value.version)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/health", request.path)
        assertEquals("Bearer tok-123", request.getHeader("Authorization"))
        assertEquals("OKB-ANDROID-A82F19", request.getHeader("X-OKB-Device-Id"))
    }

    @Test
    fun `message upload posts the documented JSON with idempotency key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"srv-1","status":"stored"}"""))
        val result = api.uploadMessage(config(), upload)
        assertEquals("srv-1", (result as ApiResult.Success).value.resolvedServerId)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/messages", request.path)
        assertEquals("f".repeat(64), request.getHeader("Idempotency-Key"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("OKB-ANDROID-A82F19", body["deviceId"]!!.jsonPrimitive.content)
        assertEquals("OKB Monitoring", body["groupName"]!!.jsonPrimitive.content)
        assertEquals("Juan Santos", body["senderName"]!!.jsonPrimitive.content)
        assertEquals("Flooding observed at Barangay San Jose", body["messageText"]!!.jsonPrimitive.content)
        assertEquals("2026-10-04T08:42:00+08:00", body["timestamp"]!!.jsonPrimitive.content)
        assertEquals("TEXT", body["mediaType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `device registration posts to devices register`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"registered":true}"""))
        val result = api.registerDevice(config(), DeviceRegistrationRequest("OKB-ANDROID-A82F19", "Ops phone", "android", "1.0.0", "Android 14", "Google", "Pixel"))
        assertTrue(result is ApiResult.Success)
        assertEquals("/api/v1/devices/register", server.takeRequest().path)
    }

    @Test
    fun `no authorization header without a token`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        api.health(config(token = null))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `base url path prefix is preserved`() {
        assertEquals("https://okb.example.org/bridge/api/v1/messages", OkHttpBridgeApi.resolve("https://okb.example.org/bridge/", "api/v1/messages").toString())
        assertEquals("https://okb.example.org/api/v1/health", OkHttpBridgeApi.resolve("https://okb.example.org", "api/v1/health").toString())
        assertNull(OkHttpBridgeApi.resolve("not a url", "api/v1/health"))
    }

    @Test
    fun `server errors are classified as retryable, client errors are not`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"maintenance"}"""))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"groupName required"}"""))
        server.enqueue(MockResponse().setResponseCode(401))

        val e503 = api.uploadMessage(config(), upload) as ApiResult.HttpError
        assertTrue(e503.retryable)
        assertEquals("HTTP 503: maintenance", e503.message)
        assertTrue((api.uploadMessage(config(), upload) as ApiResult.HttpError).retryable)
        val e400 = api.uploadMessage(config(), upload) as ApiResult.HttpError
        assertFalse(e400.retryable)
        assertFalse(e400.authError)
        val e401 = api.uploadMessage(config(), upload) as ApiResult.HttpError
        assertTrue(e401.authError)
        assertFalse(e401.retryable)
    }

    @Test
    fun `unreachable backend is a network error`() = runTest {
        val url = server.url("/").toString()
        server.shutdown()
        val result = api.uploadMessage(BackendConfig(url, "OKB-ANDROID-A82F19", null), upload)
        assertTrue(result.toString(), result is ApiResult.NetworkError)
    }

    @Test
    fun `invalid url is a configuration error`() = runTest {
        val result = api.health(BackendConfig("okb dot org", "OKB-ANDROID-A82F19", null))
        assertTrue(result is ApiResult.ConfigurationError)
    }

    @Test
    fun `2xx with unexpected body still counts as received`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("accepted"))
        val result = api.uploadMessage(config(), upload)
        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).value.resolvedServerId)
    }
}
