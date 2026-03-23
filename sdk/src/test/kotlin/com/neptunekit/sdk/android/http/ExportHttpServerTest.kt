package com.neptunekit.sdk.android.http

import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportHttpServerTest {
    private val service = ExportService()
    private val router = ExportHttpRouter(service)

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
    fun healthEndpointReturnsOkJson() {
        val response = router.handle("GET", "/v2/export/health", emptyMap())

        assertTrue(response.body.contains("\"ok\":true"))
        assertTrue(response.body.contains("\"queueCapacity\""))
        assertTrue(response.body.contains("\"queueSize\""))
    }

    @Test
    fun logsEndpointReturnsRecordsJson() {
        service.ingest(sampleRecord())

        val response = router.handle(
            method = "GET",
            path = "/v2/export/logs",
            parameters = mapOf("cursor" to listOf("0"), "limit" to listOf("10")),
        )

        assertTrue(response.body.contains("\"records\""))
        assertTrue(response.body.contains("android-http-message"))
        assertTrue(response.body.contains("\"nextCursor\""))
    }

    @Test
    fun metricsEndpointReturnsMetricsJson() {
        val response = router.handle("GET", "/v2/export/metrics", emptyMap())

        assertTrue(response.body.contains("\"queuedRecords\""))
        assertTrue(response.body.contains("\"droppedOverflow\""))
    }

    private fun sampleRecord(): IngestLogRecord =
        IngestLogRecord(
            timestamp = "2026-03-23T00:00:00Z",
            level = LogLevel.INFO,
            message = "android-http-message",
            platform = Platform.ANDROID,
            appId = "com.example.app",
            sessionId = "session-android",
            deviceId = "device-android",
            category = "default",
        )
}
