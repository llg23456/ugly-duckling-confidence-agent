package com.testconnection.confidence_agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.testconnection.confidence_agent.ui.ConfidenceAgentApp
import com.testconnection.confidence_agent.ui.ExternalDestination
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.ui.theme.ConfidenceAgentTheme
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TARGET_TAB = "target_tab"
        const val EXTRA_RECORD_MODE = "record_mode"
        const val EXTRA_OPEN_CAMERA = "open_camera"
    }

    private val externalDestination = mutableStateOf<ExternalDestination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalDestination.value = destinationFrom(intent)
        enableEdgeToEdge()
        setContent {
            ConfidenceAgentTheme {
                ConfidenceAgentApp(
                    externalDestination = externalDestination.value,
                    onExternalDestinationConsumed = { externalDestination.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalDestination.value = destinationFrom(intent)
    }

    private fun destinationFrom(intent: Intent?): ExternalDestination? {
        if (intent?.hasExtra(EXTRA_TARGET_TAB) != true) return null
        val mode = runCatching {
            RecordMode.valueOf(intent.getStringExtra(EXTRA_RECORD_MODE) ?: RecordMode.TEXT.name)
        }.getOrDefault(RecordMode.TEXT)
        return ExternalDestination(
            tab = intent.getIntExtra(EXTRA_TARGET_TAB, 0),
            recordMode = mode,
            openCamera = intent.getBooleanExtra(EXTRA_OPEN_CAMERA, false),
        )
    }
}
