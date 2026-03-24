package com.neptunekit.sdk.android

import com.neptunekit.sdk.android.core.LogQueue
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistentLogQueueTest {
    @Test
    fun persistentQueueStoresRecordsAndOverflowAcrossReopen() {
        val databasePath = createTempDatabasePath()

        LogQueue.persistent(databasePath = databasePath, capacity = 3).use { queue ->
            repeat(4) { index ->
                queue.enqueue(sampleRecord(index))
            }

            val firstPage = queue.page(limit = 2)
            val secondPage = queue.page(cursor = firstPage.nextCursor, limit = 2)

            assertEquals(3, queue.size)
            assertEquals(1L, queue.droppedOverflow)
            assertEquals(listOf("message-1", "message-2"), firstPage.records.map { it.message })
            assertTrue(firstPage.hasMore)
            assertEquals(listOf("message-3"), secondPage.records.map { it.message })
            assertFalse(secondPage.hasMore)
            assertEquals(4L, secondPage.nextCursor)
        }

        LogQueue.persistent(databasePath = databasePath, capacity = 3).use { reopened ->
            val page = reopened.page(limit = 10)

            assertEquals(3, reopened.size)
            assertEquals(1L, reopened.droppedOverflow)
            assertEquals(listOf("message-1", "message-2", "message-3"), page.records.map { it.message })
            assertFalse(page.hasMore)
            assertEquals(4L, page.nextCursor)
        }
    }

    @Test
    fun persistentFactoryKeepsExportServiceContract() {
        val databasePath = createTempDatabasePath()

        createPersistentExportService(databasePath = databasePath, queueCapacity = 2).use { service ->
            service.ingest(sampleRecord(0))
            service.ingest(sampleRecord(1))
            service.ingest(sampleRecord(2))

            val metrics = service.metrics()
            val health = service.health()
            val page = service.logs(limit = 10)

            assertEquals(2, metrics.queuedRecords)
            assertEquals(1L, metrics.droppedOverflow)
            assertEquals(2, health.queueCapacity)
            assertEquals(2, health.queueSize)
            assertEquals(listOf("message-1", "message-2"), page.records.map { it.message })
        }
    }

    private fun createTempDatabasePath(): Path {
        val directory = Files.createTempDirectory("neptune-sqlite-queue")
        return directory.resolve("queue.db")
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
