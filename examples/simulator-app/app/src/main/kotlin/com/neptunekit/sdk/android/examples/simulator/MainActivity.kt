package com.neptunekit.sdk.android.examples.simulator

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.neptunekit.sdk.android.createClientRegistrationSession
import com.neptunekit.sdk.android.createExportService
import com.neptunekit.sdk.android.examples.simulator.databinding.ActivityMainBinding
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
import com.neptunekit.sdk.android.http.ExportHttpServer
import com.neptunekit.sdk.android.viewtree.HttpUrlConnectionGatewayRawUiTreeUploader
import com.neptunekit.sdk.android.viewtree.RawUiTreeIngestRequest
import com.neptunekit.sdk.android.viewtree.ViewTreeQuery
import com.neptunekit.sdk.android.ws.GatewayWebSocketClient

private const val TAG = "NeptuneSimulatorDemo"
private const val SIMULATOR_PLATFORM = "android"
private const val SIMULATOR_APP_ID = "com.neptunekit.sdk.android.examples.simulator"
private const val SIMULATOR_SESSION_ID = "simulator-session"
private const val SIMULATOR_DEVICE_ID = "simulator-device"
private const val SIMULATOR_GATEWAY_HOST = "10.0.2.2"
private const val SIMULATOR_GATEWAY_PORT = 18765
private const val SIMULATOR_CALLBACK_DEVICE_PORT = 18766
private const val SIMULATOR_CALLBACK_HOST_PORT = 28766
private const val SIMULATOR_CALLBACK_URL = "http://127.0.0.1:$SIMULATOR_CALLBACK_HOST_PORT/v2/client/command"

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val sharedExportService by lazy { createExportService(queueCapacity = 16) }
    private val controller by lazy { SimulatorDemoController(service = sharedExportService) }
    private val viewTreeCollector by lazy { AndroidViewTreeCollector { this } }
    private val rawUiTreeUploader by lazy { HttpUrlConnectionGatewayRawUiTreeUploader() }
    private val callbackHttpServer by lazy {
        ExportHttpServer(
            sharedExportService,
            host = "0.0.0.0",
            viewTreeCollector = viewTreeCollector,
        )
    }
    private val registrationSession by lazy {
        createClientRegistrationSession(
            gatewayEndpoint = GatewayDiscoveryEndpoint(
                host = SIMULATOR_GATEWAY_HOST,
                port = SIMULATOR_GATEWAY_PORT,
            ),
            callbackEndpoint = SIMULATOR_CALLBACK_URL,
            platform = SIMULATOR_PLATFORM,
            appId = SIMULATOR_APP_ID,
            sessionId = SIMULATOR_SESSION_ID,
            deviceId = SIMULATOR_DEVICE_ID,
        )
    }
    private val gatewayWebSocketClient by lazy {
        GatewayWebSocketClient(
            listener = object : GatewayWebSocketClient.Listener {
                override fun onConnected(endpoint: GatewayDiscoveryEndpoint) {
                    Log.i(TAG, "ws connected endpoint=${endpoint.host}:${endpoint.port}")
                }

                override fun onDisconnected(reason: String) {
                    Log.i(TAG, "ws disconnected reason=$reason")
                }

                override fun onError(error: Throwable) {
                    Log.w(TAG, "ws error=${error.message ?: error::class.java.simpleName}", error)
                }
            },
            platform = SIMULATOR_PLATFORM,
            appId = SIMULATOR_APP_ID,
            sessionId = SIMULATOR_SESSION_ID,
            deviceId = SIMULATOR_DEVICE_ID,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callbackHttpServer.start(SIMULATOR_CALLBACK_DEVICE_PORT)
        registrationSession.start()
        Log.i(
            TAG,
            "callback server started devicePort=$SIMULATOR_CALLBACK_DEVICE_PORT callbackUrl=$SIMULATOR_CALLBACK_URL",
        )

        render(controller.snapshot())
        gatewayWebSocketClient.connect(SimulatorGatewayWebSocketDefaults.endpoint())

        binding.emitLogButton.setOnClickListener {
            val state = controller.emitLog()
            Log.i(
                TAG,
                "writeBatch clickCount=${state.clickCount} queuedRecords=${state.metrics.queuedRecords} " +
                    "droppedOverflow=${state.metrics.droppedOverflow} queueSize=${state.health.queueSize}",
            )
            render(state)
        }

        binding.discoverGatewayButton.setOnClickListener {
            binding.discoverGatewayButton.isEnabled = false
            Thread {
                val state = controller.discoverGateway()
                Log.i(
                    TAG,
                    buildString {
                        append("discoverGateway status=${state.gatewayDiscovery.status}")
                        state.gatewayDiscovery.source?.let { append(" source=$it") }
                        state.gatewayDiscovery.host?.let { append(" host=$it") }
                        state.gatewayDiscovery.port?.let { append(" port=$it") }
                        state.gatewayDiscovery.version?.let { append(" version=$it") }
                        state.gatewayDiscovery.error?.let { append(" error=$it") }
                    },
                )
                Log.i(
                    TAG,
                    buildString {
                        append("ingestGateway status=${state.gatewayIngest.status}")
                        state.gatewayIngest.endpoint?.let { append(" endpoint=$it") }
                        state.gatewayIngest.responseCode?.let { append(" responseCode=$it") }
                        state.gatewayIngest.error?.let { append(" error=$it") }
                    },
                )
                uploadRawInspectorSnapshotIfPossible(state)
                runOnUiThread {
                    binding.discoverGatewayButton.isEnabled = true
                    render(state)
                }
            }.start()
        }

        binding.refreshSnapshotButton.setOnClickListener {
            val state = controller.refreshSnapshot()
            Log.i(
                TAG,
                "refreshSnapshot clickCount=${state.clickCount} queuedRecords=${state.metrics.queuedRecords} " +
                    "droppedOverflow=${state.metrics.droppedOverflow} queueSize=${state.health.queueSize}",
            )
            render(state)
        }
    }

    private fun render(state: SimulatorDemoUiState) {
        binding.bannerChip.text = when (state.gatewayDiscovery.status) {
            "ok" -> "discover ok"
            "error" -> "discover failed"
            else -> "ready"
        }
        binding.batchChip.text = "batch #${state.clickCount}"

        binding.gatewayDiscoveryText.text = buildString {
            append("status=${state.gatewayDiscovery.status}")
            state.gatewayDiscovery.source?.let { append("  source=$it") }
            state.gatewayDiscovery.host?.let { append("  host=$it") }
            state.gatewayDiscovery.port?.let { append("  port=$it") }
            state.gatewayDiscovery.version?.let { append("  version=$it") }
            state.gatewayDiscovery.error?.let { append("  error=$it") }
        }

        binding.gatewayIngestText.text = buildString {
            append("ingest status=${state.gatewayIngest.status}")
            state.gatewayIngest.endpoint?.let { append("  endpoint=$it") }
            state.gatewayIngest.responseCode?.let { append("  response=$it") }
            state.gatewayIngest.error?.let { append("  error=$it") }
        }
        binding.sourcesText.text = buildString {
            if (state.gatewayDiscovery.status == "ok") {
                append("source=${state.gatewayDiscovery.source ?: "unknown"}")
                state.gatewayDiscovery.host?.let { append(" host=$it") }
                state.gatewayDiscovery.port?.let { append(":$it") }
            } else {
                append("no source yet")
            }
        }

        binding.queueStatusText.text = buildString {
            appendLine("queueSize=${state.health.queueSize}")
            appendLine("totalIngested=${state.metrics.queuedRecords}")
            appendLine("droppedOverflow=${state.metrics.droppedOverflow}")
            appendLine("totalExported=${state.metrics.queuedRecords}")
        }

        binding.recentLogsText.text = if (state.recentLogs.isEmpty()) {
            "- none yet"
        } else {
            state.recentLogs.joinToString(separator = "\n") { logLine ->
                "- #${logLine.id} ${logLine.level} ${logLine.message}"
            }
        }
    }

    private fun uploadRawInspectorSnapshotIfPossible(state: SimulatorDemoUiState) {
        if (state.gatewayDiscovery.status != "ok") {
            return
        }
        val host = state.gatewayDiscovery.host ?: return
        val port = state.gatewayDiscovery.port ?: return
        val endpoint = GatewayDiscoveryEndpoint(host = host, port = port)

        val query = ViewTreeQuery(
            platform = SIMULATOR_PLATFORM,
            appId = SIMULATOR_APP_ID,
            sessionId = SIMULATOR_SESSION_ID,
            deviceId = SIMULATOR_DEVICE_ID,
        )

        val snapshot = runCatching { viewTreeCollector.captureInspector(query) }.getOrNull() ?: return
        if (!snapshot.available || snapshot.payload == null) {
            return
        }

        val request = RawUiTreeIngestRequest(
            platform = snapshot.platform,
            appId = SIMULATOR_APP_ID,
            sessionId = SIMULATOR_SESSION_ID,
            deviceId = SIMULATOR_DEVICE_ID,
            snapshotId = snapshot.snapshotId,
            capturedAt = snapshot.capturedAt,
            payload = snapshot.payload,
        )

        try {
            val result = rawUiTreeUploader.upload(endpoint, request)
            Log.i(TAG, "raw ui tree uploaded status=${result.statusCode} endpoint=${result.requestUrl}")
        } catch (error: Throwable) {
            Log.w(TAG, "raw ui tree upload failed endpoint=${endpoint.host}:${endpoint.port}", error)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized) {
            gatewayWebSocketClient.close()
            registrationSession.close()
            callbackHttpServer.stop()
            controller.close()
        }
    }
}
