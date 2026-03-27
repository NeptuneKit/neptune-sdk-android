package com.neptunekit.sdk.android.viewtree

import com.neptunekit.sdk.android.model.InspectorSnapshot
import com.neptunekit.sdk.android.model.ViewTreeSnapshot

/**
 * Platform-specific hook for collecting runtime UI trees.
 *
 * The sdk module stays pure Kotlin/JVM, while Android app code can inject
 * a concrete collector that depends on android.view APIs.
 */
interface ViewTreeCollector {
    fun captureSnapshot(query: ViewTreeQuery): ViewTreeSnapshot?

    fun captureInspector(query: ViewTreeQuery): InspectorSnapshot?
}

data class ViewTreeQuery(
    val platform: String?,
    val appId: String?,
    val sessionId: String?,
    val deviceId: String?,
)
