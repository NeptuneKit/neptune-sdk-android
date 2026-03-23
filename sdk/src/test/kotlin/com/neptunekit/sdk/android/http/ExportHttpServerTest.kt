package com.neptunekit.sdk.android.http

import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform
import com.fasterxml.jackson.databind.ObjectMapper
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportHttpServerTest {
    private val service = ExportService()
    private val router = ExportHttpRouter(service)
    private val mapper = ObjectMapper()

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

        assertEquals(Status.OK, response.status)

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

        assertEquals(Status.OK, response.status)
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

        assertEquals(Status.OK, response.status)

        val payload = mapper.readTree(response.body)
        assertEquals(0, payload["queuedRecords"]!!.asInt())
        assertEquals(0L, payload["droppedOverflow"]!!.asLong())
    }

    @Test
    fun unsupportedMethodReturnsJsonError() {
        val response = router.handle("POST", "/v2/export/health", emptyMap())

        assertEquals(Status.METHOD_NOT_ALLOWED, response.status)

        val payload = mapper.readTree(response.body)
        assertFalse(payload["ok"]!!.asBoolean())
        assertEquals("method_not_allowed", payload["error"]!!["code"]!!.asText())
        assertEquals("Only GET is supported.", payload["error"]!!["message"]!!.asText())
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
}
