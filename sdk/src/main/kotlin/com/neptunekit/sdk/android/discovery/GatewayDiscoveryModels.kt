package com.neptunekit.sdk.android.discovery

data class GatewayDiscoveryConfig(
    val manualDsn: String? = null,
    val mdnsServiceType: String = "_neptune._tcp.local.",
    val mdnsServiceName: String? = null,
    val timeoutMillis: Long = 2_000,
)

data class GatewayDiscoveryEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port must be within 1..65535" }
    }

    fun discoveryUrl(): String = "http://${host.forUrl()}:$port/v2/gateway/discovery"

    private fun String.forUrl(): String =
        if (contains(':') && !startsWith('[')) {
            "[$this]"
        } else {
            this
        }
}

enum class GatewayDiscoverySource {
    MDNS,
    MANUAL_DSN,
}

data class GatewayDiscoveryCandidate(
    val endpoint: GatewayDiscoveryEndpoint,
    val source: GatewayDiscoverySource,
    val serviceName: String? = null,
)

data class GatewayDiscoveryResult(
    val endpoint: GatewayDiscoveryEndpoint,
    val source: GatewayDiscoverySource,
    val host: String,
    val port: Int,
    val version: String,
)

data class GatewayDiscoveryAttempt(
    val candidate: GatewayDiscoveryCandidate,
    val reason: String,
)

class GatewayDiscoveryException(
    message: String,
    val attempts: List<GatewayDiscoveryAttempt>,
) : IllegalStateException(message)
