package com.testconnection.confidence_agent.data.repository

import com.testconnection.confidence_agent.data.remote.ChatApiClient
import com.testconnection.confidence_agent.data.remote.ChatApiReply
import java.io.File

class ChatRepository(
    private val apiClient: ChatApiClient = ChatApiClient(),
) {
    suspend fun send(message: String): ChatApiReply =
        apiClient.sendMessage(deviceId = "android-demo", message = message)

    suspend fun sendAudio(file: File): ChatApiReply =
        apiClient.sendAudio(deviceId = "android-demo", file = file)

    suspend fun sendImage(
        prompt: String,
        fileName: String,
        mimeType: String,
        imageBytes: ByteArray,
    ): ChatApiReply = apiClient.sendImage(prompt, fileName, mimeType, imageBytes)

    suspend fun synthesizeSpeech(text: String, voice: String): ByteArray =
        apiClient.synthesizeSpeech(text, voice)
}
