package com.testconnection.confidence_agent

import android.app.Application
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint

class ConfidenceAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServerEndpoint.initialize(this)
    }
}
