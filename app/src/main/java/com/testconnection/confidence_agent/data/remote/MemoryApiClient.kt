package com.testconnection.confidence_agent.data.remote

import android.net.Uri
import com.testconnection.confidence_agent.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SavedMemory(
    val id: Long,
    val content: String,
    val status: String,
    val sourceId: Long?,
    val sourceDate: String?,
    val sourceType: String?,
)

data class DailySummaryDraft(val day: String, val content: String)

class MemoryApiClient(private val baseUrl: String = BuildConfig.API_BASE_URL) {
    private fun request(path: String, method: String, body: String? = null): String {
        val connection = (URL("${baseUrl.trimEnd('/')}/api/v1$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("记忆服务返回 $status")
            return content
        } finally {
            connection.disconnect()
        }
    }

    suspend fun list(deviceId: String): List<SavedMemory> = withContext(Dispatchers.IO) {
        val array = JSONObject(request("/memories?device_id=${Uri.encode(deviceId)}", "GET")).getJSONArray("memories")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(SavedMemory(
                    id = item.getLong("id"), content = item.getString("content"), status = item.getString("status"),
                    sourceId = item.optLong("source_id").takeIf { it > 0 },
                    sourceDate = item.optString("source_date").takeIf { it.isNotBlank() && it != "null" },
                    sourceType = item.optString("source_type").takeIf { it.isNotBlank() && it != "null" },
                ))
            }
        }
    }

    suspend fun dailyDrafts(deviceId: String): List<DailySummaryDraft> = withContext(Dispatchers.IO) {
        val array = JSONObject(request("/events/daily-summaries?device_id=${Uri.encode(deviceId)}", "GET")).getJSONArray("summaries")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(DailySummaryDraft(item.getString("day"), item.getString("content")))
            }
        }
    }

    suspend fun edit(deviceId: String, id: Long, content: String) = withContext(Dispatchers.IO) {
        request("/memories/$id", "PATCH", JSONObject().put("device_id", deviceId).put("content", content).toString())
    }

    suspend fun confirm(deviceId: String, id: Long) = withContext(Dispatchers.IO) {
        request("/memories/$id/confirm?device_id=${Uri.encode(deviceId)}", "POST")
    }

    suspend fun delete(deviceId: String, id: Long) = withContext(Dispatchers.IO) {
        request("/memories/$id?device_id=${Uri.encode(deviceId)}", "DELETE")
    }
}
