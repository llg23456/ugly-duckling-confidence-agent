package com.testconnection.confidence_agent.data.remote

import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CommunityHttpException(val status: Int, message: String) : Exception(message)

/** Uses the same device-bound credential as private APIs. Never puts credentials in image URLs. */
class CommunityApiClient(private val deviceId: String) {
    private fun url(path: String, query: Map<String, String> = emptyMap()): URL {
        val values = query + ("device_id" to deviceId)
        return URL(ServerEndpoint.current().trimEnd('/') + "/api/v1/community" + path + "?" +
            values.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" })
    }

    suspend fun request(method: String, path: String, body: JSONObject? = null,
                        query: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val connection = url(path, query).openAuthorizedConnection().apply {
            requestMethod = method; instanceFollowRedirects = false
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (method !in listOf("GET", "HEAD")) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write((body ?: JSONObject()).put("device_id", deviceId)
                    .toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.checkedResponseCode()
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) fail(status, text)
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }

    suspend fun upload(path: String): String = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.isFile && file.length() <= 5 * 1024 * 1024) { "图片已失效或超过5MB，请重新选择" }
        val boundary = "Duck" + UUID.randomUUID().toString().replace("-", "")
        val connection = url("/media").openAuthorizedConnection().apply {
            requestMethod = "POST"; instanceFollowRedirects = false; doOutput = true
            connectTimeout = 15_000; readTimeout = 45_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.buffered().use { output ->
                fun text(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                text("--$boundary\r\nContent-Disposition: form-data; name=\"device_id\"\r\n\r\n$deviceId\r\n")
                text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                file.inputStream().use { it.copyTo(output) }; text("\r\n--$boundary--\r\n")
            }
            val status = connection.checkedResponseCode()
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) fail(status, text)
            JSONObject(text).getString("id")
        } finally { connection.disconnect() }
    }

    suspend fun media(id: String): ByteArray = withContext(Dispatchers.IO) {
        UUID.fromString(id)
        val connection = url("/media/$id").openAuthorizedConnection().apply {
            instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 20_000
        }
        try {
            val status = connection.checkedResponseCode()
            if (status !in 200..299) throw CommunityHttpException(status, "图片已下架或暂时不可用")
            connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private fun fail(status: Int, text: String): Nothing {
        val detail = runCatching { JSONObject(text).optString("detail") }.getOrDefault("")
        throw CommunityHttpException(status, if (status == 422) "请检查字数、昵称或图片后重试" else
            detail.takeIf { it.isNotBlank() && !it.startsWith("[") } ?: "社区暂时不可用（$status），草稿已保留")
    }
}
