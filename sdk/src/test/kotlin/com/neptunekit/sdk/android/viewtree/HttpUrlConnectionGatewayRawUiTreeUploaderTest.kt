package com.neptunekit.sdk.android.viewtree

import com.fasterxml.jackson.databind.ObjectMapper
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpUrlConnectionGatewayRawUiTreeUploaderTest {
    @Test
    fun uploadsPayloadToGateway() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{\"ok\":true}"""))
        server.start()

        try {
            val endpoint = GatewayDiscoveryEndpoint(
                host = server.hostName,
                port = server.port,
            )
            val request = RawUiTreeIngestRequest(
                platform = "android",
                appId = "demo.app",
                sessionId = "session123",
                deviceId = "device-1",
                snapshotId = "snap-003",
                capturedAt = "2026-03-27T00:00:00Z",
                payload = mapOf(
                    "activityClass" to "MainActivity",
                    "roots" to listOf(mapOf("id" to "root", "children" to emptyList<Any>(), "visible" to false, "alpha" to 0)),
                    "debug" to false,
                ),
            )

            val uploader = HttpUrlConnectionGatewayRawUiTreeUploader()
            val result = uploader.upload(endpoint, request)

            assertEquals("http://${server.hostName}:${server.port}/v2/ui-tree/inspector", result.requestUrl)
            assertEquals(200, result.statusCode)

            val recordedRequest = server.takeRequest()
            assertEquals("POST", recordedRequest.method)
            assertEquals("/v2/ui-tree/inspector", recordedRequest.path)

            val mapper = ObjectMapper()
            val body = mapper.readTree(recordedRequest.body.readUtf8())
            assertEquals("android", body["platform"].asText())
            assertEquals("demo.app", body["appId"].asText())
            assertEquals("session123", body["sessionId"].asText())
            assertEquals("device-1", body["deviceId"].asText())
            assertEquals("snap-003", body["snapshotId"].asText())
            assertEquals("2026-03-27T00:00:00Z", body["capturedAt"].asText())
            assertEquals("MainActivity", body["payload"]["activityClass"].asText())
            assertTrue(body["payload"]["roots"].isArray)
            assertEquals(false, body["payload"].has("debug"))
            assertEquals(false, body["payload"]["roots"][0].has("children"))
            assertEquals(false, body["payload"]["roots"][0].has("visible"))
            assertEquals(false, body["payload"]["roots"][0].has("alpha"))
        } finally {
            server.shutdown()
        }
    }
}
