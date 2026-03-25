package com.neptunekit.sdk.android.examples.simulator

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.neptunekit.sdk.android.createClientRegistrationSession
import com.neptunekit.sdk.android.createExportHttpServer
import com.neptunekit.sdk.android.examples.simulator.databinding.ActivityMainBinding
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
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
    private val controller by lazy { SimulatorDemoController() }
    private val callbackHttpServer by lazy {
        createExportHttpServer(queueCapacity = 16, host = "0.0.0.0")
    }
    private val registrationSession by lazy {
        createClientRegistrationSession(
            gatewayEndpoint = GatewayDiscoveryEndpoint(
                host = SIMULATOR_GATEWAY_HOST,
                port = SIMULATOR_GATEWAY_PORT,
            ),
            callbackUrl = SIMULATOR_CALLBACK_URL,
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
                "emitLog clickCount=${state.clickCount} queuedRecords=${state.metrics.queuedRecords} " +
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
                runOnUiThread {
                    binding.discoverGatewayButton.isEnabled = true
                    render(state)
                }
            }.start()
        }
    }

    private fun render(state: SimulatorDemoUiState) {
        binding.queueStatusText.text = buildString {
            appendLine("Clicks: ${state.clickCount}")
            appendLine("Health: ok=${state.health.ok} capacity=${state.health.queueCapacity} size=${state.health.queueSize}")
            appendLine("Metrics: queuedRecords=${state.metrics.queuedRecords} droppedOverflow=${state.metrics.droppedOverflow}")
            appendLine("Gateway discovery:")
            appendLine("  status=${state.gatewayDiscovery.status}")
            state.gatewayDiscovery.source?.let { appendLine("  source=$it") }
            state.gatewayDiscovery.host?.let { appendLine("  host=$it") }
            state.gatewayDiscovery.port?.let { appendLine("  port=$it") }
            state.gatewayDiscovery.version?.let { appendLine("  version=$it") }
            state.gatewayDiscovery.error?.let { appendLine("  error=$it") }
            appendLine("Gateway ingest:")
            appendLine("  status=${state.gatewayIngest.status}")
            state.gatewayIngest.endpoint?.let { appendLine("  endpoint=$it") }
            state.gatewayIngest.responseCode?.let { appendLine("  responseCode=$it") }
            state.gatewayIngest.error?.let { appendLine("  error=$it") }
            appendLine("Recent logs:")
            if (state.recentLogs.isEmpty()) {
                appendLine("- none yet")
            } else {
                state.recentLogs.forEach { logLine ->
                    appendLine("- #${logLine.id} ${logLine.level} ${logLine.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized) {
            gatewayWebSocketClient.close()
            registrationSession.close()
            callbackHttpServer.close()
            controller.close()
        }
    }
}
