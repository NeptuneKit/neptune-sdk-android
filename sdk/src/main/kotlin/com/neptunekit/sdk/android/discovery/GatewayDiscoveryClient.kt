package com.neptunekit.sdk.android.discovery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.jmdns.JmDNS

fun interface GatewayDiscoveryMdnsResolver {
    fun resolve(config: GatewayDiscoveryConfig): List<GatewayDiscoveryCandidate>
}

fun interface GatewayDiscoveryHttpClient {
    fun get(candidate: GatewayDiscoveryCandidate, timeoutMillis: Int): GatewayDiscoveryHttpResponse
}

data class GatewayDiscoveryHttpResponse(
    val statusCode: Int,
    val body: String,
)

data class GatewayDiscoveryPayload(
    val host: String,
    val port: Int,
    val version: String,
)

class GatewayDiscovery(
    private val mdnsResolver: GatewayDiscoveryMdnsResolver = JmDnsGatewayDiscoveryMdnsResolver(),
    private val httpClient: GatewayDiscoveryHttpClient = UrlConnectionGatewayDiscoveryHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) {
    fun discover(config: GatewayDiscoveryConfig = GatewayDiscoveryConfig()): GatewayDiscoveryResult {
        val attempts = mutableListOf<GatewayDiscoveryAttempt>()
        val candidates = buildCandidateList(config)

        for (candidate in candidates) {
            val response = try {
                httpClient.get(candidate, timeoutMillis = safeTimeoutMillis(config.timeoutMillis))
            } catch (error: Exception) {
                attempts += GatewayDiscoveryAttempt(candidate, error.message ?: error::class.java.simpleName)
                null
            } ?: continue

            if (response.statusCode !in 200..299) {
                attempts += GatewayDiscoveryAttempt(candidate, "http ${response.statusCode}")
                continue
            }

            val payload = runCatching { parsePayload(response.body) }.getOrNull()
            if (payload == null) {
                attempts += GatewayDiscoveryAttempt(candidate, "invalid discovery payload")
                continue
            }

            val endpoint = payload.resolveEndpoint(candidate)
            return GatewayDiscoveryResult(
                endpoint = endpoint,
                source = candidate.source,
                host = payload.host,
                port = payload.port,
                version = payload.version,
            )
        }

        throw GatewayDiscoveryException(
            message = buildFailureMessage(attempts),
            attempts = attempts,
        )
    }

    private fun buildCandidateList(config: GatewayDiscoveryConfig): List<GatewayDiscoveryCandidate> {
        val mdnsCandidates = runCatching { mdnsResolver.resolve(config) }.getOrElse { emptyList() }
        val manualCandidates = parseManualDsn(config.manualDsn)
        return mdnsCandidates + manualCandidates
    }

    private fun parseManualDsn(manualDsn: String?): List<GatewayDiscoveryCandidate> {
        val normalized = manualDsn?.trim().orEmpty()
        if (normalized.isBlank()) {
            return emptyList()
        }

        val uri = tryParseUri(normalized)
            ?: return emptyList()

        val host = uri.host?.takeIf { it.isNotBlank() } ?: return emptyList()
        val port = uri.port
        if (port <= 0) {
            return emptyList()
        }

        return listOf(
            GatewayDiscoveryCandidate(
                endpoint = GatewayDiscoveryEndpoint(host, port),
                source = GatewayDiscoverySource.MANUAL_DSN,
                serviceName = normalized,
            ),
        )
    }

    private fun tryParseUri(value: String): URI? {
        val candidate = if ("://" in value) value else "http://$value"
        return runCatching { URI(candidate) }.getOrNull()
    }

    private fun parsePayload(body: String): GatewayDiscoveryPayload {
        val node = objectMapper.readTree(body)
        require(node.isObject) { "discovery payload must be a JSON object" }

        val host = textField(node, "host")
        val port = intField(node, "port")
        val version = textField(node, "version")
        return GatewayDiscoveryPayload(host = host, port = port, version = version)
    }

    private fun textField(node: JsonNode, name: String): String {
        val value = node[name]?.asText()?.trim().orEmpty()
        require(value.isNotBlank()) { "missing $name" }
        return value
    }

    private fun intField(node: JsonNode, name: String): Int {
        val value = node[name]?.takeIf { it.isInt || it.isIntegralNumber }?.asInt() ?: -1
        require(value in 1..65_535) { "missing $name" }
        return value
    }

    private fun GatewayDiscoveryPayload.resolveEndpoint(candidate: GatewayDiscoveryCandidate): GatewayDiscoveryEndpoint =
        GatewayDiscoveryEndpoint(
            host = if (host.isLoopbackOrWildcard()) candidate.endpoint.host else host,
            port = port,
        )

    private fun String.isLoopbackOrWildcard(): Boolean {
        return trim().lowercase() in setOf(
            "127.0.0.1",
            "localhost",
            "::1",
            "::",
            "0.0.0.0",
        )
    }

    private fun buildFailureMessage(attempts: List<GatewayDiscoveryAttempt>): String {
        if (attempts.isEmpty()) {
            return "Unable to discover Neptune gateway: no candidates were available."
        }

        return buildString {
            append("Unable to discover Neptune gateway. Tried ")
            append(attempts.size)
            append(" candidate(s): ")
            append(attempts.joinToString(" | ") { "${it.candidate.source}:${it.candidate.endpoint.host}:${it.candidate.endpoint.port} (${it.reason})" })
        }
    }

    private fun safeTimeoutMillis(timeoutMillis: Long): Int =
        timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
}

class JmDnsGatewayDiscoveryMdnsResolver(
    private val jmdnsFactory: JmDnsFactory = DefaultJmDnsFactory,
) : GatewayDiscoveryMdnsResolver {
    override fun resolve(config: GatewayDiscoveryConfig): List<GatewayDiscoveryCandidate> {
        val jmdns = jmdnsFactory.create()
        return try {
            jmdns
                .list(config.mdnsServiceType, config.timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()))
                .orEmpty()
                .asSequence()
                .filter { serviceInfo ->
                    config.mdnsServiceName == null || serviceInfo.name == config.mdnsServiceName
                }
                .flatMap { serviceInfo ->
                    serviceInfo
                        .getHostAddresses()
                        .orEmpty()
                        .asSequence()
                        .filter { host -> host.isNotBlank() }
                        .map { host ->
                            GatewayDiscoveryCandidate(
                                endpoint = GatewayDiscoveryEndpoint(host, serviceInfo.port),
                                source = GatewayDiscoverySource.MDNS,
                                serviceName = serviceInfo.name,
                            )
                        }
                }
                .toList()
        } finally {
            runCatching { jmdns.close() }
        }
    }
}

fun interface JmDnsFactory {
    fun create(): JmDNS
}

object DefaultJmDnsFactory : JmDnsFactory {
    override fun create(): JmDNS = JmDNS.create(null, "neptune-sdk-android-discovery")
}

class UrlConnectionGatewayDiscoveryHttpClient : GatewayDiscoveryHttpClient {
    override fun get(candidate: GatewayDiscoveryCandidate, timeoutMillis: Int): GatewayDiscoveryHttpResponse {
        val url = URL(candidate.endpoint.discoveryUrl())
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.instanceFollowRedirects = false
        connection.doInput = true

        return try {
            val statusCode = connection.responseCode
            val body = connection.responseBody(statusCode)
            GatewayDiscoveryHttpResponse(statusCode = statusCode, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.responseBody(statusCode: Int): String {
        val stream = if (statusCode in 200..299) inputStream else errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }
}
