package com.neptunekit.sdk.android.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.neptunekit.sdk.android.core.LogPage
import com.neptunekit.sdk.android.core.LogStore
import com.neptunekit.sdk.android.model.ExportLogRecord
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform
import com.neptunekit.sdk.android.storage.db.NeptuneQueueDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class SqlDelightLogStore private constructor(
    private val driver: JdbcSqliteDriver,
    private val database: NeptuneQueueDatabase,
) : LogStore {
    companion object {
        fun open(
            databasePath: Path,
            capacity: Int = com.neptunekit.sdk.android.core.DEFAULT_QUEUE_CAPACITY,
        ): SqlDelightLogStore {
            require(capacity > 0) { "capacity must be greater than zero" }
            databasePath.toAbsolutePath().parent?.let(Files::createDirectories)

            val driver = JdbcSqliteDriver(
                url = "jdbc:sqlite:${databasePath.toAbsolutePath()}",
                properties = Properties(),
                schema = NeptuneQueueDatabase.Schema,
            )
            val database = NeptuneQueueDatabase(driver)

            return SqlDelightLogStore(driver = driver, database = database).apply {
                initialize(capacity)
            }
        }
    }

    private val queries = database.logQueueQueries
    private val lock = ReentrantLock()

    override val capacity: Int
        get() = lock.withLock { queries.selectCapacity().executeAsOne().toInt() }

    override val size: Int
        get() = lock.withLock { queries.countRecords().executeAsOne().toInt() }

    override val droppedOverflow: Long
        get() = lock.withLock { queries.selectDroppedOverflow().executeAsOne() }

    override fun enqueue(record: IngestLogRecord): Long = lock.withLock {
        var insertedId = 0L

        database.transaction {
            queries.enqueueRecord(
                timestamp = record.timestamp,
                level = record.level.name,
                message = record.message,
                platform = record.platform.name,
                app_id = record.appId,
                session_id = record.sessionId,
                device_id = record.deviceId,
                category = record.category,
                attributes_json = LogRecordJsonCodec.encodeAttributes(record.attributes),
                source_json = LogRecordJsonCodec.encodeSource(record.source),
            )
            insertedId = queries.selectLastInsertedId().executeAsOne()
            trimOverflowIfNeeded()
        }

        insertedId
    }

    override fun page(cursor: Long?, limit: Int): LogPage {
        require(limit > 0) { "limit must be greater than zero" }

        return lock.withLock {
            val selected = queries
                .selectPage(
                    id = cursor ?: 0L,
                    value_ = limit.toLong(),
                )
                .executeAsList()
                .map { row ->
                    ExportLogRecord(
                        id = row.id,
                        timestamp = row.timestamp,
                        level = LogLevel.valueOf(row.level),
                        message = row.message,
                        platform = Platform.valueOf(row.platform),
                        appId = row.app_id,
                        sessionId = row.session_id,
                        deviceId = row.device_id,
                        category = row.category,
                        attributes = LogRecordJsonCodec.decodeAttributes(row.attributes_json),
                        source = LogRecordJsonCodec.decodeSource(row.source_json),
                    )
                }

            val lastSelectedId = selected.lastOrNull()?.id
            val hasMore = lastSelectedId != null && queries.countAfterId(lastSelectedId).executeAsOne() > 0L
            LogPage(records = selected, nextCursor = lastSelectedId, hasMore = hasMore)
        }
    }

    override fun close() {
        driver.close()
    }

    private fun initialize(configuredCapacity: Int) = lock.withLock {
        database.transaction {
            queries.insertInitialState(capacity = configuredCapacity.toLong())
            queries.updateCapacity(capacity = configuredCapacity.toLong())
            trimOverflowIfNeeded()
        }
    }

    private fun trimOverflowIfNeeded() {
        val overflowCount = (queries.countRecords().executeAsOne() - queries.selectCapacity().executeAsOne())
            .coerceAtLeast(0L)

        if (overflowCount == 0L) {
            return
        }

        queries.deleteOverflowRecords(overflowCount)
        queries.updateDroppedOverflow(
            dropped_overflow = queries.selectDroppedOverflow().executeAsOne() + overflowCount,
        )
    }
}
