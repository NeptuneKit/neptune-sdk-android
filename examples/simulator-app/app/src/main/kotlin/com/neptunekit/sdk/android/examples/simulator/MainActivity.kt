package com.neptunekit.sdk.android.examples.simulator

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.neptunekit.sdk.android.examples.simulator.databinding.ActivityMainBinding

private const val TAG = "NeptuneSimulatorDemo"

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val controller by lazy { SimulatorDemoController() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        render(controller.snapshot())

        binding.emitLogButton.setOnClickListener {
            val state = controller.emitLog()
            Log.i(
                TAG,
                "emitLog clickCount=${state.clickCount} queuedRecords=${state.metrics.queuedRecords} " +
                    "droppedOverflow=${state.metrics.droppedOverflow} queueSize=${state.health.queueSize}",
            )
            render(state)
        }
    }

    private fun render(state: SimulatorDemoUiState) {
        binding.queueStatusText.text = buildString {
            appendLine("Clicks: ${state.clickCount}")
            appendLine("Health: ok=${state.health.ok} capacity=${state.health.queueCapacity} size=${state.health.queueSize}")
            appendLine("Metrics: queuedRecords=${state.metrics.queuedRecords} droppedOverflow=${state.metrics.droppedOverflow}")
            appendLine("Recent logs:")
            if (state.recentLogs.isEmpty()) {
                appendLine("- none yet")
            } else {
                state.recentLogs.forEach { logLine ->
                    appendLine("- #${logLine.id} ${logLine.level} ${logLine.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized) {
            controller.close()
        }
    }
}

