package com.testconnection.confidence_agent.ui

import com.testconnection.confidence_agent.data.model.RecordMode

enum class AppDestination(val legacyCode: Int) {
    HOME(0),
    GROWTH(1),
    COMMUNITY(4),
    RECORD(2),
    PROFILE(3);

    companion object {
        fun fromLegacyCode(code: Int): AppDestination = entries.firstOrNull { it.legacyCode == code } ?: HOME
    }
}

data class ExternalDestination(
    val destination: AppDestination,
    val recordMode: RecordMode = RecordMode.TEXT,
    val openCamera: Boolean = false,
)
