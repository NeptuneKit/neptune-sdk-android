package com.neptunekit.sdk.android.core

import com.neptunekit.sdk.android.storage.SqlDelightLogStore
import com.neptunekit.sdk.android.model.ExportLogRecord
import com.neptunekit.sdk.android.model.IngestLogRecord
import java.io.Closeable
import java.nio.file.Path

const val DEFAULT_QUEUE_CAPACITY = 2_000
const val DEFAULT_PAGE_LIMIT = 50

/**
 * Queue facade with a stable monotonic cursor.
 */
class LogQueue private constructor(
    private val store: LogStore,
) : Closeable {
    constructor(capacity: Int = DEFAULT_QUEUE_CAPACITY) : this(InMemoryLogStore(capacity))

    companion object {
        fun persistent(
            databasePath: Path,
            capacity: Int = DEFAULT_QUEUE_CAPACITY,
        ): LogQueue = LogQueue(SqlDelightLogStore.open(databasePath = databasePath, capacity = capacity))
    }

    val capacity: Int
        get() = store.capacity

    val size: Int
        get() = store.size

    val droppedOverflow: Long
        get() = store.droppedOverflow

    fun enqueue(record: IngestLogRecord): Long = store.enqueue(record)

    fun page(cursor: Long? = null, limit: Int = DEFAULT_PAGE_LIMIT): LogPage =
        store.page(cursor = cursor, limit = limit)

    override fun close() {
        store.close()
    }
}

data class LogPage(
    val records: List<ExportLogRecord>,
    val nextCursor: Long?,
    val hasMore: Boolean,
)
