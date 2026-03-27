package com.neptunekit.sdk.android.examples.simulator

import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryResult
import com.neptunekit.sdk.android.discovery.GatewayDiscoverySource
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.LogSource
import com.neptunekit.sdk.android.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatorDemoControllerTest {
    @Test
    fun emitLogUpdatesQueueMetricsAndRecentLogs() {
        SimulatorDemoController().use { controller ->
            val initial = controller.snapshot()
            assertEquals(0, initial.clickCount)
            assertEquals(0, initial.metrics.queuedRecords)
            assertTrue(initial.recentLogs.isEmpty())

            val afterFirstClick = controller.emitLog()

            assertEquals(1, afterFirstClick.clickCount)
            assertEquals(3, afterFirstClick.health.queueSize)
            assertEquals(3, afterFirstClick.metrics.queuedRecords)
            assertEquals(0L, afterFirstClick.metrics.droppedOverflow)
            assertEquals(3, afterFirstClick.recentLogs.size)
            assertEquals("neptune-simulator-batch-1-log-3", afterFirstClick.recentLogs.last().message)
        }
    }

    @Test
    fun discoverGatewayUploadsLogOnSuccessAndRecordsUploadState() {
        val uploadRequests = mutableListOf<SimulatorGatewayIngestRequest>()
        val discovery = SimulatorGatewayDiscovery { config ->
            assertEquals("10.0.2.2:18765", config.manualDsn)
            GatewayDiscoveryResult(
                endpoint = GatewayDiscoveryEndpoint("127.0.0.1", 18765),
                source = GatewayDiscoverySource.MANUAL_DSN,
                host = "127.0.0.1",
                port = 18765,
                version = "2.0.0-alpha.1",
            )
        }
        val ingest = SimulatorGatewayIngestClient { endpoint, record ->
            uploadRequests += SimulatorGatewayIngestRequest(endpoint, record)
            SimulatorGatewayIngestResult(
                requestUrl = "http://10.0.2.2:18765/v2/logs:ingest",
                statusCode = 200,
                responseBody = """{"ok":true}""",
            )
        }

        SimulatorDemoController(
            gatewayDiscovery = discovery,
            gatewayIngest = ingest,
        ).use { controller ->
            val afterDiscovery = controller.discoverGateway()

            assertEquals("ok", afterDiscovery.gatewayDiscovery.status)
            assertEquals("manual-dsn", afterDiscovery.gatewayDiscovery.source)
            assertEquals("127.0.0.1", afterDiscovery.gatewayDiscovery.host)
            assertEquals(18765, afterDiscovery.gatewayDiscovery.port)
            assertEquals("2.0.0-alpha.1", afterDiscovery.gatewayDiscovery.version)
            assertEquals("ok", afterDiscovery.gatewayIngest.status)
            assertEquals("http://10.0.2.2:18765/v2/logs:ingest", afterDiscovery.gatewayIngest.endpoint)
            assertEquals(200, afterDiscovery.gatewayIngest.responseCode)
            assertEquals(1, uploadRequests.size)
            assertEquals("http://10.0.2.2:18765/v2/logs:ingest", uploadRequests.single().endpoint.ingestUrl())
            assertEquals("neptune-simulator-gateway-discovered", uploadRequests.single().record.message)
            assertEquals(LogLevel.INFO, uploadRequests.single().record.level)
            assertEquals(Platform.ANDROID, uploadRequests.single().record.platform)
            assertEquals("gateway-discovery", uploadRequests.single().record.category)
            assertEquals("manual-dsn", uploadRequests.single().record.attributes["gatewaySource"])
            assertEquals("127.0.0.1", uploadRequests.single().record.attributes["gatewayHost"])
            assertEquals("18765", uploadRequests.single().record.attributes["gatewayPort"])
            assertEquals("2.0.0-alpha.1", uploadRequests.single().record.attributes["gatewayVersion"])
            assertEquals("127.0.0.1:18765", uploadRequests.single().record.attributes["gatewayEndpoint"])
            assertEquals("neptune-sdk-android", uploadRequests.single().record.source?.sdkName)
            assertEquals("SimulatorDemoController.kt", uploadRequests.single().record.source?.file)
        }
    }

    @Test
    fun discoverGatewayCapturesFailureMessage() {
        val discovery = SimulatorGatewayDiscovery { _ ->
            throw IllegalStateException("gateway offline")
        }

        SimulatorDemoController(gatewayDiscovery = discovery).use { controller ->
            val afterDiscovery = controller.discoverGateway()

            assertEquals("error", afterDiscovery.gatewayDiscovery.status)
            assertTrue(afterDiscovery.gatewayDiscovery.error.orEmpty().contains("gateway offline"))
        }
    }

    @Test
    fun refreshSnapshotDoesNotMutateQueueOrTriggerNetwork() {
        val uploadRequests = mutableListOf<SimulatorGatewayIngestRequest>()
        val ingest = SimulatorGatewayIngestClient { endpoint, record ->
            uploadRequests += SimulatorGatewayIngestRequest(endpoint, record)
            SimulatorGatewayIngestResult(
                requestUrl = endpoint.ingestUrl(),
                statusCode = 200,
                responseBody = """{"ok":true}""",
            )
        }

        SimulatorDemoController(gatewayIngest = ingest).use { controller ->
            controller.emitLog()
            val beforeRefresh = controller.snapshot()
            val afterRefresh = controller.refreshSnapshot()

            assertEquals(beforeRefresh.clickCount, afterRefresh.clickCount)
            assertEquals(beforeRefresh.metrics.queuedRecords, afterRefresh.metrics.queuedRecords)
            assertEquals(beforeRefresh.health.queueSize, afterRefresh.health.queueSize)
            assertEquals(0, uploadRequests.size)
        }
    }

    private data class SimulatorGatewayIngestRequest(
        val endpoint: GatewayDiscoveryEndpoint,
        val record: IngestLogRecord,
    )

    private fun GatewayDiscoveryEndpoint.ingestUrl(): String =
        "http://${host}:${port}/v2/logs:ingest"
}
