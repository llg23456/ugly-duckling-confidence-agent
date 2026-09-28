package com.testconnection.confidence_agent.data.remote

import com.testconnection.confidence_agent.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class VideoScene(
    val stage: String,
    val text: String,
    val sourceEventIds: List<Long>,
)

data class VideoScript(
    val id: Long,
    val scenes: List<VideoScene>,
    val sourceEventIds: List<Long>,
    val model: String?,
    val promptVersion: String,
    val mock: Boolean,
    val userEdited: Boolean,
)

class VideoScriptApiClient(private val baseUrl: String = BuildConfig.API_BASE_URL) {
    private fun request(path: String, method: String, body: JSONObject): String {
        val connection = (URL("${baseUrl.trimEnd('/')}/api/v1$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 45_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("脚本服务返回 $status")
            return content
        } finally {
            connection.disconnect()
        }
    }

    suspend fun create(deviceId: String, eventIds: List<Long>): VideoScript = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_id", deviceId).put("event_ids", JSONArray(eventIds))
        parse(JSONObject(request("/videos/scripts", "POST", body)))
    }

    suspend fun update(deviceId: String, scriptId: Long, scenes: List<VideoScene>): VideoScript = withContext(Dispatchers.IO) {
        val rows = JSONArray()
        scenes.forEach { scene -> rows.put(JSONObject().put("stage", scene.stage).put("text", scene.text).put("source_event_ids", JSONArray(scene.sourceEventIds))) }
        parse(JSONObject(request("/videos/scripts/$scriptId", "PATCH", JSONObject().put("device_id", deviceId).put("scenes", rows))))
    }

    private fun parse(row: JSONObject): VideoScript {
        val scenes = row.getJSONArray("scenes")
        val sourceIds = row.getJSONArray("source_event_ids")
        return VideoScript(
            id = row.getLong("id"),
            scenes = (0 until scenes.length()).map { index ->
                val item = scenes.getJSONObject(index)
                val ids = item.getJSONArray("source_event_ids")
                VideoScene(item.getString("stage"), item.getString("text"), (0 until ids.length()).map { ids.getLong(it) })
            },
            sourceEventIds = (0 until sourceIds.length()).map { sourceIds.getLong(it) },
            model = row.optString("model").takeIf { it.isNotBlank() && it != "null" },
            promptVersion = row.getString("prompt_version"),
            mock = row.getBoolean("mock"),
            userEdited = row.getBoolean("is_user_edited"),
        )
    }
}
