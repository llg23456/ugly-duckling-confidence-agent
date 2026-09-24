package com.testconnection.confidence_agent.data.model

import androidx.annotation.DrawableRes

data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val imageBytes: ByteArray? = null,
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
    @DrawableRes val iconRes: Int,
    val title: String,
    val subtitle: String,
)
