package com.testconnection.confidence_agent.data.model

enum class RecordMode { TEXT, VOICE, PHOTO }

data class RecordDraft(
    val id: String,
    val mode: RecordMode,
    val text: String,
    val audioPath: String? = null,
    val photoPath: String? = null,
    val photoComment: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "saved",
)
