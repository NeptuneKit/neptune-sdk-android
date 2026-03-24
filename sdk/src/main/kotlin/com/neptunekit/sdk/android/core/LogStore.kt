package com.neptunekit.sdk.android.core

import com.neptunekit.sdk.android.model.IngestLogRecord
import java.io.Closeable

internal interface LogStore : Closeable {
    val capacity: Int
    val size: Int
    val droppedOverflow: Long

    fun enqueue(record: IngestLogRecord): Long

    fun page(cursor: Long? = null, limit: Int = DEFAULT_PAGE_LIMIT): LogPage

    override fun close() = Unit
}
