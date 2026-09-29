package com.testconnection.confidence_agent.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.testconnection.confidence_agent.data.local.ChatDatabase
import com.testconnection.confidence_agent.data.local.ChatMessageEntity
import com.testconnection.confidence_agent.data.model.ChatMessage
import com.testconnection.confidence_agent.data.model.UserProfile
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.ChatApiClient
import com.testconnection.confidence_agent.data.remote.ChatApiReply
import com.testconnection.confidence_agent.data.remote.CheckInApiClient
import com.testconnection.confidence_agent.data.remote.CheckInResponse
import com.testconnection.confidence_agent.data.remote.OnboardingReply
import com.testconnection.confidence_agent.data.remote.ProactiveCheckIn
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ChatRepository(
    context: Context? = null,
    private val apiClient: ChatApiClient = ChatApiClient(),
    private val checkInApiClient: CheckInApiClient = CheckInApiClient(),
) {
    private val appContext = context?.applicationContext
    private val database = appContext?.let(ChatDatabase::get)
    private val deviceId = appContext?.let { DeviceIdStore(it).get() } ?: "android-demo"
    private val cacheMutex = Mutex()

    fun observeHistory(): Flow<List<ChatMessage>> = requireNotNull(database).messages()
        .observeAll()
        .map { rows ->
            rows.map { row ->
                ChatMessage(
                    text = row.content,
                    fromUser = row.role == "user",
                    imagePath = row.localImagePath,
                    id = row.serverId,
                    modality = row.modality,
                )
            }
        }

    suspend fun syncHistory() {
        val db = requireNotNull(database)
        cacheMutex.withLock {
            val remote = apiClient.loadMessages(deviceId)
            db.withTransaction {
                val imagePaths = db.messages().all().associate { it.serverId to it.localImagePath }
                db.messages().clear()
                db.messages().upsert(remote.map { item ->
                    ChatMessageEntity(
                        serverId = item.id,
                        role = item.role,
                        modality = item.modality,
                        content = item.content,
                        mediaRef = item.mediaRef,
                        localImagePath = imagePaths[item.id],
                        createdAt = item.createdAt,
                        isMock = item.isMock,
                    )
                })
            }
        }
    }

    private suspend fun cacheExchange(
        userText: String,
        response: ChatApiReply,
        imageBytes: ByteArray? = null,
    ) {
        val db = requireNotNull(database)
        val userId = response.userMessageId ?: return syncHistory()
        val assistantId = response.assistantMessageId ?: return syncHistory()
        val imagePath = if (imageBytes != null) withContext(Dispatchers.IO) {
            File(requireNotNull(appContext).filesDir, "chat_images").apply { mkdirs() }
                .resolve("$userId.img")
                .also { it.writeBytes(imageBytes) }
                .absolutePath
        } else null
        val createdAt = Instant.now().toString()
        cacheMutex.withLock {
            db.messages().upsert(listOf(
                ChatMessageEntity(userId, "user", response.modality, userText, null, imagePath, createdAt, false),
                ChatMessageEntity(assistantId, "assistant", response.modality, response.text, null, null, createdAt, response.isMock),
            ))
        }
        appContext?.let { ReviewCacheStore(it).markDirty() }
    }

    suspend fun send(message: String): ChatApiReply {
        val response = apiClient.sendMessage(deviceId = deviceId, message = message)
        cacheExchange(message, response)
        return response
    }

    suspend fun sendAudio(file: File): ChatApiReply {
        val response = apiClient.sendAudio(deviceId = deviceId, file = file)
        cacheExchange(response.userText ?: "[语音消息]", response)
        return response
    }

    suspend fun sendImage(
        prompt: String,
        fileName: String,
        mimeType: String,
        imageBytes: ByteArray,
    ): ChatApiReply {
        val response = apiClient.sendImage(deviceId, prompt, fileName, mimeType, imageBytes)
        cacheExchange(prompt, response, imageBytes)
        return response
    }

    suspend fun synthesizeSpeech(text: String, voice: String): ByteArray =
        apiClient.synthesizeSpeech(text, voice)

    suspend fun transcribe(file: File): String = apiClient.transcribeAudio(file)

    suspend fun analyzeOnboarding(text: String, profile: UserProfile): OnboardingReply =
        apiClient.analyzeOnboarding(text, profile)

    suspend fun pendingCheckIn(): ProactiveCheckIn? = checkInApiClient.pending(deviceId)

    suspend fun newCheckInNotice(): ProactiveCheckIn? = checkInApiClient.notice(deviceId)

    suspend fun respondToCheckIn(checkInId: Long, choice: String): CheckInResponse =
        checkInApiClient.respond(deviceId, checkInId, choice)
}
