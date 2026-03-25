package com.neptunekit.sdk.android.examples.simulator

import kotlin.test.Test
import kotlin.test.assertEquals

class SimulatorGatewayWebSocketDefaultsTest {
    @Test
    fun usesTheAndroidEmulatorLoopbackDefault() {
        val endpoint = SimulatorGatewayWebSocketDefaults.endpoint()

        assertEquals("10.0.2.2", endpoint.host)
        assertEquals(18765, endpoint.port)
    }
}
