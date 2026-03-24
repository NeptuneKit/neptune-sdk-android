package com.neptunekit.sdk.android

import com.neptunekit.sdk.android.core.DEFAULT_QUEUE_CAPACITY
import com.neptunekit.sdk.android.core.LogQueue
import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.http.ExportHttpServer
import java.nio.file.Path

fun createExportService(queueCapacity: Int = DEFAULT_QUEUE_CAPACITY): ExportService =
    ExportService(LogQueue(queueCapacity))

fun createExportHttpServer(queueCapacity: Int = DEFAULT_QUEUE_CAPACITY): ExportHttpServer =
    ExportHttpServer(createExportService(queueCapacity))

fun createPersistentExportService(
    databasePath: Path,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
): ExportService = ExportService(LogQueue.persistent(databasePath = databasePath, capacity = queueCapacity))

fun createPersistentExportHttpServer(
    databasePath: Path,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
): ExportHttpServer = ExportHttpServer(
    createPersistentExportService(
        databasePath = databasePath,
        queueCapacity = queueCapacity,
    ),
)
