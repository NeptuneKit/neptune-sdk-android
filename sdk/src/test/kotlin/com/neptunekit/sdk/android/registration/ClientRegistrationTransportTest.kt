package com.neptunekit.sdk.android.registration

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientRegistrationTransportTest {
    private val mapper = ObjectMapper()
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun postsExpectedPayloadToClientRegistrationEndpoint() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        server.start()

        val transport = OkHttpClientRegistrationTransport(
            client = OkHttpClient(),
            registerUrl = server.url("/v2/clients:register"),
        )

        val response = transport.register(
            ClientRegistrationPayload(
                platform = "android",
                appId = "com.neptunekit.sdk.android.examples.simulator",
                deviceId = "simulator-device",
                sessionId = "session-001",
                callbackEndpoint = "http://10.0.2.2:18765/v2/client/command",
                preferredTransports = listOf("httpCallback"),
            ),
        )

        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("ok"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v2/clients:register", request.path)

        val payload = mapper.readTree(request.body.readUtf8())
        assertEquals("android", payload["platform"].asText())
        assertEquals("com.neptunekit.sdk.android.examples.simulator", payload["appId"].asText())
        assertEquals("simulator-device", payload["deviceId"].asText())
        assertEquals("session-001", payload["sessionId"].asText())
        assertEquals("http://10.0.2.2:18765/v2/client/command", payload["callbackEndpoint"].asText())
        assertEquals("httpCallback", payload["preferredTransports"][0].asText())
    }
}
