package com.neptunekit.sdk.android.http

import com.neptunekit.sdk.android.export.ExportMetrics
import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.export.ServiceHealth
import com.neptunekit.sdk.android.model.InspectorSnapshot
import com.neptunekit.sdk.android.model.ExportLogRecord
import com.neptunekit.sdk.android.viewtree.ViewTreeCollector
import com.neptunekit.sdk.android.viewtree.ViewTreeQuery
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelinePhase
import java.io.Closeable
import java.time.Instant

internal const val DEFAULT_BIND_HOST = "0.0.0.0"
private const val MAX_LENGTH = 10_000
private const val HTTP_STATUS_OK = 200
private const val HTTP_STATUS_BAD_REQUEST = 400
private const val HTTP_STATUS_NOT_FOUND = 404
private const val HTTP_STATUS_METHOD_NOT_ALLOWED = 405

private val jsonMapper = ObjectMapper()
private val jsonContentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)
private val exportCallPhase = PipelinePhase("ExportHttpRouter")

data class ExportQueryParameters(
    val cursor: Long?,
    val length: Int?,
)

data class ViewTreeQueryParameters(
    val platform: String?,
    val appId: String?,
    val sessionId: String?,
    val deviceId: String?,
)

data class ExportHttpResult(
    val statusCode: Int,
    val body: String,
)

class ExportHttpServer(
    private val service: ExportService,
    val host: String = DEFAULT_BIND_HOST,
    private val viewTreeCollector: ViewTreeCollector? = null,
) : Closeable {
    private val router = ExportHttpRouter(service, viewTreeCollector = viewTreeCollector)
    private var server: ApplicationEngine? = null

    @Synchronized
    fun start(port: Int) {
        if (server != null) {
            return
        }

        val embedded = embeddedServer(
            factory = CIO,
            port = port,
            host = host,
        ) {
            exportHttpModule(router)
        }
        embedded.start(wait = false)
        server = embedded
    }

    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        server = null
    }

    override fun close() {
        stop()
        service.close()
    }
}

internal class ExportHttpRouter(
    private val service: ExportService,
    private val messageBus: ClientMessageBus = ClientMessageBus(),
    private val viewTreeCollector: ViewTreeCollector? = null,
) {
    fun handle(
        method: String,
        path: String,
        parameters: Map<String, List<String>>,
        body: String? = null,
    ): ExportHttpResult =
        when (method.uppercase()) {
            "GET" -> handleGet(path = path, parameters = parameters)
            "POST" -> when (path) {
                "/v2/client/command" -> handleClientCommand(body)
                else -> jsonError(
                    statusCode = HTTP_STATUS_METHOD_NOT_ALLOWED,
                    code = "method_not_allowed",
                    message = "Only GET is supported.",
                )
            }
            else -> jsonError(
                statusCode = HTTP_STATUS_METHOD_NOT_ALLOWED,
                code = "method_not_allowed",
                message = "Only GET is supported.",
            )
        }

    private fun handleGet(path: String, parameters: Map<String, List<String>>): ExportHttpResult =
        when (path) {
            "/v2/export/health" -> jsonOk(healthJson(service.health()))
            "/v2/export/metrics" -> jsonOk(metricsJson(service.metrics()))
            "/v2/logs" -> {
                val query = parseExportQueryParameters(parameters)
                jsonOk(logsJson(service.logs(cursor = query.cursor, limit = query.length ?: Int.MAX_VALUE)))
            }
            "/v2/ui-tree/inspector" -> {
                val query = parseViewTreeQueryParameters(parameters)
                jsonOk(viewTreeInspectorJson(query, viewTreeCollector))
            }

            else -> jsonError(
                statusCode = HTTP_STATUS_NOT_FOUND,
                code = "not_found",
                message = "Unknown export endpoint.",
            )
        }

    private fun handleClientCommand(body: String?): ExportHttpResult {
        val envelope = runCatching { parseBusEnvelope(body) }.getOrElse { error ->
            return jsonError(
                statusCode = HTTP_STATUS_BAD_REQUEST,
                code = "invalid_payload",
                message = error.message ?: "Invalid client command payload.",
            )
        }

        val ack = messageBus.acknowledgeInboundCommand(envelope)
        return if (ack.status == BusAckStatus.OK.rawValue) {
            jsonOk(busAckJson(ack))
        } else {
            jsonError(
                statusCode = HTTP_STATUS_BAD_REQUEST,
                code = "unsupported_command",
                message = ack.message ?: "Only ping is supported.",
            )
        }
    }
}

internal fun Application.exportHttpModule(router: ExportHttpRouter) {
    insertPhaseAfter(io.ktor.server.application.ApplicationCallPipeline.Plugins, exportCallPhase)
    intercept(exportCallPhase) {
        val body = if (call.request.httpMethod == HttpMethod.Post) {
            runCatching { call.receiveText() }.getOrDefault("")
        } else {
            null
        }
        val result = router.handle(
            method = call.request.httpMethod.value,
            path = call.request.path(),
            parameters = call.request.queryParameters.toListMap(),
            body = body,
        )
        call.respondText(
            text = result.body,
            contentType = jsonContentType,
            status = HttpStatusCode.fromValue(result.statusCode),
        )
        finish()
    }
}

internal fun parseExportQueryParameters(parameters: Map<String, List<String>>): ExportQueryParameters {
    val cursor = parameters.firstValue("cursor")
        ?.takeIf { it.isNotBlank() }
        ?.toLongOrNull()

    val length = parameters.firstValue("length")
        ?.takeIf { it.isNotBlank() }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.coerceAtMost(MAX_LENGTH)

    return ExportQueryParameters(cursor = cursor, length = length)
}

internal fun parseViewTreeQueryParameters(parameters: Map<String, List<String>>): ViewTreeQueryParameters =
    ViewTreeQueryParameters(
        platform = parameters.firstValue("platform")?.takeIf { it.isNotBlank() },
        appId = parameters.firstValue("appId")?.takeIf { it.isNotBlank() },
        sessionId = parameters.firstValue("sessionId")?.takeIf { it.isNotBlank() },
        deviceId = parameters.firstValue("deviceId")?.takeIf { it.isNotBlank() },
    )

private fun jsonOk(body: String): ExportHttpResult =
    ExportHttpResult(statusCode = HTTP_STATUS_OK, body = body)

private fun jsonError(statusCode: Int, code: String, message: String): ExportHttpResult =
    ExportHttpResult(
        statusCode = statusCode,
        body = jsonMapper.writeValueAsString(
            objectNode().apply {
                put("ok", false)
                putObject("error").apply {
                    put("code", code)
                    put("message", message)
                }
            },
        ),
    )

private fun healthJson(health: ServiceHealth): String =
    jsonMapper.writeValueAsString(
        objectNode().apply {
            put("ok", health.ok)
            put("queueCapacity", health.queueCapacity)
            put("queueSize", health.queueSize)
        },
    )

private fun metricsJson(metrics: ExportMetrics): String =
    jsonMapper.writeValueAsString(
        objectNode().apply {
            put("queuedRecords", metrics.queuedRecords)
            put("droppedOverflow", metrics.droppedOverflow)
        },
    )

private fun logsJson(page: com.neptunekit.sdk.android.core.LogPage): String =
    jsonMapper.writeValueAsString(
        objectNode().apply {
            val records = putArray("records")
            page.records.forEach { records.add(recordJson(it)) }
            put("hasMore", page.hasMore)
        },
    )

private fun viewTreeInspectorJson(
    query: ViewTreeQueryParameters,
    collector: ViewTreeCollector? = null,
): String {
    val platform = normalizePlatform(query.platform, fallback = "android")
    val snapshot = collector?.captureInspector(query.toViewTreeQuery())
        ?: unavailableInspectorSnapshot(
            platform = platform,
            reason = "ViewTreeCollector is not configured.",
        )
    return inspectorSnapshotJson(snapshot)
}

private fun unavailableInspectorSnapshot(platform: String, reason: String): InspectorSnapshot =
    InspectorSnapshot(
        snapshotId = "$platform-inspector-${System.currentTimeMillis()}",
        capturedAt = Instant.now().toString(),
        platform = platform,
        available = false,
        payload = null,
        reason = reason,
    )

private fun inspectorSnapshotJson(snapshot: InspectorSnapshot): String =
    jsonMapper.writeValueAsString(
        objectNode().apply {
            put("snapshotId", snapshot.snapshotId)
            put("capturedAt", snapshot.capturedAt)
            put("platform", snapshot.platform)
            put("available", snapshot.available)
            when (val payload = snapshot.payload) {
                null -> putNull("payload")
                else -> set<JsonNode>("payload", normalizeInspectorPayload(payload))
            }
            snapshot.reason?.let { put("reason", it) } ?: putNull("reason")
        },
    )

private fun normalizeInspectorPayload(payload: Any): JsonNode =
    when (payload) {
        is JsonNode -> payload
        is String -> jsonMapper.readTree(payload)
        else -> jsonMapper.valueToTree(payload)
    }.also { node ->
        require(node.isObject || node.isArray) {
            "Inspector payload must be a JSON object or array."
        }
    }

private fun ViewTreeQueryParameters.toViewTreeQuery(): ViewTreeQuery =
    ViewTreeQuery(
        platform = platform,
        appId = appId,
        sessionId = sessionId,
        deviceId = deviceId,
    )

private fun busAckJson(ack: BusAck): String =
    jsonMapper.writeValueAsString(ack)

private fun recordJson(record: ExportLogRecord): ObjectNode =
    objectNode().apply {
        put("id", record.id)
        put("timestamp", record.timestamp)
        put("level", record.level.name.lowercase())
        put("message", record.message)
        put("platform", record.platform.name.lowercase())
        put("appId", record.appId)
        put("sessionId", record.sessionId)
        put("deviceId", record.deviceId)
        put("category", record.category)
        putObject("attributes").apply {
            record.attributes.recordAttributesSorted().forEach { (key, value) ->
                put(key, value)
            }
        }
        if (record.source == null) {
            putNull("source")
        } else {
            putObject("source").apply {
                put("sdkName", record.source.sdkName)
                put("sdkVersion", record.source.sdkVersion)
                put("file", record.source.file)
                put("function", record.source.function)
                if (record.source.line == null) {
                    putNull("line")
                } else {
                    put("line", record.source.line)
                }
            }
        }
    }

private fun Map<String, String>.recordAttributesSorted(): List<Map.Entry<String, String>> =
    entries.sortedBy { it.key }

private fun Map<String, List<String>>.firstValue(key: String): String? =
    this[key]?.firstOrNull()

private fun io.ktor.http.Parameters.toListMap(): Map<String, List<String>> =
    names().associateWith { name -> getAll(name).orEmpty() }

private fun objectNode(): ObjectNode =
    JsonNodeFactory.instance.objectNode()

private fun parseBusEnvelope(body: String?): BusEnvelope {
    require(!body.isNullOrBlank()) { "missing request body" }
    val node = jsonMapper.readTree(body)
    require(node.isObject) { "client command payload must be a JSON object" }

    return BusEnvelope(
        requestId = node.optionalText("requestId"),
        direction = node.requiredText("direction"),
        kind = node.requiredText("kind"),
        command = node.optionalText("command"),
        timestamp = node.optionalText("timestamp"),
    )
}

private fun com.fasterxml.jackson.databind.JsonNode.requiredText(name: String): String {
    val value = this[name]?.asText()?.trim().orEmpty()
    require(value.isNotBlank()) { "missing $name" }
    return value
}

private fun com.fasterxml.jackson.databind.JsonNode.optionalText(name: String): String? {
    val value = this[name]?.asText()?.trim().orEmpty()
    return value.ifBlank { null }
}

private fun normalizePlatform(value: String?, fallback: String): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        "ios", "android", "harmony", "web" -> normalized
        else -> fallback
    }
}
