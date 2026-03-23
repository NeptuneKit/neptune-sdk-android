package com.neptunekit.sdk.android.model

/**
 * Supported client platform identifiers in the v2 contract.
 */
enum class Platform {
    IOS,
    ANDROID,
    HARMONY,
    WEB,
}

/**
 * Log verbosity levels accepted by the v2 contract.
 */
enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    NOTICE,
    WARNING,
    ERROR,
    CRITICAL,
}

/**
 * Optional source metadata for a log record.
 */
data class LogSource(
    val sdkName: String? = null,
    val sdkVersion: String? = null,
    val file: String? = null,
    val function: String? = null,
    val line: Int? = null,
)

/**
 * Ingest payload used by the SDK when sending records to the CLI gateway.
 * The `id` is intentionally omitted and assigned by the gateway.
 */
data class IngestLogRecord(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val platform: Platform,
    val appId: String,
    val sessionId: String,
    val deviceId: String,
    val category: String,
    val attributes: Map<String, String> = emptyMap(),
    val source: LogSource? = null,
)

/**
 * Stored/exported record with a gateway-assigned stable cursor.
 */
data class ExportLogRecord(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val message: String,
    val platform: Platform,
    val appId: String,
    val sessionId: String,
    val deviceId: String,
    val category: String,
    val attributes: Map<String, String> = emptyMap(),
    val source: LogSource? = null,
)

internal fun Long.toExportLogRecord(record: IngestLogRecord): ExportLogRecord =
    ExportLogRecord(
        id = this,
        timestamp = record.timestamp,
        level = record.level,
        message = record.message,
        platform = record.platform,
        appId = record.appId,
        sessionId = record.sessionId,
        deviceId = record.deviceId,
        category = record.category,
        attributes = record.attributes,
        source = record.source,
    )
