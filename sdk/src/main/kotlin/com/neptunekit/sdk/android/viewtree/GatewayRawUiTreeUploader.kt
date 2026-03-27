package com.neptunekit.sdk.android.viewtree

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.NumericNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class RawUiTreeIngestRequest(
    val platform: String,
    val appId: String,
    val sessionId: String,
    val deviceId: String,
    val snapshotId: String,
    val capturedAt: String,
    val payload: Any?,
)

data class GatewayRawUiTreeUploadResult(
    val requestUrl: String,
    val statusCode: Int,
    val responseBody: String,
)

interface GatewayRawUiTreeUploader {
    fun upload(endpoint: GatewayDiscoveryEndpoint, request: RawUiTreeIngestRequest): GatewayRawUiTreeUploadResult
}

class HttpUrlConnectionGatewayRawUiTreeUploader(
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val requestTimeoutMillis: Int = REQUEST_TIMEOUT_MILLIS,
) : GatewayRawUiTreeUploader {
    override fun upload(endpoint: GatewayDiscoveryEndpoint, request: RawUiTreeIngestRequest): GatewayRawUiTreeUploadResult {
        val requestUrl = endpoint.rawUiTreeIngestUrl()
        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        return connection.useJsonPostRequest(request.toPayloadNode(objectMapper)).let { response ->
            GatewayRawUiTreeUploadResult(
                requestUrl = requestUrl,
                statusCode = response.statusCode,
                responseBody = response.responseBody,
            )
        }
    }

    private fun RawUiTreeIngestRequest.toPayloadNode(mapper: ObjectMapper): ObjectNode = objectNode().apply {
        put("platform", platform)
        put("appId", appId)
        put("sessionId", sessionId)
        put("deviceId", deviceId)
        put("snapshotId", snapshotId)
        put("capturedAt", capturedAt)
        val payloadNode = payload.toNormalizedPayload(mapper)?.pruneZeroValues(isRoot = true)
        if (payloadNode == null) {
            set<JsonNode>("payload", objectNode())
        } else {
            set<JsonNode>("payload", payloadNode)
        }
    }

    private fun Any?.toNormalizedPayload(mapper: ObjectMapper): JsonNode? = when (this) {
        null -> null
        is JsonNode -> this
        is String -> mapper.readTree(this)
        else -> mapper.valueToTree<JsonNode>(this)
    }?.also { node ->
        require(node.isObject || node.isArray) {
            "Raw UI tree payload must serialize to a JSON object or array."
        }
    }

    private fun JsonNode.pruneZeroValues(isRoot: Boolean = false): JsonNode? = when {
        isNull -> if (isRoot) objectNode() else null
        isBoolean -> if (booleanValue()) this else null
        isNumber -> {
            val numeric = this as NumericNode
            if (numeric.decimalValue().compareTo(java.math.BigDecimal.ZERO) == 0) null else this
        }
        isArray -> {
            val source = this as ArrayNode
            val pruned = JsonNodeFactory.instance.arrayNode()
            source.forEach { child ->
                child.pruneZeroValues(isRoot = false)?.let { pruned.add(it) }
            }
            if (pruned.size() == 0) {
                if (isRoot) JsonNodeFactory.instance.arrayNode() else null
            } else {
                pruned
            }
        }
        isObject -> {
            val source = this as ObjectNode
            val pruned = JsonNodeFactory.instance.objectNode()
            val fields = source.fields()
            while (fields.hasNext()) {
                val field = fields.next()
                field.value.pruneZeroValues(isRoot = false)?.let { pruned.set<JsonNode>(field.key, it) }
            }
            if (pruned.size() == 0) {
                if (isRoot) JsonNodeFactory.instance.objectNode() else null
            } else {
                pruned
            }
        }
        else -> this
    }

    private fun HttpURLConnection.useJsonPostRequest(payload: ObjectNode): HttpResponse {
        try {
            connectTimeout = requestTimeoutMillis
            readTimeout = requestTimeoutMillis
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", CONTENT_TYPE_JSON)
            setRequestProperty("Accept", CONTENT_TYPE_JSON)

            BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8)).use { writer ->
                writer.write(payload.toString())
            }

            val code = responseCode
            val body = responseBody(code)
            return HttpResponse(statusCode = code, responseBody = body)
        } finally {
            disconnect()
        }
    }

    private fun HttpURLConnection.responseBody(statusCode: Int): String {
        val stream = if (statusCode >= HTTP_STATUS_BAD_RESPONSE) errorStream else inputStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun objectNode(): ObjectNode = JsonNodeFactory.instance.objectNode()

    private companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 3_000
        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
        private const val HTTP_STATUS_BAD_RESPONSE = 400
    }
}

private fun GatewayDiscoveryEndpoint.rawUiTreeIngestUrl(): String =
    "http://${host}:${port}/v2/ui-tree/inspector"

private data class HttpResponse(
    val statusCode: Int,
    val responseBody: String,
)
