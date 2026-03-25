package com.neptunekit.sdk.android.registration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientRegistrationSessionTest {
    @Test
    fun startRegistersImmediatelyAndRenewsEveryThirtySeconds() {
        val transport = RecordingTransport()
        val scheduler = RecordingScheduler()
        val session = ClientRegistrationSession(
            identity = ClientIdentity(
                platform = "android",
                appId = "com.neptunekit.sdk.android.examples.simulator",
                deviceId = "simulator-device",
                sessionId = "session-001",
            ),
            callbackUrl = "http://10.0.2.2:18765/v2/client/command",
            transport = transport,
            scheduler = scheduler,
        )

        assertEquals("android|com.neptunekit.sdk.android.examples.simulator|simulator-device", session.identity.primaryKey)

        session.start()

        assertEquals(1, transport.payloads.size)
        assertEquals(30_000L, scheduler.initialDelayMillis)
        assertEquals(30_000L, scheduler.periodMillis)

        val initialPayload = transport.payloads.single()
        assertEquals("android", initialPayload.platform)
        assertEquals("com.neptunekit.sdk.android.examples.simulator", initialPayload.appId)
        assertEquals("simulator-device", initialPayload.deviceId)
        assertEquals("session-001", initialPayload.sessionId)
        assertEquals("http://10.0.2.2:18765/v2/client/command", initialPayload.callbackUrl)

        scheduler.runScheduledTask()

        assertEquals(2, transport.payloads.size)
        assertTrue(transport.payloads.all { it.platform == "android" })

        session.close()
    }

    private class RecordingTransport : ClientRegistrationTransport {
        val payloads = mutableListOf<ClientRegistrationPayload>()

        override fun register(payload: ClientRegistrationPayload): ClientRegistrationResponse {
            payloads += payload
            return ClientRegistrationResponse(statusCode = 200, body = """{"ok":true}""")
        }
    }

    private class RecordingScheduler : ClientRegistrationScheduler {
        var initialDelayMillis: Long? = null
            private set
        var periodMillis: Long? = null
            private set
        private var task: (() -> Unit)? = null

        override fun scheduleAtFixedRate(
            initialDelayMillis: Long,
            periodMillis: Long,
            task: () -> Unit,
        ): ClientRegistrationSchedule {
            this.initialDelayMillis = initialDelayMillis
            this.periodMillis = periodMillis
            this.task = task
            return ClientRegistrationSchedule {
                this.task = null
            }
        }

        fun runScheduledTask() {
            task?.invoke()
        }
    }
}
