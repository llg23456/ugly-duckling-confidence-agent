package com.testconnection.confidence_agent.data.remote

import com.testconnection.confidence_agent.BuildConfig
import com.testconnection.confidence_agent.data.model.UserProfile
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ChatApiReply(
    val text: String,
    val isMock: Boolean,
    val userText: String? = null,
    val modality: String = "text",
)

data class OnboardingReply(
    val profile: UserProfile,
    val missingFields: List<String>,
    val followUp: String?,
    val complete: Boolean,
)

class ChatApiClient(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) {
    suspend fun sendMessage(deviceId: String, message: String): ChatApiReply = withContext(Dispatchers.IO) {
        val connection = (URL("${baseUrl.trimEnd('/')}/api/v1/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 40_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            val requestBody = JSONObject()
                .put("device_id", deviceId)
                .put("message", message)
                .put("mode", "listen")
                .toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }

            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("服务返回 $status：$responseText")
            }

            val json = JSONObject(responseText)
            ChatApiReply(
                text = json.getString("reply"),
                isMock = json.optBoolean("mock", true),
                userText = message,
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun sendAudio(deviceId: String, file: File): ChatApiReply =
        sendMultipart(
            path = "/api/v1/multimodal/audio",
            fields = mapOf("device_id" to deviceId),
            fileName = file.name,
            mimeType = "audio/wav",
            fileBytes = withContext(Dispatchers.IO) { file.readBytes() },
        )

    suspend fun sendImage(
        prompt: String,
        fileName: String,
        mimeType: String,
        imageBytes: ByteArray,
    ): ChatApiReply = sendMultipart(
        path = "/api/v1/multimodal/image",
        fields = mapOf("prompt" to prompt),
        fileName = fileName,
        mimeType = mimeType,
        fileBytes = imageBytes,
    )

    suspend fun synthesizeSpeech(text: String, voice: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = (URL("${baseUrl.trimEnd('/')}/api/v1/multimodal/speech").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 70_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/*")
        }
        try {
            val requestBody = JSONObject().put("text", text).put("voice", voice).toString()
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val responseText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(responseText).optString("detail") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "语音合成服务返回 $status")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun transcribeAudio(file: File): String = withContext(Dispatchers.IO) {
        val response = sendMultipartRaw(
            path = "/api/v1/multimodal/transcribe",
            fields = emptyMap(),
            fileName = file.name,
            mimeType = "audio/wav",
            fileBytes = file.readBytes(),
        )
        JSONObject(response).getString("transcript")
    }

    suspend fun analyzeOnboarding(transcript: String, profile: UserProfile): OnboardingReply = withContext(Dispatchers.IO) {
        val connection = (URL("${baseUrl.trimEnd('/')}/api/v1/onboarding/analyze").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = JSONObject()
                .put("transcript", transcript)
                .put("existing_profile", profile.toJson())
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("画像服务返回 $status")
            val json = JSONObject(responseText)
            val missingJson = json.optJSONArray("missing_fields")
            val missing = buildList {
                if (missingJson != null) for (index in 0 until missingJson.length()) add(missingJson.getString(index))
            }
            OnboardingReply(
                profile = UserProfile.fromJson(json.getJSONObject("profile")),
                missingFields = missing,
                followUp = json.optString("follow_up").takeIf { it.isNotBlank() && it != "null" },
                complete = json.optBoolean("complete"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun sendMultipart(
        path: String,
        fields: Map<String, String>,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
    ): ChatApiReply {
        val responseText = sendMultipartRaw(path, fields, fileName, mimeType, fileBytes)
        val json = JSONObject(responseText)
        return ChatApiReply(
            text = json.getString("reply"),
            isMock = json.optBoolean("mock", false),
            userText = json.optString("user_text").takeIf { it.isNotBlank() },
            modality = json.optString("modality", "text"),
        )
    }

    private suspend fun sendMultipartRaw(
        path: String,
        fields: Map<String, String>,
        fileName: String,
        mimeType: String,
        fileBytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val boundary = "DuckBoundary${UUID.randomUUID()}"
        val connection = (URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 90_000
            doOutput = true
            setChunkedStreamingMode(0)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        try {
            DataOutputStream(connection.outputStream).use { output ->
                fields.forEach { (name, value) ->
                    output.writeUtf8("--$boundary\r\n")
                    output.writeUtf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    output.writeUtf8(value)
                    output.writeUtf8("\r\n")
                }
                output.writeUtf8("--$boundary\r\n")
                output.writeUtf8("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
                output.writeUtf8("Content-Type: $mimeType\r\n\r\n")
                output.write(fileBytes)
                output.writeUtf8("\r\n--$boundary--\r\n")
                output.flush()
            }

            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(responseText).optString("detail") }.getOrNull()
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "服务返回 $status")
            }
            responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
    }
}
