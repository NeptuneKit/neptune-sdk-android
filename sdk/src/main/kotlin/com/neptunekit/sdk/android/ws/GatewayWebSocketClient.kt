package com.neptunekit.sdk.android.ws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GatewayWebSocketReconnectPolicy(
    private val delaysMillis: List<Long> = DEFAULT_DELAYS_MILLIS,
) {
    fun delayMillis(attempt: Int): Long {
        val index = attempt.coerceAtLeast(0)
        return delaysMillis.getOrElse(index) { delaysMillis.lastOrNull() ?: 0L }
    }

    private companion object {
        val DEFAULT_DELAYS_MILLIS = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
    }
}

class GatewayWebSocketClient(
    private val heartbeatIntervalMillis: Long = 15_000,
    private val staleTimeoutMillis: Long = 45_000,
    private val reconnectPolicy: GatewayWebSocketReconnectPolicy = GatewayWebSocketReconnectPolicy(),
    private val listener: Listener = Listener.NO_OP,
    private val platform: String = DEFAULT_PLATFORM,
    private val appId: String = DEFAULT_APP_ID,
    private val sessionId: String = DEFAULT_SESSION_ID,
    private val deviceId: String = DEFAULT_DEVICE_ID,
) : Closeable {
    interface Listener {
        fun onConnected(endpoint: GatewayDiscoveryEndpoint) {}
        fun onDisconnected(reason: String) {}
        fun onMessage(message: String) {}
        fun onError(error: Throwable) {}

        companion object {
            val NO_OP: Listener = object : Listener {}
        }
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "neptune-gateway-ws").apply {
            isDaemon = true
        }
    }
    private val okHttpClient = defaultOkHttpClient()
    private val objectMapper = ObjectMapper()
    private val closed = AtomicBoolean(false)

    private var endpoint: GatewayDiscoveryEndpoint? = null
    private var currentWebSocket: WebSocket? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var watchdogFuture: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var reconnectAttempt = 0

    fun connect(endpoint: GatewayDiscoveryEndpoint) {
        post {
            if (closed.get()) {
                return@post
            }

            this.endpoint = endpoint
            reconnectAttempt = 0
            cancelTimersLocked()
            currentWebSocket?.cancel()
            currentWebSocket = null
            openConnectionLocked()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        val cleanup = executor.submit<Unit> {
            closeLocked("closed")
        }
        runCatching { cleanup.get(1, TimeUnit.SECONDS) }
        executor.shutdownNow()
    }

    private fun openConnectionLocked() {
        val currentEndpoint = endpoint ?: return
        val request = Request.Builder()
            .url(currentEndpoint.websocketUrl())
            .build()

        val webSocket = okHttpClient.newWebSocket(request, clientListener)
        currentWebSocket = webSocket
    }

    private fun handleOpen(webSocket: WebSocket) {
        if (closed.get()) {
            webSocket.cancel()
            return
        }

        if (currentWebSocket !== webSocket) {
            currentWebSocket = webSocket
        }

        reconnectAttempt = 0
        cancelTimersLocked()
        scheduleHeartbeatLocked()
        scheduleWatchdogLocked()
        currentWebSocket?.send(buildHelloMessage())

        endpoint?.let(listener::onConnected)
    }

    private fun handleText(webSocket: WebSocket, text: String) {
        if (closed.get() || currentWebSocket !== webSocket) {
            return
        }

        listener.onMessage(text)
        scheduleWatchdogLocked()

        val node = runCatching { objectMapper.readTree(text) }.getOrNull() ?: return
        when (node.textField("type")) {
            "command.dispatch" -> handleCommandDispatch(webSocket, node)
        }
    }

    private fun handleCommandDispatch(webSocket: WebSocket, node: JsonNode) {
        val command = node.textField("command") ?: node.textField("name")
        if (command != "ping") {
            return
        }

        webSocket.send(
            buildObjectNode()
                .put("type", "command.ack")
                .put("command", "ping")
                .put("status", "ok")
                .put("timestamp", Instant.now().toString())
                .apply {
                    node.textField("requestId")?.let { put("requestId", it) }
                }
                .toString(),
        )
    }

    private fun handleClosed(webSocket: WebSocket, reason: String) {
        if (closed.get()) {
            return
        }

        if (currentWebSocket === webSocket) {
            currentWebSocket = null
            cancelTimersLocked()
            listener.onDisconnected(reason)
            scheduleReconnectLocked()
        }
    }

    private fun handleFailure(webSocket: WebSocket, throwable: Throwable, reason: String) {
        if (closed.get()) {
            return
        }

        listener.onError(throwable)
        handleClosed(webSocket, reason)
    }

    private fun scheduleHeartbeatLocked() {
        heartbeatFuture = executor.scheduleWithFixedDelay(
            {
                if (closed.get()) {
                    return@scheduleWithFixedDelay
                }

                currentWebSocket?.send(buildHeartbeatMessage())
            },
            heartbeatIntervalMillis,
            heartbeatIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleWatchdogLocked() {
        watchdogFuture?.cancel(false)
        watchdogFuture = executor.schedule(
            {
                if (closed.get()) {
                    return@schedule
                }

                currentWebSocket?.cancel()
            },
            staleTimeoutMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleReconnectLocked() {
        if (reconnectFuture != null || closed.get() || endpoint == null) {
            return
        }

        val delay = reconnectPolicy.delayMillis(reconnectAttempt)
        reconnectAttempt += 1
        reconnectFuture = executor.schedule(
            {
                reconnectFuture = null
                if (closed.get()) {
                    return@schedule
                }

                openConnectionLocked()
            },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelTimersLocked() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        watchdogFuture?.cancel(false)
        watchdogFuture = null
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun post(block: () -> Unit) {
        runCatching { executor.execute(block) }
    }

    private fun buildHelloMessage(): String =
        buildObjectNode()
            .put("type", "hello")
            .put("role", "sdk")
            .put("platform", platform)
            .put("appId", appId)
            .put("sessionId", sessionId)
            .put("deviceId", deviceId)
            .toString()

    private fun buildHeartbeatMessage(): String =
        buildObjectNode()
            .put("type", "heartbeat")
            .put("role", "sdk")
            .put("timestamp", Instant.now().toString())
            .toString()

    private fun buildObjectNode() = objectMapper.createObjectNode()

    private fun JsonNode.textField(name: String): String? {
        val value = path(name).takeIf { !it.isMissingNode && !it.isNull }?.asText()?.trim().orEmpty()
        return value.takeIf { it.isNotBlank() }
    }

    private val clientListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            post { handleOpen(webSocket) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            post { handleText(webSocket, text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            post { handleClosed(webSocket, "closed $code ${reason.ifBlank { "normal" }}") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            post { handleFailure(webSocket, t, t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName) }
        }
    }

    private fun closeLocked(reason: String, socket: WebSocket? = currentWebSocket) {
        cancelTimersLocked()
        currentWebSocket = null
        endpoint = null
        reconnectAttempt = 0
        socket?.cancel()
        listener.onDisconnected(reason.ifBlank { "closed" })
    }
}

private fun defaultOkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

private const val DEFAULT_PLATFORM = "android"
private const val DEFAULT_APP_ID = "unknown"
private const val DEFAULT_SESSION_ID = "unknown"
private const val DEFAULT_DEVICE_ID = "unknown"

private fun GatewayDiscoveryEndpoint.websocketUrl(): String =
    "ws://${host.forUrl()}:$port/v2/ws"

private fun String.forUrl(): String =
    if (contains(':') && !startsWith('[')) {
        "[$this]"
    } else {
        this
    }
