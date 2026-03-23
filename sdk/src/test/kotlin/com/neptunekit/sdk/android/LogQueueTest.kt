package com.neptunekit.sdk.android

import com.neptunekit.sdk.android.core.LogQueue
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogQueueTest {
    @Test
    fun overflowDropsOldestRecordAndCountsIt() {
        val queue = LogQueue(capacity = 3)

        repeat(4) { index ->
            queue.enqueue(sampleRecord(index))
        }

        val page = queue.page(limit = 10)

        assertEquals(3, queue.size)
        assertEquals(1, queue.droppedOverflow)
        assertEquals(listOf("message-1", "message-2", "message-3"), page.records.map { it.message })
        assertFalse(page.hasMore)
        assertEquals(4L, page.records.last().id)
    }

    @Test
    fun cursorPaginationReturnsStablePages() {
        val queue = LogQueue(capacity = 10)

        repeat(5) { index ->
            queue.enqueue(sampleRecord(index))
        }

        val firstPage = queue.page(limit = 2)
        val secondPage = queue.page(cursor = firstPage.nextCursor, limit = 2)

        assertEquals(listOf("message-0", "message-1"), firstPage.records.map { it.message })
        assertTrue(firstPage.hasMore)
        assertEquals(2L, firstPage.nextCursor)

        assertEquals(listOf("message-2", "message-3"), secondPage.records.map { it.message })
        assertTrue(secondPage.hasMore)
        assertEquals(4L, secondPage.nextCursor)
    }

    private fun sampleRecord(index: Int): IngestLogRecord =
        IngestLogRecord(
            timestamp = "2026-03-23T00:00:00Z",
            level = LogLevel.INFO,
            message = "message-$index",
            platform = Platform.ANDROID,
            appId = "com.example.app",
            sessionId = "session-1",
            deviceId = "device-1",
            category = "default",
        )
}
