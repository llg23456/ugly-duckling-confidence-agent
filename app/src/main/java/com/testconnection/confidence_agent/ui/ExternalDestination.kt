package com.testconnection.confidence_agent.ui

import com.testconnection.confidence_agent.data.model.RecordMode

data class ExternalDestination(
    val tab: Int,
    val recordMode: RecordMode = RecordMode.TEXT,
    val openCamera: Boolean = false,
)
