package com.testconnection.confidence_agent

import android.app.Application
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint

class ConfidenceAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServerEndpoint.initialize(this)
        com.testconnection.confidence_agent.data.remote.CloudAccess.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.testconnection.confidence_agent.data.repository.CommunityImageCache.trimMemory()
        }
    }
}
