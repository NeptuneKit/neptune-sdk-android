package com.neptunekit.sdk.android.examples.simulator

import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint

internal object SimulatorGatewayWebSocketDefaults {
    const val host = "10.0.2.2"
    const val port = 18765

    fun endpoint(): GatewayDiscoveryEndpoint = GatewayDiscoveryEndpoint(host, port)
}
