package com.testconnection.confidence_agent.data.model

data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
)

data class GrowthMoment(
    val date: String,
    val title: String,
    val source: String,
)

data class OriginalRecord(
    val date: String,
    val weekday: String,
    val content: String,
)

data class SettingEntry(
    val symbol: String,
    val title: String,
    val subtitle: String,
)
