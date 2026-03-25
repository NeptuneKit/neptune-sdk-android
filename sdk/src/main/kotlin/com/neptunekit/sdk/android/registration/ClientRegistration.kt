package com.neptunekit.sdk.android.registration

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val DEFAULT_CLIENT_REGISTRATION_RENEW_INTERVAL_MILLIS = 30_000L

data class ClientIdentity(
    val platform: String,
    val appId: String,
    val deviceId: String,
    val sessionId: String? = null,
) {
    val primaryKey: String = listOf(platform, appId, deviceId).joinToString("|")
}

data class ClientRegistrationPayload(
    val platform: String,
    val appId: String,
    val deviceId: String,
    val sessionId: String? = null,
    val callbackEndpoint: String,
    val preferredTransports: List<String> = emptyList(),
    val usbmuxdHint: ClientUSBMuxdHint? = null,
)

data class ClientUSBMuxdHint(
    val deviceID: Int,
    val socketPath: String? = null,
)

data class ClientRegistrationResponse(
    val statusCode: Int,
    val body: String,
)

fun interface ClientRegistrationTransport {
    fun register(payload: ClientRegistrationPayload): ClientRegistrationResponse
}

fun interface ClientRegistrationScheduler {
    fun scheduleAtFixedRate(
        initialDelayMillis: Long,
        periodMillis: Long,
        task: () -> Unit,
    ): ClientRegistrationSchedule
}

fun interface ClientRegistrationSchedule {
    fun cancel()
}

object DefaultClientRegistrationScheduler : ClientRegistrationScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "neptune-client-registration").apply {
            isDaemon = true
        }
    }

    override fun scheduleAtFixedRate(
        initialDelayMillis: Long,
        periodMillis: Long,
        task: () -> Unit,
    ): ClientRegistrationSchedule {
        val future: ScheduledFuture<*> = executor.scheduleAtFixedRate(
            {
                runCatching { task() }
            },
            initialDelayMillis,
            periodMillis,
            TimeUnit.MILLISECONDS,
        )
        return ClientRegistrationSchedule {
            future.cancel(false)
        }
    }
}

class OkHttpClientRegistrationTransport(
    private val registerUrl: HttpUrl,
    private val client: OkHttpClient = OkHttpClient(),
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : ClientRegistrationTransport {
    override fun register(payload: ClientRegistrationPayload): ClientRegistrationResponse {
        val request = Request.Builder()
            .url(registerUrl)
            .post(payload.toRequestBody())
            .build()

        return client.newCall(request).execute().use { response ->
            response.toRegistrationResponse()
        }
    }

    private fun ClientRegistrationPayload.toRequestBody() =
        objectMapper.createObjectNode().apply {
            put("platform", platform)
            put("appId", appId)
            put("deviceId", deviceId)
            sessionId?.takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
            put("callbackEndpoint", callbackEndpoint)
            if (preferredTransports.isNotEmpty()) {
                putArray("preferredTransports").apply {
                    preferredTransports.forEach { add(it) }
                }
            }
            usbmuxdHint?.let { hint ->
                putObject("usbmuxdHint").apply {
                    put("deviceID", hint.deviceID)
                    hint.socketPath?.takeIf { value -> value.isNotBlank() }?.let { put("socketPath", it) }
                }
            }
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun Response.toRegistrationResponse(): ClientRegistrationResponse =
        ClientRegistrationResponse(
            statusCode = code,
            body = bodyAsString(),
        )

    private fun Response.bodyAsString(): String =
        body?.string().orEmpty()

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class ClientRegistrationSession(
    val identity: ClientIdentity,
    private val callbackEndpoint: String,
    private val transport: ClientRegistrationTransport,
    private val scheduler: ClientRegistrationScheduler = DefaultClientRegistrationScheduler,
    private val renewIntervalMillis: Long = DEFAULT_CLIENT_REGISTRATION_RENEW_INTERVAL_MILLIS,
    private val preferredTransports: List<String> = emptyList(),
    private val usbmuxdHint: ClientUSBMuxdHint? = null,
) : Closeable {
    private val started = AtomicBoolean(false)
    @Volatile private var schedule: ClientRegistrationSchedule? = null

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }

        performRegistration()
        schedule = scheduler.scheduleAtFixedRate(
            initialDelayMillis = renewIntervalMillis,
            periodMillis = renewIntervalMillis,
            task = { performRegistration() },
        )
    }

    override fun close() {
        schedule?.cancel()
        schedule = null
        started.set(false)
    }

    private fun performRegistration() {
        runCatching {
            transport.register(identity.toPayload(callbackEndpoint))
        }
    }

    private fun ClientIdentity.toPayload(callbackEndpoint: String): ClientRegistrationPayload =
        ClientRegistrationPayload(
            platform = platform,
            appId = appId,
            deviceId = deviceId,
            sessionId = sessionId,
            callbackEndpoint = callbackEndpoint,
            preferredTransports = preferredTransports,
            usbmuxdHint = usbmuxdHint,
        )
}
