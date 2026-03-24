package com.neptunekit.sdk.android.core

import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.toExportLogRecord
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class InMemoryLogStore(
    capacity: Int = DEFAULT_QUEUE_CAPACITY,
) : LogStore {
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

    override val capacity: Int
        get() = maxCapacity

    override val size: Int
        get() = lock.withLock { entries.size }

    override val droppedOverflow: Long
        get() = lock.withLock { droppedOverflowCount }

    override fun enqueue(record: IngestLogRecord): Long = lock.withLock {
        val id = nextId.getAndIncrement()
        entries.addLast(QueuedEntry(id, record))
        if (entries.size > maxCapacity) {
            entries.removeFirst()
            droppedOverflowCount += 1
        }
        id
    }

    override fun page(cursor: Long?, limit: Int): LogPage {
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
