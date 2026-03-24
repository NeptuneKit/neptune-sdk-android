package com.neptunekit.sdk.android.examples

import com.neptunekit.sdk.android.createExportService
import com.neptunekit.sdk.android.export.ExportMetrics
import com.neptunekit.sdk.android.export.ServiceHealth
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform

data class SmokeDemoRecordSummary(
    val id: Long,
    val level: LogLevel,
    val message: String,
)

data class SmokeDemoSummary(
    val health: ServiceHealth,
    val metrics: ExportMetrics,
    val records: List<SmokeDemoRecordSummary>,
    val nextCursor: Long?,
    val hasMore: Boolean,
)

fun runSmokeDemo(): SmokeDemoSummary = createExportService(queueCapacity = 8).use { service ->
    service.ingest(
        sampleRecord(
            timestamp = "2026-03-24T08:00:00Z",
            level = LogLevel.INFO,
            message = "smoke-start",
            category = "demo",
        ),
    )
    service.ingest(
        sampleRecord(
            timestamp = "2026-03-24T08:00:01Z",
            level = LogLevel.WARNING,
            message = "smoke-cache-warmup-slow",
            category = "demo",
        ),
    )
    service.ingest(
        sampleRecord(
            timestamp = "2026-03-24T08:00:02Z",
            level = LogLevel.ERROR,
            message = "smoke-request-failed",
            category = "demo",
        ),
    )

    val page = service.logs(limit = 10)

    SmokeDemoSummary(
        health = service.health(),
        metrics = service.metrics(),
        records = page.records.map {
            SmokeDemoRecordSummary(
                id = it.id,
                level = it.level,
                message = it.message,
            )
        },
        nextCursor = page.nextCursor,
        hasMore = page.hasMore,
    )
}

fun renderSmokeDemoSummary(summary: SmokeDemoSummary): String = buildString {
    appendLine("NeptuneKit Android smoke demo")
    appendLine("health ok=${summary.health.ok} queueCapacity=${summary.health.queueCapacity} queueSize=${summary.health.queueSize}")
    appendLine("metrics queuedRecords=${summary.metrics.queuedRecords} droppedOverflow=${summary.metrics.droppedOverflow}")
    appendLine("page hasMore=${summary.hasMore} nextCursor=${summary.nextCursor ?: "null"}")
    appendLine("records:")
    summary.records.forEach { record ->
        appendLine("- #${record.id} ${record.level} ${record.message}")
    }
}

fun main() {
    println(renderSmokeDemoSummary(runSmokeDemo()))
}

private fun sampleRecord(
    timestamp: String,
    level: LogLevel,
    message: String,
    category: String,
): IngestLogRecord =
    IngestLogRecord(
        timestamp = timestamp,
        level = level,
        message = message,
        platform = Platform.ANDROID,
        appId = "com.neptunekit.examples.smoke",
        sessionId = "smoke-session",
        deviceId = "smoke-device",
        category = category,
        attributes = mapOf(
            "channel" to "smoke",
            "message_length" to message.length.toString(),
        ),
    )
