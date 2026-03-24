package com.neptunekit.sdk.android.examples.simulator

import com.neptunekit.sdk.android.createExportService
import com.neptunekit.sdk.android.export.ExportMetrics
import com.neptunekit.sdk.android.export.ExportService
import com.neptunekit.sdk.android.export.ServiceHealth
import com.neptunekit.sdk.android.model.IngestLogRecord
import com.neptunekit.sdk.android.model.LogLevel
import com.neptunekit.sdk.android.model.Platform
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

data class SimulatorLogLine(
    val id: Long,
    val message: String,
    val level: LogLevel,
)

data class SimulatorDemoUiState(
    val clickCount: Int,
    val health: ServiceHealth,
    val metrics: ExportMetrics,
    val recentLogs: List<SimulatorLogLine>,
)

class SimulatorDemoController(
    private val service: ExportService = createExportService(queueCapacity = 16),
) : Closeable {
    private val clickCount = AtomicInteger(0)

    fun snapshot(): SimulatorDemoUiState = SimulatorDemoUiState(
        clickCount = clickCount.get(),
        health = service.health(),
        metrics = service.metrics(),
        recentLogs = service.logs(limit = 5).records.map {
            SimulatorLogLine(
                id = it.id,
                message = it.message,
                level = it.level,
            )
        },
    )

    fun emitLog(): SimulatorDemoUiState {
        val index = clickCount.incrementAndGet()
        val message = "neptune-simulator-click-$index"

        service.ingest(
            IngestLogRecord(
                timestamp = Instant.now().toString(),
                level = LogLevel.INFO,
                message = message,
                platform = Platform.ANDROID,
                appId = "com.neptunekit.sdk.android.examples.simulator",
                sessionId = "simulator-session",
                deviceId = "simulator-device",
                category = "demo",
                attributes = mapOf(
                    "clickIndex" to index.toString(),
                    "surface" to "emulator",
                ),
            ),
        )

        return snapshot()
    }

    override fun close() {
        service.close()
    }
}

