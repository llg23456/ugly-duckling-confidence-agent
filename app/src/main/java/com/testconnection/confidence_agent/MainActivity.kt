package com.testconnection.confidence_agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.testconnection.confidence_agent.ui.ConfidenceAgentApp
import com.testconnection.confidence_agent.ui.theme.ConfidenceAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ConfidenceAgentTheme {
                ConfidenceAgentApp()
            }
        }
    }
}
