package com.neptunekit.sdk.android.export

import com.neptunekit.sdk.android.core.DEFAULT_PAGE_LIMIT
import com.neptunekit.sdk.android.core.LogPage
import com.neptunekit.sdk.android.core.LogQueue
import com.neptunekit.sdk.android.model.IngestLogRecord

class ExportService(
    private val queue: LogQueue = LogQueue(),
) {
    fun ingest(record: IngestLogRecord): Long = queue.enqueue(record)

    fun logs(cursor: Long? = null, limit: Int = DEFAULT_PAGE_LIMIT): LogPage =
        queue.page(cursor = cursor, limit = limit)

    fun metrics(): ExportMetrics =
        ExportMetrics(
            queuedRecords = queue.size,
            droppedOverflow = queue.droppedOverflow,
        )

    fun health(): ServiceHealth =
        ServiceHealth(
            ok = true,
            queueCapacity = queue.capacity,
            queueSize = queue.size,
        )
}

data class ExportMetrics(
    val queuedRecords: Int,
    val droppedOverflow: Long,
)

data class ServiceHealth(
    val ok: Boolean,
    val queueCapacity: Int,
    val queueSize: Int,
)
