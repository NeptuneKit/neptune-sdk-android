package com.neptunekit.sdk.android.examples.simulator

import com.neptunekit.sdk.android.createExportService
import com.neptunekit.sdk.android.http.ExportHttpServer
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertTrue

class MainActivitySharedQueueTest {
    @Test
    fun emitLogCanBeReadFromCallbackLogsEndpointWhenServiceIsShared() {
        val service = createExportService(queueCapacity = 16)
        val controller = SimulatorDemoController(service = service)
        val server = ExportHttpServer(service = service, host = "127.0.0.1")
        val port = allocatePort()

        server.start(port)
        try {
            controller.emitLog()

            val body = getText("http://127.0.0.1:$port/v2/logs?cursor=0&limit=10")
            assertTrue(
                body.contains("neptune-simulator-click-1"),
                "expected callback /v2/logs to expose emitted log, actual body=$body",
            )
        } finally {
            server.stop()
            controller.close()
        }
    }

    private fun allocatePort(): Int =
        ServerSocket(0).use { socket ->
            socket.localPort
        }

    private fun getText(rawUrl: String): String {
        val connection = URL(rawUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
