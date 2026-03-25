package com.neptunekit.sdk.android.http

import com.neptunekit.sdk.android.createExportHttpServer
import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportHttpServerTest {
    private val service = ExportService()
    private val router = ExportHttpRouter(service)
    private val mapper = ObjectMapper()
    private var server: ExportHttpServer? = null
    private val httpClient = HttpClient.newHttpClient()

    @Test
    fun parsesCursorAndLimitQueryParameters() {
        val query = parseExportQueryParameters(
            mapOf(
                "cursor" to listOf("41"),
                "limit" to listOf("7"),
            ),
        )

        assertEquals(41L, query.cursor)
        assertEquals(7, query.limit)
    }

    @Test
    fun invalidQueryValuesFallBackToDefaults() {
        val query = parseExportQueryParameters(
            mapOf(
                "cursor" to listOf("not-a-number"),
                "limit" to listOf("0"),
            ),
        )

        assertEquals(null, query.cursor)
        assertEquals(1, query.limit)
    }

    @Test
    fun healthEndpointReturnsStructuredJson() {
        val response = router.handle("GET", "/v2/export/health", emptyMap())

        assertEquals(200, response.statusCode)

        val payload = mapper.readTree(response.body)
        assertTrue(payload["ok"]!!.asBoolean())
        assertEquals(2_000, payload["queueCapacity"]!!.asInt())
        assertEquals(0, payload["queueSize"]!!.asInt())
    }

    @Test
    fun logsEndpointEscapesStringsAndPreservesNullSource() {
        service.ingest(sampleRecord())

        val response = router.handle(
            method = "GET",
            path = "/v2/export/logs",
            parameters = mapOf("cursor" to listOf("0"), "limit" to listOf("10")),
        )

        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("\\\"quoted\\\""))
        assertTrue(response.body.contains("\\nline"))

        val payload = mapper.readTree(response.body)
        val records = payload["records"]!!
        val record = records[0]
        assertEquals(1L, record["id"]!!.asLong())
        assertEquals("android-http-message \"quoted\"\nline", record["message"]!!.asText())
        assertEquals("android", record["platform"]!!.asText())
        assertEquals("a-value", record["attributes"]!!["a-key"]!!.asText())
        assertEquals("b-value", record["attributes"]!!["b-key"]!!.asText())
        assertTrue(record["source"]!!.isNull)
        assertFalse(payload["hasMore"]!!.asBoolean())
    }

    @Test
    fun metricsEndpointReturnsStructuredJson() {
        val response = router.handle("GET", "/v2/export/metrics", emptyMap())

        assertEquals(200, response.statusCode)

        val payload = mapper.readTree(response.body)
        assertEquals(0, payload["queuedRecords"]!!.asInt())
        assertEquals(0L, payload["droppedOverflow"]!!.asLong())
    }

    @Test
    fun unsupportedMethodReturnsJsonError() {
        val response = router.handle("POST", "/v2/export/health", emptyMap())

        assertEquals(405, response.statusCode)

        val payload = mapper.readTree(response.body)
        assertFalse(payload["ok"]!!.asBoolean())
        assertEquals("method_not_allowed", payload["error"]!!["code"]!!.asText())
        assertEquals("Only GET is supported.", payload["error"]!!["message"]!!.asText())
    }

    @Test
    fun commandEndpointReturnsPingAckJson() {
        val response = router.handle(
            method = "POST",
            path = "/v2/client/command",
            parameters = emptyMap(),
            body = """{"requestId":"req-1","command":"ping"}""",
        )

        assertEquals(200, response.statusCode)

        val payload = mapper.readTree(response.body)
        assertEquals("req-1", payload["requestId"]!!.asText())
        assertEquals("ping", payload["command"]!!.asText())
        assertEquals("ok", payload["status"]!!.asText())
        assertTrue(payload["timestamp"]!!.asText().isNotBlank())
        assertTrue(payload["message"] == null || payload["message"].isNull)
    }

    @Test
    fun embeddedServerServesHealthEndpointOverHttp() {
        val port = findAvailablePort()
        val server = ExportHttpServer(service)
        this.server = server
        server.start(port)

        val response = eventuallyRequest("GET", port, "/v2/export/health")

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"))

        val payload = mapper.readTree(response.body())
        assertTrue(payload["ok"]!!.asBoolean())
        assertEquals(2_000, payload["queueCapacity"]!!.asInt())
    }

    @Test
    fun embeddedServerPreservesJsonErrorForUnsupportedMethod() {
        val port = findAvailablePort()
        val server = ExportHttpServer(service)
        this.server = server
        server.start(port)

        val response = eventuallyRequest("POST", port, "/v2/export/health")

        assertEquals(405, response.statusCode())

        val payload = mapper.readTree(response.body())
        assertFalse(payload["ok"]!!.asBoolean())
        assertEquals("method_not_allowed", payload["error"]!!["code"]!!.asText())
    }

    @Test
    fun embeddedServerServesClientCommandAckOverHttp() {
        val port = findAvailablePort()
        val server = ExportHttpServer(service)
        this.server = server
        server.start(port)

        val response = eventuallyRequest(
            method = "POST",
            port = port,
            path = "/v2/client/command",
            body = """{"requestId":"req-1","command":"ping"}""",
        )

        assertEquals(200, response.statusCode())

        val payload = mapper.readTree(response.body())
        assertEquals("req-1", payload["requestId"]!!.asText())
        assertEquals("ping", payload["command"]!!.asText())
        assertEquals("ok", payload["status"]!!.asText())
        assertTrue(payload["timestamp"]!!.asText().isNotBlank())
    }

    @Test
    fun defaultServerBindingUsesWildcardHost() {
        val server = createExportHttpServer()

        assertEquals("0.0.0.0", server.host)

        server.close()
    }

    @AfterTest
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun sampleRecord(): IngestLogRecord =
        IngestLogRecord(
            timestamp = "2026-03-23T00:00:00Z",
            level = LogLevel.INFO,
            message = "android-http-message \"quoted\"\nline",
            platform = Platform.ANDROID,
            appId = "com.example.app",
            sessionId = "session-android",
            deviceId = "device-android",
            category = "default",
            attributes = linkedMapOf(
                "b-key" to "b-value",
                "a-key" to "a-value",
            ),
            source = null,
        )

    private fun eventuallyRequest(method: String, port: Int, path: String, body: String? = null): HttpResponse<String> {
        repeat(20) { attempt ->
            try {
                return request(method = method, port = port, path = path, body = body)
            } catch (error: Exception) {
                if (attempt == 19) {
                    throw error
                }
                Thread.sleep(50)
            }
        }

        error("unreachable")
    }

    private fun request(method: String, port: Int, path: String, body: String? = null): HttpResponse<String> {
        val bodyPublisher = body?.let { HttpRequest.BodyPublishers.ofString(it) }
            ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$port$path"))
            .method(method, bodyPublisher)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .build()

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun findAvailablePort(): Int =
        ServerSocket(0).use { it.localPort }
}
