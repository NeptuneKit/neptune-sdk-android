package com.neptunekit.sdk.android.core

import com.neptunekit.sdk.android.model.ExportLogRecord
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.toExportLogRecord
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

const val DEFAULT_QUEUE_CAPACITY = 2_000
const val DEFAULT_PAGE_LIMIT = 50

/**
 * In-memory queue with a stable monotonic cursor.
 */
class LogQueue(
    capacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    private val maxCapacity = capacity

    private data class QueuedEntry(
        val id: Long,
        val record: IngestLogRecord,
    )

    private val lock = ReentrantLock()
    private val nextId = AtomicLong(1L)
    private val entries = ArrayDeque<QueuedEntry>()
    private var droppedOverflowCount: Long = 0

    val capacity: Int
        get() = maxCapacity

    val size: Int
        get() = lock.withLock { entries.size }

    val droppedOverflow: Long
        get() = lock.withLock { droppedOverflowCount }

    fun enqueue(record: IngestLogRecord): Long = lock.withLock {
        val id = nextId.getAndIncrement()
        entries.addLast(QueuedEntry(id, record))
        if (entries.size > maxCapacity) {
            entries.removeFirst()
            droppedOverflowCount += 1
        }
        id
    }

    fun page(cursor: Long? = null, limit: Int = DEFAULT_PAGE_LIMIT): LogPage {
        require(limit > 0) { "limit must be greater than zero" }

        return lock.withLock {
            val startIndex = if (cursor == null) {
                0
            } else {
                entries.indexOfFirst { it.id > cursor }
            }

            if (startIndex < 0 || startIndex >= entries.size) {
                return@withLock LogPage(emptyList(), nextCursor = null, hasMore = false)
            }

            val selected = entries
                .drop(startIndex)
                .take(limit)
                .map { it.id.toExportLogRecord(it.record) }

            val lastSelected = selected.lastOrNull()?.id
            val hasMore = startIndex + selected.size < entries.size
            LogPage(selected, nextCursor = if (selected.isEmpty()) null else lastSelected, hasMore = hasMore)
        }
    }
}

data class LogPage(
    val records: List<ExportLogRecord>,
    val nextCursor: Long?,
    val hasMore: Boolean,
)
