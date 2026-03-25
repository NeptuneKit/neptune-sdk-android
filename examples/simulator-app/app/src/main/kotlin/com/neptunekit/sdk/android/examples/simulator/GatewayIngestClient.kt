package com.neptunekit.sdk.android.examples.simulator

import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogSource
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class SimulatorGatewayIngestResult(
    val requestUrl: String,
    val statusCode: Int,
    val responseBody: String,
)

fun interface SimulatorGatewayIngestClient {
    fun ingest(endpoint: GatewayDiscoveryEndpoint, record: IngestLogRecord): SimulatorGatewayIngestResult
}

class HttpUrlConnectionSimulatorGatewayIngestClient : SimulatorGatewayIngestClient {
    override fun ingest(endpoint: GatewayDiscoveryEndpoint, record: IngestLogRecord): SimulatorGatewayIngestResult {
        val requestUrl = "http://${endpoint.host}:${endpoint.port}/v2/logs:ingest"
        val connection = URL(requestUrl).openConnection() as HttpURLConnection

        return connection.useJsonPostRequest(record.toJsonObject()).let { response ->
            SimulatorGatewayIngestResult(
                requestUrl = requestUrl,
                statusCode = response.statusCode,
                responseBody = response.responseBody,
            )
        }
    }

    private fun HttpURLConnection.useJsonPostRequest(payload: JSONObject): SimulatorGatewayIngestResult {
        try {
            connectTimeout = REQUEST_TIMEOUT_MILLIS
            readTimeout = REQUEST_TIMEOUT_MILLIS
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")

            BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8)).use { writer ->
                writer.write(payload.toString())
            }

            val statusCode = responseCode
            val responseBody = responseBody(statusCode)
            return SimulatorGatewayIngestResult(
                requestUrl = url.toString(),
                statusCode = statusCode,
                responseBody = responseBody,
            )
        } finally {
            disconnect()
        }
    }

    private fun HttpURLConnection.responseBody(statusCode: Int): String {
        val stream = if (statusCode >= 400) errorStream else inputStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun IngestLogRecord.toJsonObject(): JSONObject =
        JSONObject().apply {
            put("timestamp", timestamp)
            put("level", level.name.lowercase())
            put("message", message)
            put("platform", platform.name.lowercase())
            put("appId", appId)
            put("sessionId", sessionId)
            put("deviceId", deviceId)
            put("category", category)
            put(
                "attributes",
                JSONObject().apply {
                    attributes.toSortedMap().forEach { (key, value) ->
                        put(key, value)
                    }
                },
            )
            put(
                "source",
                source?.toJsonObject() ?: JSONObject.NULL,
            )
        }

    private fun LogSource.toJsonObject(): JSONObject =
        JSONObject().apply {
            put("sdkName", sdkName)
            put("sdkVersion", sdkVersion)
            put("file", file)
            put("function", function)
            put("line", line ?: JSONObject.NULL)
        }

    private companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 3_000
    }
}
