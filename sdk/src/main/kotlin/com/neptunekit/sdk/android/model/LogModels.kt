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

data class ViewTreeNode(
    val frame: ViewTreeFrame? = null,
    val style: ViewTreeStyle? = null,
    val text: String? = null,
    val visible: Boolean? = null,
    val id: String,
    val parentId: String? = null,
    val name: String,
    val children: List<ViewTreeNode> = emptyList(),
)

data class ViewTreeFrame(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

data class ViewTreeStyle(
    val opacity: Double? = null,
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val typographyUnit: String? = null,
    val sourceTypographyUnit: String? = null,
    val platformFontScale: Double? = null,
    val fontSize: Double? = null,
    val lineHeight: Double? = null,
    val letterSpacing: Double? = null,
    val fontWeight: String? = null,
    val fontWeightRaw: String? = null,
    val borderRadius: Double? = null,
    val borderWidth: Double? = null,
    val borderColor: String? = null,
    val zIndex: Double? = null,
    val textAlign: String? = null,
)

data class ViewTreeSnapshot(
    val snapshotId: String,
    val capturedAt: String,
    val platform: String,
    val roots: List<ViewTreeNode>,
)

data class InspectorSnapshot(
    val snapshotId: String,
    val capturedAt: String,
    val platform: String,
    val available: Boolean,
    val payload: Any?,
    val reason: String? = null,
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
