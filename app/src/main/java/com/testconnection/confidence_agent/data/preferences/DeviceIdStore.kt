package com.testconnection.confidence_agent.data.preferences

import android.content.Context
import java.util.UUID

class DeviceIdStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("duck_device", Context.MODE_PRIVATE)

    fun get(): String = synchronized(preferences) {
        preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).commit()
        }
    }
}
