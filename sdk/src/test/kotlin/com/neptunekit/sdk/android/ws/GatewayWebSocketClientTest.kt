package com.neptunekit.sdk.android.ws

import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayWebSocketClientTest {
    private val objectMapper = ObjectMapper()
    private val servers = mutableListOf<MockWebServer>()

    @BeforeTest
    fun setUp() {
        servers.clear()
    }

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
        servers.clear()
    }

    @Test
    fun connectsToV2WsAndSendsHelloWithSdkRole() {
        val messages = ConcurrentLinkedQueue<String>()
        val helloLatch = CountDownLatch(1)
        val server = createServer(
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages += text
                    if (isType(text, "hello")) {
                        helloLatch.countDown()
                    }
                }
            },
        )

        val client = GatewayWebSocketClient(
            heartbeatIntervalMillis = 1_000,
            staleTimeoutMillis = 5_000,
            reconnectPolicy = GatewayWebSocketReconnectPolicy(listOf(25L)),
            platform = "android",
            appId = "com.neptunekit.sdk.android.examples.simulator",
            sessionId = "simulator-session",
            deviceId = "simulator-device",
        )

        client.connect(endpoint(server))

        assertTrue(helloLatch.await(2, TimeUnit.SECONDS), "expected hello message")
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("/v2/ws", request?.path)

        val hello = messages.single { isType(it, "hello") }
        assertEquals("sdk", field(hello, "role"))
        assertEquals("android", field(hello, "platform"))
        assertEquals("com.neptunekit.sdk.android.examples.simulator", field(hello, "appId"))
        assertEquals("simulator-session", field(hello, "sessionId"))
        assertEquals("simulator-device", field(hello, "deviceId"))

        client.close()
    }

    @Test
    fun sendsHeartbeatFramesOnTheConfiguredInterval() {
        val heartbeatLatch = CountDownLatch(1)
        val server = createServer(
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isType(text, "heartbeat")) {
                        heartbeatLatch.countDown()
                    }
                }
            },
        )

        val client = GatewayWebSocketClient(
            heartbeatIntervalMillis = 50,
            staleTimeoutMillis = 1_000,
            reconnectPolicy = GatewayWebSocketReconnectPolicy(listOf(25L)),
        )

        client.connect(endpoint(server))

        assertTrue(heartbeatLatch.await(2, TimeUnit.SECONDS), "expected heartbeat frame")
        client.close()
    }

    @Test
    fun repliesCommandAckForPingDispatch() {
        val ackLatch = CountDownLatch(1)
        val messages = ConcurrentLinkedQueue<String>()
        val server = createServer(
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    messages += text
                    if (isType(text, "hello")) {
                        webSocket.send(
                            """{"type":"command.dispatch","requestId":"req-1","command":"ping"}""",
                        )
                    }
                    if (isType(text, "command.ack")) {
                        ackLatch.countDown()
                    }
                }
            },
        )

        val client = GatewayWebSocketClient(
            heartbeatIntervalMillis = 1_000,
            staleTimeoutMillis = 5_000,
            reconnectPolicy = GatewayWebSocketReconnectPolicy(listOf(25L)),
        )

        client.connect(endpoint(server))

        assertTrue(ackLatch.await(2, TimeUnit.SECONDS), "expected command ack")
        val ack = messages.single { isType(it, "command.ack") }
        assertEquals("ping", field(ack, "command"))
        assertEquals("req-1", field(ack, "requestId"))
        assertEquals("ok", field(ack, "status"))
        assertFalse(field(ack, "timestamp").isBlank())

        client.close()
    }

    @Test
    fun reconnectsAfterStaleTimeoutUsingTheConfiguredBackoffPolicy() {
        val openCount = AtomicInteger(0)
        val helloLatch = CountDownLatch(2)
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        openCount.incrementAndGet()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (isType(text, "hello")) {
                            helloLatch.countDown()
                        }
                    }
                },
            ),
        )
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        openCount.incrementAndGet()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (isType(text, "hello")) {
                            helloLatch.countDown()
                        }
                    }
                },
            ),
        )
        servers += server
        server.start()

        val client = GatewayWebSocketClient(
            heartbeatIntervalMillis = 30,
            staleTimeoutMillis = 120,
            reconnectPolicy = GatewayWebSocketReconnectPolicy(listOf(20L)),
        )

        client.connect(endpoint(server))

        assertTrue(helloLatch.await(3, TimeUnit.SECONDS), "expected two hello messages across reconnects")
        waitUntil(3_000) { openCount.get() >= 2 }

        client.close()
    }

    private fun createServer(listener: WebSocketListener): MockWebServer {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        servers += server
        server.start()
        return server
    }

    private fun endpoint(server: MockWebServer): GatewayDiscoveryEndpoint =
        GatewayDiscoveryEndpoint("127.0.0.1", server.port)

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadlineNanos) {
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }

        assertTrue(condition(), "condition was not met within ${timeoutMillis}ms")
    }

    private fun isType(text: String, type: String): Boolean =
        field(text, "type") == type

    private fun field(text: String, name: String): String =
        objectMapper.readTree(text).path(name).asText()
}
