package com.neptunekit.sdk.android.examples.simulator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatorDemoControllerTest {
    @Test
    fun emitLogUpdatesQueueMetricsAndRecentLogs() {
        SimulatorDemoController().use { controller ->
            val initial = controller.snapshot()
            assertEquals(0, initial.clickCount)
            assertEquals(0, initial.metrics.queuedRecords)
            assertTrue(initial.recentLogs.isEmpty())

            val afterFirstClick = controller.emitLog()

            assertEquals(1, afterFirstClick.clickCount)
            assertEquals(1, afterFirstClick.health.queueSize)
            assertEquals(1, afterFirstClick.metrics.queuedRecords)
            assertEquals(0L, afterFirstClick.metrics.droppedOverflow)
            assertEquals(1, afterFirstClick.recentLogs.size)
            assertEquals("neptune-simulator-click-1", afterFirstClick.recentLogs.single().message)
        }
    }
}

