package com.neptunekit.sdk.android.http

import com.neptunekit.sdk.android.core.DEFAULT_PAGE_LIMIT
import com.neptunekit.sdk.android.export.ExportMetrics
import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.export.ServiceHealth
import com.neptunekit.sdk.android.model.ExportLogRecord
import com.neptunekit.sdk.android.model.LogSource
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status

private const val DEFAULT_HOST = "127.0.0.1"
private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
private const val MAX_LIMIT = 1_000

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
        body = buildJsonObject(
            "ok" to "false",
            "error" to jsonObject(
                "code" to jsonString(code),
                "message" to jsonString(message),
            ),
        ),
    )

private fun healthJson(health: ServiceHealth): String =
    buildJsonObject(
        "ok" to health.ok.toJsonBoolean(),
        "queueCapacity" to health.queueCapacity.toString(),
        "queueSize" to health.queueSize.toString(),
    )

private fun metricsJson(metrics: ExportMetrics): String =
    buildJsonObject(
        "queuedRecords" to metrics.queuedRecords.toString(),
        "droppedOverflow" to metrics.droppedOverflow.toString(),
    )

private fun logsJson(page: com.neptunekit.sdk.android.core.LogPage): String =
    buildJsonObject(
        "records" to buildJsonArray(page.records.map { recordJson(it) }),
        "nextCursor" to page.nextCursor.toJsonNumberOrNull(),
        "hasMore" to page.hasMore.toJsonBoolean(),
    )

private fun recordJson(record: ExportLogRecord): String =
    buildJsonObject(
        "id" to record.id.toString(),
        "timestamp" to jsonString(record.timestamp),
        "level" to jsonString(record.level.name.lowercase()),
        "message" to jsonString(record.message),
        "platform" to jsonString(record.platform.name.lowercase()),
        "appId" to jsonString(record.appId),
        "sessionId" to jsonString(record.sessionId),
        "deviceId" to jsonString(record.deviceId),
        "category" to jsonString(record.category),
        "attributes" to attributesJson(record.attributes),
        "source" to sourceJson(record.source),
    )

private fun attributesJson(attributes: Map<String, String>): String =
    buildJsonObject(attributes.entries.sortedBy { it.key }.map { (key, value) ->
        key to jsonString(value)
    })

private fun sourceJson(source: LogSource?): String =
    if (source == null) {
        "null"
    } else {
        buildJsonObject(
            "sdkName" to source.sdkName.toJsonStringOrNull(),
            "sdkVersion" to source.sdkVersion.toJsonStringOrNull(),
            "file" to source.file.toJsonStringOrNull(),
            "function" to source.function.toJsonStringOrNull(),
            "line" to source.line.toJsonNumberOrNull(),
        )
    }

private fun buildJsonObject(fields: List<Pair<String, String>>): String =
    fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        jsonString(key) + ":" + value
    }

private fun buildJsonObject(vararg fields: Pair<String, String>): String =
    buildJsonObject(fields.toList())

private fun buildJsonArray(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]")

private fun jsonObject(vararg fields: Pair<String, String>): String =
    buildJsonObject(*fields)

private fun jsonString(value: String): String =
    buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

private fun String?.toJsonStringOrNull(): String =
    this?.let { jsonString(it) } ?: "null"

private fun Int?.toJsonNumberOrNull(): String =
    this?.toString() ?: "null"

private fun Long?.toJsonNumberOrNull(): String =
    this?.toString() ?: "null"

private fun Boolean.toJsonBoolean(): String = if (this) "true" else "false"

private fun Map<String, List<String>>.firstValue(key: String): String? =
    this[key]?.firstOrNull()
