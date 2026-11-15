package com.testconnection.confidence_agent.data.remote

import android.net.Uri
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class WellbeingApiException(val statusCode: Int, message: String) : IllegalStateException(message)

class WellbeingApiClient {
    suspend fun request(path: String, deviceId: String, method: String = "GET", body: JSONObject? = null,
                        baseUrl: String = ServerEndpoint.current()): JSONObject =
        withContext(Dispatchers.IO) {
            val suffix = if (method == "GET" || method == "DELETE") {
                (if (path.contains("?")) "&" else "?") + "device_id=" + Uri.encode(deviceId)
            } else ""
            val payload = if (method in listOf("POST", "PATCH")) (body ?: JSONObject()).put("device_id", deviceId) else null
            val connection = URL(baseUrl.trimEnd('/') + "/api/v1/wellbeing/" + path + suffix)
                .openAuthorizedConnection().apply {
                    requestMethod = method
                    connectTimeout = 8_000
                    readTimeout = 25_000
                    setRequestProperty("Accept", "application/json")
                    if (payload != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                }
            try {
                payload?.let { connection.outputStream.use { stream -> stream.write(it.toString().toByteArray(Charsets.UTF_8)) } }
                val status = connection.checkedResponseCode()
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrDefault("")
                    throw WellbeingApiException(status, detail.ifBlank {
                        if (status == 404) "当前服务器还没有更新这项功能，请使用本地版测试。" else "服务暂时不可用，请重试。"
                    })
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } finally { connection.disconnect() }
        }
}

internal fun JSONObject.nullableScore(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
