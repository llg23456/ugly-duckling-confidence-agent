package com.testconnection.confidence_agent.data.remote

import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class VideoScene(
    val stage: String,
    val title: String = "成长记录",
    val date: String? = null,
    val text: String,
    val sourceEventIds: List<Long>,
)

data class VideoKeywordSuggestion(
    val label: String,
    val eventIds: List<Long>,
)

data class VideoKeywordSuggestions(
    val suggestions: List<VideoKeywordSuggestion>,
    val model: String?,
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

data class VideoRenderAsset(
    val frame: File,
    val durationMs: Long,
    val scriptSceneIndex: Int,
    val narration: File? = null,
    val narrationDurationMs: Long = 0,
    val originalVoice: File? = null,
)

class VideoScriptApiClient(private val baseUrl: String? = null) {
    private val resolvedBaseUrl: String get() = baseUrl ?: ServerEndpoint.current()

    private fun request(path: String, method: String, body: JSONObject): String {
        val connection = (URL("${resolvedBaseUrl.trimEnd('/')}/api/v1$path").openConnection() as HttpURLConnection).apply {
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
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(content).optString("detail") }.getOrDefault("")
                throw IllegalStateException(detail.ifBlank { "脚本服务返回 $status" })
            }
            return content
        } finally {
            connection.disconnect()
        }
    }

    suspend fun create(deviceId: String, eventIds: List<Long>): VideoScript = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_id", deviceId).put("event_ids", JSONArray(eventIds))
        parse(JSONObject(request("/videos/scripts", "POST", body)))
    }

    suspend fun suggestKeywords(deviceId: String, eventIds: List<Long>): VideoKeywordSuggestions = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_id", deviceId).put("event_ids", JSONArray(eventIds))
        val row = JSONObject(request("/videos/keywords", "POST", body))
        val suggestions = row.getJSONArray("suggestions")
        VideoKeywordSuggestions(
            suggestions = (0 until suggestions.length()).map { index ->
                val item = suggestions.getJSONObject(index)
                val ids = item.getJSONArray("event_ids")
                VideoKeywordSuggestion(
                    label = item.getString("label"),
                    eventIds = (0 until ids.length()).map { ids.getLong(it) },
                )
            },
            model = row.optString("model").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    suspend fun update(deviceId: String, scriptId: Long, scenes: List<VideoScene>): VideoScript = withContext(Dispatchers.IO) {
        val rows = JSONArray()
        scenes.forEach { scene -> rows.put(JSONObject()
            .put("stage", scene.stage)
            .put("title", scene.title)
            .put("date", scene.date)
            .put("text", scene.text)
            .put("source_event_ids", JSONArray(scene.sourceEventIds))) }
        parse(JSONObject(request("/videos/scripts/$scriptId", "PATCH", JSONObject().put("device_id", deviceId).put("scenes", rows))))
    }

    suspend fun render(deviceId: String, scriptId: Long, assets: List<VideoRenderAsset>): ByteArray =
        withContext(Dispatchers.IO) {
            require(assets.size in 3..40) { "视频需要三到七天的片段" }
            val boundary = "confidence-video-${System.nanoTime()}"
            val manifestScenes = JSONArray()
            val uploads = mutableListOf<Triple<String, String, File>>()
            assets.forEachIndexed { index, asset ->
                val frameName = "frame-$index.png"
                val narrationName = asset.narration?.let { "narration-$index.audio" }
                val originalName = asset.originalVoice?.let { "original-$index.audio" }
                manifestScenes.put(JSONObject()
                    .put("frame", frameName)
                    .put("duration_ms", asset.durationMs)
                    .put("script_scene_index", asset.scriptSceneIndex)
                    .put("narration", narrationName ?: "")
                    .put("narration_duration_ms", asset.narrationDurationMs)
                    .put("original", originalName ?: ""))
                uploads += Triple(frameName, "image/png", asset.frame)
                if (narrationName != null) uploads += Triple(narrationName, "application/octet-stream", requireNotNull(asset.narration))
                if (originalName != null) uploads += Triple(originalName, "application/octet-stream", requireNotNull(asset.originalVoice))
            }
            val connection = (URL("${resolvedBaseUrl.trimEnd('/')}/api/v1/videos/render/$scriptId").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 150_000
                doOutput = true
                setChunkedStreamingMode(64 * 1024)
                setRequestProperty("Accept", "video/mp4")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            fun DataOutputStream.textPart(name: String, value: String) {
                writeBytes("--$boundary\r\n")
                writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n")
                writeBytes("Content-Type: text/plain; charset=utf-8\r\n\r\n")
                write(value.toByteArray(Charsets.UTF_8))
                writeBytes("\r\n")
            }
            try {
                DataOutputStream(connection.outputStream).use { output ->
                    output.textPart("device_id", deviceId)
                    output.textPart("manifest", JSONObject().put("scenes", manifestScenes).toString())
                    uploads.forEach { (fileName, contentType, file) ->
                        output.writeBytes("--$boundary\r\n")
                        output.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"$fileName\"\r\n")
                        output.writeBytes("Content-Type: $contentType\r\n\r\n")
                        file.inputStream().use { it.copyTo(output) }
                        output.writeBytes("\r\n")
                    }
                    output.writeBytes("--$boundary--\r\n")
                }
                val status = connection.responseCode
                val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() } ?: ByteArray(0)
                if (status !in 200..299) {
                    val responseText = content.toString(Charsets.UTF_8)
                    val detail = runCatching { JSONObject(responseText).optString("detail") }.getOrDefault("")
                    throw IllegalStateException(detail.ifBlank { "视频合成服务返回 $status" })
                }
                content
            } finally {
                connection.disconnect()
            }
        }

    private fun parse(row: JSONObject): VideoScript {
        val scenes = row.getJSONArray("scenes")
        val sourceIds = row.getJSONArray("source_event_ids")
        return VideoScript(
            id = row.getLong("id"),
            scenes = (0 until scenes.length()).map { index ->
                val item = scenes.getJSONObject(index)
                val ids = item.getJSONArray("source_event_ids")
                VideoScene(
                    stage = item.getString("stage"),
                    title = item.optString("title", "成长记录"),
                    date = item.optString("date").takeIf { it.isNotBlank() && it != "null" },
                    text = item.getString("text"),
                    sourceEventIds = (0 until ids.length()).map { ids.getLong(it) },
                )
            },
            sourceEventIds = (0 until sourceIds.length()).map { sourceIds.getLong(it) },
            model = row.optString("model").takeIf { it.isNotBlank() && it != "null" },
            promptVersion = row.getString("prompt_version"),
            mock = row.getBoolean("mock"),
            userEdited = row.getBoolean("is_user_edited"),
        )
    }
}
