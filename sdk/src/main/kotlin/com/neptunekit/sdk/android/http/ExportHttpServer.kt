package com.neptunekit.sdk.android.http

import com.neptunekit.sdk.android.core.DEFAULT_PAGE_LIMIT
import com.neptunekit.sdk.android.export.ExportMetrics
import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.export.ServiceHealth
import com.neptunekit.sdk.android.model.ExportLogRecord
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status

private const val DEFAULT_HOST = "127.0.0.1"
private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
private const val MAX_LIMIT = 1_000

private val jsonMapper = ObjectMapper()

data class ExportQueryParameters(
    val cursor: Long?,
    val limit: Int,
)

data class ExportHttpResult(
    val status: Status,
    val body: String,
)

class ExportHttpServer(
    private val service: ExportService,
    private val host: String = DEFAULT_HOST,
) {
    private val router = ExportHttpRouter(service)
    private var server: EmbeddedServer? = null

    @Synchronized
    fun start(port: Int) {
        if (server != null) {
            return
        }

        val embedded = EmbeddedServer(host, port, router)
        embedded.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = embedded
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }
}

internal class ExportHttpRouter(
    private val service: ExportService,
) {
    fun handle(method: String, path: String, parameters: Map<String, List<String>>): ExportHttpResult =
        when (method.uppercase()) {
            "GET" -> handleGet(path = path, parameters = parameters)
            else -> jsonError(
                status = Status.METHOD_NOT_ALLOWED,
                code = "method_not_allowed",
                message = "Only GET is supported.",
            )
        }

    private fun handleGet(path: String, parameters: Map<String, List<String>>): ExportHttpResult =
        when (path) {
            "/v2/export/health" -> jsonOk(healthJson(service.health()))
            "/v2/export/metrics" -> jsonOk(metricsJson(service.metrics()))
            "/v2/export/logs" -> {
                val query = parseExportQueryParameters(parameters)
                jsonOk(logsJson(service.logs(cursor = query.cursor, limit = query.limit)))
            }

            else -> jsonError(
                status = Status.NOT_FOUND,
                code = "not_found",
                message = "Unknown export endpoint.",
            )
        }
}

internal class EmbeddedServer(
    host: String,
    port: Int,
    private val router: ExportHttpRouter,
) : NanoHTTPD(host, port) {
    override fun serve(session: IHTTPSession): Response {
        val result = router.handle(
            method = session.method.name,
            path = session.uri,
            parameters = session.parameters,
        )
        return newFixedLengthResponse(result.status, JSON_CONTENT_TYPE, result.body)
    }
}

internal fun parseExportQueryParameters(parameters: Map<String, List<String>>): ExportQueryParameters {
    val cursor = parameters.firstValue("cursor")
        ?.takeIf { it.isNotBlank() }
        ?.toLongOrNull()

    val limit = parameters.firstValue("limit")
        ?.takeIf { it.isNotBlank() }
        ?.toIntOrNull()
        ?.coerceIn(1, MAX_LIMIT)
        ?: DEFAULT_PAGE_LIMIT

    return ExportQueryParameters(cursor = cursor, limit = limit)
}

private fun jsonOk(body: String): ExportHttpResult =
    ExportHttpResult(status = Status.OK, body = body)

private fun jsonError(status: Status, code: String, message: String): ExportHttpResult =
    ExportHttpResult(
        status = status,
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

            if (page.nextCursor == null) {
                putNull("nextCursor")
            } else {
                put("nextCursor", page.nextCursor)
            }

            put("hasMore", page.hasMore)
        },
    )

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

private fun objectNode(): ObjectNode =
    JsonNodeFactory.instance.objectNode()
