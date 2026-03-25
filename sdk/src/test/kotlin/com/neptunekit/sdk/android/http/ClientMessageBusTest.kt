package com.neptunekit.sdk.android.http

import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientMessageBusTest {
    @Test
    fun acknowledgeInboundPingCommandReturnsOkAck() {
        val bus = ClientMessageBus()
        val ack = bus.acknowledgeInboundCommand(
            BusEnvelope(
                requestId = "req-1",
                direction = "cli_to_client",
                kind = "command",
                command = "ping",
            ),
        )

        assertEquals("req-1", ack.requestId)
        assertEquals("ping", ack.command)
        assertEquals("ok", ack.status)
    }

    @Test
    fun makeLogEnvelopeUsesClientToCliDirection() {
        val bus = ClientMessageBus()
        val envelope = bus.makeLogEnvelope(
            IngestLogRecord(
                timestamp = "2026-03-25T03:00:00Z",
                level = LogLevel.INFO,
                message = "hello",
                platform = Platform.ANDROID,
                appId = "demo.app",
                sessionId = "s-1",
                deviceId = "d-1",
                category = "default",
            ),
        )

        assertEquals("client_to_cli", envelope.direction)
        assertEquals("log", envelope.kind)
        assertEquals("hello", envelope.logRecord?.message)
    }
}
