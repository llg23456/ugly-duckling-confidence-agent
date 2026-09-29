package com.testconnection.confidence_agent.data.remote

import android.net.Uri
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ProactiveCheckIn(
    val id: Long,
    val reason: String,
    val prompt: String,
    val sourceEventIds: List<Long>,
    val createdAt: String,
)

data class CheckInResponse(
    val id: Long,
    val choice: String,
    val acknowledgement: String,
    val followUpMessage: String?,
)

class CheckInApiClient(private val baseUrl: String? = null) {
    private val resolvedBaseUrl: String get() = baseUrl ?: ServerEndpoint.current()

    suspend fun pending(deviceId: String): ProactiveCheckIn? = withContext(Dispatchers.IO) {
        getCheckIn("/api/v1/check-ins/pending?device_id=${Uri.encode(deviceId)}")
    }

    suspend fun notice(deviceId: String): ProactiveCheckIn? = withContext(Dispatchers.IO) {
        getCheckIn("/api/v1/check-ins/notice?device_id=${Uri.encode(deviceId)}")
    }

    private fun getCheckIn(path: String): ProactiveCheckIn? {
        val connection = open(path, "GET")
        try {
            val json = readJson(connection, "主动问候服务")
            val item = json.optJSONObject("check_in") ?: return null
            val sourceIds = item.optJSONArray("source_event_ids")
            return ProactiveCheckIn(
                id = item.getLong("id"),
                reason = item.getString("reason"),
                prompt = item.getString("prompt"),
                sourceEventIds = buildList {
                    if (sourceIds != null) for (index in 0 until sourceIds.length()) add(sourceIds.getLong(index))
                },
                createdAt = item.getString("created_at"),
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun respond(deviceId: String, checkInId: Long, choice: String): CheckInResponse =
        withContext(Dispatchers.IO) {
            val connection = open("/api/v1/check-ins/$checkInId/respond", "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                val request = JSONObject().put("device_id", deviceId).put("choice", choice).toString()
                connection.outputStream.use { it.write(request.toByteArray(Charsets.UTF_8)) }
                val json = readJson(connection, "主动问候服务")
                CheckInResponse(
                    id = json.getLong("id"),
                    choice = json.getString("choice"),
                    acknowledgement = json.getString("acknowledgement"),
                    followUpMessage = json.optString("follow_up_message")
                        .takeIf { it.isNotBlank() && it != "null" },
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL("${resolvedBaseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 40_000
            setRequestProperty("Accept", "application/json")
        }

    private fun readJson(connection: HttpURLConnection, serviceName: String): JSONObject {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
            throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "$serviceName 返回 $status")
        }
        return JSONObject(body)
    }
}
