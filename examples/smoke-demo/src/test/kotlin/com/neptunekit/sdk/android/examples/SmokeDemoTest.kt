package com.neptunekit.sdk.android.examples

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeDemoTest {
    @Test
    fun smokeDemoIngestsLogsAndProducesSummary() {
        val summary = runSmokeDemo()
        val rendered = renderSmokeDemoSummary(summary)

        assertEquals(8, summary.health.queueCapacity)
        assertEquals(3, summary.health.queueSize)
        assertEquals(3, summary.metrics.queuedRecords)
        assertEquals(0L, summary.metrics.droppedOverflow)
        assertEquals(3, summary.records.size)
        assertEquals(listOf("smoke-start", "smoke-cache-warmup-slow", "smoke-request-failed"), summary.records.map { it.message })
        assertEquals(3L, summary.nextCursor)
        assertTrue(rendered.contains("NeptuneKit Android smoke demo"))
        assertTrue(rendered.contains("queuedRecords=3"))
        assertTrue(rendered.contains("- #1 INFO smoke-start"))
    }
}
