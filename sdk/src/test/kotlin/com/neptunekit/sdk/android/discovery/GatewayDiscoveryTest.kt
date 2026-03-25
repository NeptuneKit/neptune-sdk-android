package com.neptunekit.sdk.android.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GatewayDiscoveryTest {
    @Test
    fun mdnsTakesPriorityOverManualDsn() {
        val service = GatewayDiscovery(
            mdnsResolver = GatewayDiscoveryMdnsResolver { config ->
                listOf(
                    GatewayDiscoveryCandidate(
                        endpoint = GatewayDiscoveryEndpoint("mdns-host", 18765),
                        source = GatewayDiscoverySource.MDNS,
                        serviceName = config.mdnsServiceType,
                    ),
                )
            },
            httpClient = fakeHttpClient(
                "http://mdns-host:18765/v2/gateway/discovery" to response(200, """{"host":"127.0.0.1","port":18765,"version":"2.0.0-alpha.1"}"""),
            ),
        )

        val result = service.discover(
            GatewayDiscoveryConfig(
                manualDsn = "manual-host:18765",
                timeoutMillis = 100,
            ),
        )

        assertEquals(GatewayDiscoverySource.MDNS, result.source)
        assertEquals("127.0.0.1", result.host)
        assertEquals(18765, result.port)
        assertEquals("2.0.0-alpha.1", result.version)
    }

    @Test
    fun fallsBackToManualDsnWhenMdnsFails() {
        val service = GatewayDiscovery(
            mdnsResolver = GatewayDiscoveryMdnsResolver { emptyList() },
            httpClient = fakeHttpClient(
                "http://manual-host:18765/v2/gateway/discovery" to response(200, """{"host":"10.0.0.8","port":18765,"version":"2.0.0-alpha.1"}"""),
            ),
        )

        val result = service.discover(
            GatewayDiscoveryConfig(
                manualDsn = "manual-host:18765",
                timeoutMillis = 100,
            ),
        )

        assertEquals(GatewayDiscoverySource.MANUAL_DSN, result.source)
        assertEquals("10.0.0.8", result.host)
    }

    @Test
    fun fallsBackToCandidateHostWhenDiscoveryReturnsLoopbackHost() {
        val service = GatewayDiscovery(
            mdnsResolver = GatewayDiscoveryMdnsResolver { emptyList() },
            httpClient = fakeHttpClient(
                "http://10.0.2.2:18765/v2/gateway/discovery" to response(
                    200,
                    """{"host":"127.0.0.1","port":20000,"version":"2.0.0-alpha.1"}""",
                ),
            ),
        )

        val result = service.discover(
            GatewayDiscoveryConfig(
                manualDsn = "10.0.2.2:18765",
                timeoutMillis = 100,
            ),
        )

        assertEquals(GatewayDiscoverySource.MANUAL_DSN, result.source)
        assertEquals("127.0.0.1", result.host)
        assertEquals("10.0.2.2", result.endpoint.host)
        assertEquals(20000, result.endpoint.port)
        assertEquals(20000, result.port)
    }

    @Test
    fun skipsInvalidDiscoveryResponsesBeforeContinuing() {
        val discovery = GatewayDiscovery(
            mdnsResolver = GatewayDiscoveryMdnsResolver {
                listOf(
                    GatewayDiscoveryCandidate(
                        endpoint = GatewayDiscoveryEndpoint("bad-host", 18765),
                        source = GatewayDiscoverySource.MDNS,
                        serviceName = "bad",
                    ),
                    GatewayDiscoveryCandidate(
                        endpoint = GatewayDiscoveryEndpoint("good-host", 18765),
                        source = GatewayDiscoverySource.MDNS,
                        serviceName = "good",
                    ),
                )
            },
            httpClient = fakeHttpClient(
                "http://bad-host:18765/v2/gateway/discovery" to response(200, """{"host":"","port":0,"version":""}"""),
                "http://good-host:18765/v2/gateway/discovery" to response(200, """{"host":"127.0.0.1","port":18765,"version":"2.0.0-alpha.1"}"""),
            ),
        )

        val result = discovery.discover()

        assertEquals("127.0.0.1", result.host)
        assertEquals(18765, result.port)
        assertEquals(GatewayDiscoverySource.MDNS, result.source)
    }

    @Test
    fun reportsAllFailuresWithUsefulErrorMessage() {
        val service = GatewayDiscovery(
            mdnsResolver = GatewayDiscoveryMdnsResolver { emptyList() },
            httpClient = fakeHttpClient(
                "http://manual-host:18765/v2/gateway/discovery" to response(500, "boom"),
            ),
        )

        val error = assertFailsWith<GatewayDiscoveryException> {
            service.discover(
                GatewayDiscoveryConfig(
                    manualDsn = "manual-host:18765",
                    timeoutMillis = 100,
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("manual-host"))
        assertTrue(error.message.orEmpty().contains("http 500"))
        assertEquals(1, error.attempts.size)
    }

    private fun response(statusCode: Int, body: String) = GatewayDiscoveryHttpResponse(statusCode, body)

    private fun fakeHttpClient(vararg responses: Pair<String, GatewayDiscoveryHttpResponse>): GatewayDiscoveryHttpClient {
        val table = responses.toMap()
        return GatewayDiscoveryHttpClient { candidate, _ ->
            table[candidate.endpoint.discoveryUrl()]
                ?: error("unexpected request: ${candidate.endpoint.discoveryUrl()}")
        }
    }
}
