package com.neptunekit.sdk.android

import com.neptunekit.sdk.android.core.DEFAULT_QUEUE_CAPACITY
import com.neptunekit.sdk.android.core.LogQueue
import com.neptunekit.sdk.android.export.ExportService

fun createExportService(queueCapacity: Int = DEFAULT_QUEUE_CAPACITY): ExportService =
    ExportService(LogQueue(queueCapacity))
