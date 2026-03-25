package com.neptunekit.sdk.android.examples.simulator

import com.neptunekit.sdk.android.createExportService
import com.neptunekit.sdk.android.createGatewayDiscovery
import com.neptunekit.sdk.android.export.ExportMetrics
import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.export.ServiceHealth
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryConfig
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryResult
import com.neptunekit.sdk.android.discovery.GatewayDiscoverySource
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.LogSource
import com.neptunekit.sdk.android.model.Platform
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

data class SimulatorLogLine(
    val id: Long,
    val message: String,
    val level: LogLevel,
)

data class SimulatorDemoUiState(
    val clickCount: Int,
    val health: ServiceHealth,
    val metrics: ExportMetrics,
    val gatewayDiscovery: SimulatorGatewayDiscoveryState,
    val gatewayIngest: SimulatorGatewayIngestState,
    val recentLogs: List<SimulatorLogLine>,
)

data class SimulatorGatewayDiscoveryState(
    val status: String,
    val source: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val version: String? = null,
    val error: String? = null,
)

data class SimulatorGatewayIngestState(
    val status: String,
    val endpoint: String? = null,
    val responseCode: Int? = null,
    val error: String? = null,
)

fun interface SimulatorGatewayDiscovery {
    fun discover(config: GatewayDiscoveryConfig): GatewayDiscoveryResult
}

class SimulatorDemoController(
    private val service: ExportService = createExportService(queueCapacity = 16),
    private val gatewayDiscovery: SimulatorGatewayDiscovery = SimulatorGatewayDiscovery { config ->
        createGatewayDiscovery().discover(config)
    },
    private val gatewayIngest: SimulatorGatewayIngestClient = HttpUrlConnectionSimulatorGatewayIngestClient(),
) : Closeable {
    private val clickCount = AtomicInteger(0)
    @Volatile private var gatewayDiscoveryState = SimulatorGatewayDiscoveryState(status = "not-run")
    @Volatile private var gatewayIngestState = SimulatorGatewayIngestState(status = "not-run")

    fun snapshot(): SimulatorDemoUiState = SimulatorDemoUiState(
        clickCount = clickCount.get(),
        health = service.health(),
        metrics = service.metrics(),
        gatewayDiscovery = gatewayDiscoveryState,
        gatewayIngest = gatewayIngestState,
        recentLogs = service.logs(limit = 5).records.map {
            SimulatorLogLine(
                id = it.id,
                message = it.message,
                level = it.level,
            )
        },
    )

    fun emitLog(): SimulatorDemoUiState {
        val index = clickCount.incrementAndGet()
        val message = "neptune-simulator-click-$index"

        service.ingest(
            IngestLogRecord(
                timestamp = Instant.now().toString(),
                level = LogLevel.INFO,
                message = message,
                platform = Platform.ANDROID,
                appId = "com.neptunekit.sdk.android.examples.simulator",
                sessionId = "simulator-session",
                deviceId = "simulator-device",
                category = "demo",
                attributes = mapOf(
                    "clickIndex" to index.toString(),
                    "surface" to "emulator",
                ),
            ),
        )

        return snapshot()
    }

    fun discoverGateway(manualDsn: String = DEFAULT_GATEWAY_DSN): SimulatorDemoUiState {
        try {
            val result = gatewayDiscovery.discover(
                GatewayDiscoveryConfig(
                    manualDsn = manualDsn,
                ),
            )
            gatewayDiscoveryState = result.toDiscoveryState()
            gatewayIngestState = result.toDiscoveryIngestState(manualDsn)
        } catch (error: Exception) {
            gatewayDiscoveryState = error.toDiscoveryFailureState()
            gatewayIngestState = SimulatorGatewayIngestState(
                status = "skipped",
                error = "discovery failed",
            )
        }

        return snapshot()
    }

    override fun close() {
        service.close()
    }

    private fun GatewayDiscoveryResult.toDiscoveryState(): SimulatorGatewayDiscoveryState =
        SimulatorGatewayDiscoveryState(
            status = "ok",
            source = source.name.lowercase().replace('_', '-'),
            host = host,
            port = port,
            version = version,
        )

    private fun Throwable.toDiscoveryFailureState(): SimulatorGatewayDiscoveryState =
        SimulatorGatewayDiscoveryState(
            status = "error",
            error = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName,
        )

    private fun GatewayDiscoveryResult.toDiscoveryIngestState(manualDsn: String): SimulatorGatewayIngestState {
        val ingestEndpoint = resolveIngestEndpoint(manualDsn)
        val record = IngestLogRecord(
            timestamp = Instant.now().toString(),
            level = LogLevel.INFO,
            message = "neptune-simulator-gateway-discovered",
            platform = Platform.ANDROID,
            appId = "com.neptunekit.sdk.android.examples.simulator",
            sessionId = "simulator-session",
            deviceId = "simulator-device",
            category = "gateway-discovery",
            attributes = linkedMapOf(
                "gatewaySource" to source.name.lowercase().replace('_', '-'),
                "gatewayHost" to host,
                "gatewayPort" to port.toString(),
                "gatewayVersion" to version,
                "gatewayEndpoint" to "${endpoint.host}:${endpoint.port}",
            ),
            source = LogSource(
                sdkName = "neptune-sdk-android",
                sdkVersion = "simulator-app",
                file = "SimulatorDemoController.kt",
                function = "discoverGateway",
                line = null,
            ),
        )

        return try {
            val result = gatewayIngest.ingest(ingestEndpoint, record)
            result.toIngestState()
        } catch (error: Exception) {
            SimulatorGatewayIngestState(
                status = "error",
                endpoint = ingestEndpoint.ingestUrl(),
                error = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName,
            )
        }
    }

    private fun SimulatorGatewayIngestResult.toIngestState(): SimulatorGatewayIngestState =
        if (statusCode in 200..299) {
            SimulatorGatewayIngestState(
                status = "ok",
                endpoint = requestUrl,
                responseCode = statusCode,
            )
        } else {
            SimulatorGatewayIngestState(
                status = "error",
                endpoint = requestUrl,
                responseCode = statusCode,
                error = responseBody
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { "http $statusCode: ${it.take(240)}" }
                    ?: "http $statusCode",
            )
        }

    private fun GatewayDiscoveryResult.resolveIngestEndpoint(manualDsn: String): GatewayDiscoveryEndpoint =
        when (source) {
            GatewayDiscoverySource.MANUAL_DSN -> manualDsn.toGatewayEndpointOrNull() ?: endpoint
            else -> endpoint
        }

    private fun GatewayDiscoveryEndpoint.ingestUrl(): String =
        "http://${host}:${port}/v2/logs:ingest"

    private fun String.toGatewayEndpointOrNull(): GatewayDiscoveryEndpoint? {
        val raw = trim()
        val parts = raw.split(":")
        if (parts.size != 2) {
            return null
        }

        val host = parts[0].takeIf { it.isNotBlank() } ?: return null
        val port = parts[1].toIntOrNull() ?: return null
        return GatewayDiscoveryEndpoint(host = host, port = port)
    }

    private companion object {
        // Android Emulator maps host-loopback to 10.0.2.2, not 127.0.0.1.
        const val DEFAULT_GATEWAY_DSN = "10.0.2.2:18765"
    }
}
