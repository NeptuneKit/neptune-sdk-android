package com.neptunekit.sdk.android.http

import com.neptunekit.sdk.android.createExportHttpServer
import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform
import com.neptunekit.sdk.android.model.InspectorSnapshot
import com.neptunekit.sdk.android.model.ViewTreeFrame
import com.neptunekit.sdk.android.model.ViewTreeStyle
import com.neptunekit.sdk.android.viewtree.ViewTreeCollector
import com.neptunekit.sdk.android.viewtree.ViewTreeQuery
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
    fun parsesCursorAndLengthQueryParameters() {
        val query = parseExportQueryParameters(
            mapOf(
                "cursor" to listOf("41"),
                "length" to listOf("7"),
            ),
        )

        assertEquals(41L, query.cursor)
        assertEquals(7, query.length)
    }

    @Test
    fun invalidQueryValuesFallBackToDefaults() {
        val query = parseExportQueryParameters(
            mapOf(
                "cursor" to listOf("not-a-number"),
                "length" to listOf("0"),
            ),
        )

        assertEquals(null, query.cursor)
        assertEquals(null, query.length)
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
            path = "/v2/logs",
            parameters = mapOf("cursor" to listOf("0"), "length" to listOf("10")),
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
        assertTrue(payload["nextCursor"] == null || payload["nextCursor"].isMissingNode || payload["nextCursor"].isNull)
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
    fun viewTreeSnapshotEndpointReturnsNotFound() {
        val response = router.handle(
            method = "GET",
            path = "/v2/ui-tree/snapshot",
            parameters = mapOf(
                "platform" to listOf("android"),
                "appId" to listOf("demo.app"),
                "sessionId" to listOf("s-1"),
                "deviceId" to listOf("d-1"),
            ),
        )

        assertEquals(404, response.statusCode)

        val payload = mapper.readTree(response.body)
        assertFalse(payload["ok"]!!.asBoolean())
        assertEquals("not_found", payload["error"]!!["code"]!!.asText())
        assertEquals("Unknown export endpoint.", payload["error"]!!["message"]!!.asText())
    }

    @Test
    fun viewTreeInspectorEndpointReturnsUnavailableWhenCollectorMissing() {
        val response = router.handle(
            method = "GET",
            path = "/v2/ui-tree/inspector",
            parameters = mapOf(
                "platform" to listOf("android"),
                "deviceId" to listOf("d-1"),
            ),
        )

        assertEquals(200, response.statusCode)

        val payload = mapper.readTree(response.body)
        assertFalse(payload["available"]!!.asBoolean())
        assertTrue(payload["payload"]!!.isNull)
        assertTrue(payload["reason"]!!.asText().isNotBlank())
    }

    @Test
    fun viewTreeInspectorEndpointReturnsObjectPayloadFromCollector() {
        val collector = RecordingViewTreeCollector(
            inspector = InspectorSnapshot(
                snapshotId = "inspector-123",
                capturedAt = "2026-03-27T00:00:00Z",
                platform = "android",
                available = true,
                payload = FakeInspectorPayload(
                    roots = listOf(
                        FakeInspectorNode(
                            id = "root",
                            className = "android.widget.FrameLayout",
                            text = "root text",
                            style = ViewTreeStyle(
                                typographyUnit = "dp",
                                sourceTypographyUnit = "sp",
                                platformFontScale = 1.0,
                                fontSize = 14.0,
                                lineHeight = 18.0,
                                letterSpacing = 0.75,
                                fontWeightRaw = "style=0,fakeBold=false",
                            ),
                            children = listOf(
                                FakeInspectorNode(
                                    id = "child",
                                    className = "android.widget.TextView",
                                    text = "child text",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val router = ExportHttpRouter(service, messageBus = ClientMessageBus(), viewTreeCollector = collector)

        val response = router.handle(
            method = "GET",
            path = "/v2/ui-tree/inspector",
            parameters = mapOf(
                "platform" to listOf("android"),
                "deviceId" to listOf("d-collector"),
            ),
        )

        assertEquals(200, response.statusCode)
        assertEquals(
            ViewTreeQuery(
                platform = "android",
                appId = null,
                sessionId = null,
                deviceId = "d-collector",
            ),
            collector.inspectorQueries.single(),
        )

        val payload = mapper.readTree(response.body)
        assertTrue(payload["available"]!!.asBoolean())
        assertTrue(payload["payload"]!!.isObject)
        assertEquals("root text", payload["payload"]!!["roots"]!![0]["text"]!!.asText())
        assertEquals("dp", payload["payload"]!!["roots"]!![0]["style"]!!["typographyUnit"]!!.asText())
        assertEquals("sp", payload["payload"]!!["roots"]!![0]["style"]!!["sourceTypographyUnit"]!!.asText())
        assertEquals(1.0, payload["payload"]!!["roots"]!![0]["style"]!!["platformFontScale"]!!.asDouble())
        assertEquals(14.0, payload["payload"]!!["roots"]!![0]["style"]!!["fontSize"]!!.asDouble())
        assertEquals(18.0, payload["payload"]!!["roots"]!![0]["style"]!!["lineHeight"]!!.asDouble())
        assertEquals(0.75, payload["payload"]!!["roots"]!![0]["style"]!!["letterSpacing"]!!.asDouble())
        assertEquals("style=0,fakeBold=false", payload["payload"]!!["roots"]!![0]["style"]!!["fontWeightRaw"]!!.asText())
        assertEquals("child text", payload["payload"]!!["roots"]!![0]["children"]!![0]["text"]!!.asText())
    }

    @Test
    fun viewTreeInspectorEndpointParsesStringifiedJsonPayloadIntoJsonNode() {
        val collector = RecordingViewTreeCollector(
            inspector = InspectorSnapshot(
                snapshotId = "inspector-456",
                capturedAt = "2026-03-27T00:00:00Z",
                platform = "android",
                available = true,
                payload = """{"roots":[{"id":"root","children":[{"id":"child"}]}]}""",
            ),
        )
        val router = ExportHttpRouter(service, messageBus = ClientMessageBus(), viewTreeCollector = collector)

        val response = router.handle(
            method = "GET",
            path = "/v2/ui-tree/inspector",
            parameters = mapOf(
                "platform" to listOf("android"),
                "deviceId" to listOf("d-collector"),
            ),
        )

        assertEquals(200, response.statusCode)

        val payload = mapper.readTree(response.body)
        assertTrue(payload["payload"]!!.isObject)
        assertFalse(payload["payload"]!!.isTextual)
        assertEquals("root", payload["payload"]!!["roots"]!![0]["id"]!!.asText())
        assertEquals("child", payload["payload"]!!["roots"]!![0]["children"]!![0]["id"]!!.asText())
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
            body = """{"requestId":"req-1","direction":"cli_to_client","kind":"command","command":"ping"}""",
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
            body = """{"requestId":"req-1","direction":"cli_to_client","kind":"command","command":"ping"}""",
        )

        assertEquals(200, response.statusCode())

        val payload = mapper.readTree(response.body())
        assertEquals("req-1", payload["requestId"]!!.asText())
        assertEquals("ping", payload["command"]!!.asText())
        assertEquals("ok", payload["status"]!!.asText())
        assertTrue(payload["timestamp"]!!.asText().isNotBlank())
    }

    @Test
    fun embeddedServerReturnsNotFoundForSnapshotEndpointOverHttp() {
        val port = findAvailablePort()
        val server = ExportHttpServer(service)
        this.server = server
        server.start(port)

        val response = eventuallyRequest(
            method = "GET",
            port = port,
            path = "/v2/ui-tree/snapshot?platform=android&appId=demo.app&sessionId=s-http",
        )

        assertEquals(404, response.statusCode())
        val payload = mapper.readTree(response.body())
        assertFalse(payload["ok"]!!.asBoolean())
        assertEquals("not_found", payload["error"]!!["code"]!!.asText())
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

    private data class FakeInspectorPayload(
        val roots: List<FakeInspectorNode>,
    )

    private data class FakeInspectorNode(
        val id: String,
        val className: String,
        val text: String? = null,
        val style: ViewTreeStyle? = null,
        val children: List<FakeInspectorNode> = emptyList(),
    )

    private class RecordingViewTreeCollector(
        private val inspector: InspectorSnapshot? = null,
    ) : ViewTreeCollector {
        val inspectorQueries = mutableListOf<ViewTreeQuery>()

        override fun captureSnapshot(query: ViewTreeQuery): com.neptunekit.sdk.android.model.ViewTreeSnapshot? = null

        override fun captureInspector(query: ViewTreeQuery): InspectorSnapshot? {
            inspectorQueries += query
            return inspector
        }
    }
}
