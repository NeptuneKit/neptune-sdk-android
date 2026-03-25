package com.neptunekit.sdk.android.ws

import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayWebSocketReconnectPolicyTest {
    @Test
    fun usesTheExpectedExponentialBackoffSequence() {
        val policy = GatewayWebSocketReconnectPolicy()

        assertEquals(500L, policy.delayMillis(0))
        assertEquals(1_000L, policy.delayMillis(1))
        assertEquals(2_000L, policy.delayMillis(2))
        assertEquals(4_000L, policy.delayMillis(3))
        assertEquals(8_000L, policy.delayMillis(4))
        assertEquals(8_000L, policy.delayMillis(5))
        assertEquals(8_000L, policy.delayMillis(42))
    }
}
