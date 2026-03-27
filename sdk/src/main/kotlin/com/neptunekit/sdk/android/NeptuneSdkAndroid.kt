package com.neptunekit.sdk.android

import com.neptunekit.sdk.android.core.DEFAULT_QUEUE_CAPACITY
import com.neptunekit.sdk.android.core.LogQueue
import com.neptunekit.sdk.android.discovery.GatewayDiscovery
import com.neptunekit.sdk.android.discovery.GatewayDiscoveryEndpoint
import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.http.DEFAULT_BIND_HOST
import com.neptunekit.sdk.android.http.ExportHttpServer
import com.neptunekit.sdk.android.viewtree.ViewTreeCollector
import com.neptunekit.sdk.android.registration.ClientIdentity
import com.neptunekit.sdk.android.registration.ClientRegistrationSession
import com.neptunekit.sdk.android.registration.ClientUSBMuxdHint
import com.neptunekit.sdk.android.registration.OkHttpClientRegistrationTransport
import com.neptunekit.sdk.android.registration.DEFAULT_CLIENT_REGISTRATION_RENEW_INTERVAL_MILLIS
import com.neptunekit.sdk.android.ws.GatewayWebSocketClient
import okhttp3.HttpUrl
import java.nio.file.Path

fun createExportService(queueCapacity: Int = DEFAULT_QUEUE_CAPACITY): ExportService =
    ExportService(LogQueue(queueCapacity))

fun createExportHttpServer(
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    host: String = DEFAULT_BIND_HOST,
    viewTreeCollector: ViewTreeCollector? = null,
): ExportHttpServer =
    ExportHttpServer(
        createExportService(queueCapacity),
        host = host,
        viewTreeCollector = viewTreeCollector,
    )

fun createPersistentExportService(
    databasePath: Path,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
): ExportService = ExportService(LogQueue.persistent(databasePath = databasePath, capacity = queueCapacity))

fun createPersistentExportHttpServer(
    databasePath: Path,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    host: String = DEFAULT_BIND_HOST,
    viewTreeCollector: ViewTreeCollector? = null,
): ExportHttpServer = ExportHttpServer(
    createPersistentExportService(
        databasePath = databasePath,
        queueCapacity = queueCapacity,
    ),
    host = host,
    viewTreeCollector = viewTreeCollector,
)

fun createGatewayDiscovery(): GatewayDiscovery = GatewayDiscovery()

fun createGatewayWebSocketClient(): GatewayWebSocketClient = GatewayWebSocketClient()

fun createClientRegistrationSession(
    gatewayEndpoint: GatewayDiscoveryEndpoint,
    callbackEndpoint: String,
    platform: String,
    appId: String,
    deviceId: String,
    sessionId: String? = null,
    renewIntervalMillis: Long = DEFAULT_CLIENT_REGISTRATION_RENEW_INTERVAL_MILLIS,
    preferredTransports: List<String> = emptyList(),
    usbmuxdHint: ClientUSBMuxdHint? = null,
): ClientRegistrationSession =
    ClientRegistrationSession(
        identity = ClientIdentity(
            platform = platform,
            appId = appId,
            deviceId = deviceId,
            sessionId = sessionId,
        ),
        callbackEndpoint = callbackEndpoint,
        transport = OkHttpClientRegistrationTransport(registerUrl = gatewayEndpoint.registrationUrl()),
        renewIntervalMillis = renewIntervalMillis,
        preferredTransports = preferredTransports,
        usbmuxdHint = usbmuxdHint,
    )

private fun GatewayDiscoveryEndpoint.registrationUrl(): HttpUrl =
    HttpUrl.Builder()
        .scheme("http")
        .host(host)
        .port(port)
        .addPathSegments("v2/clients:register")
        .build()
