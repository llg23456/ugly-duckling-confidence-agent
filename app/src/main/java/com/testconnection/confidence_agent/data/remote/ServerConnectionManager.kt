package com.testconnection.confidence_agent.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ServerHealth(
    val baseUrl: String,
    val model: String,
    val liveAi: Boolean,
)

object ServerConnectionManager {
    suspend fun check(input: String, timeoutMs: Int = 1_500): ServerHealth = withContext(Dispatchers.IO) {
        val baseUrl = ServerEndpoint.normalize(input)
        val connection = (URL("$baseUrl/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) error("健康检查返回 ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (json.optString("status") != "ok" || json.optString("service") != "confidence-agent-api") {
                error("该地址不是小丑鸭后端")
            }
            ServerHealth(
                baseUrl = baseUrl,
                model = json.optString("chat_model", "未知模型"),
                liveAi = json.optBoolean("ai_configured", false),
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun detectOnWifi(context: Context): ServerHealth {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.activeNetwork ?: error("当前没有可用网络")
        val capabilities = connectivity.getNetworkCapabilities(active)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            error("请先让手机和电脑连接同一个 Wi-Fi")
        }
        val address = connectivity.getLinkProperties(active)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?: error("没有读取到手机的 Wi-Fi IPv4 地址")
        val octets = address.address.map { it.toInt() and 0xff }
        val prefix = "${octets[0]}.${octets[1]}.${octets[2]}"
        val phoneHost = octets[3]

        val saved = runCatching { ServerEndpoint.normalize(ServerEndpoint.current()) }.getOrNull()
        if (saved != null && UriHost.sameSubnet(saved, prefix)) {
            runCatching { check(saved, 700) }.getOrNull()?.let { return it }
        }

        for (batch in (1..254).filter { it != phoneHost }.chunked(32)) {
            val found = coroutineScope {
                batch.map { host ->
                    async(Dispatchers.IO) {
                        runCatching { check("http://$prefix.$host:8000", 450) }.getOrNull()
                    }
                }.awaitAll().firstOrNull { it != null }
            }
            if (found != null) return found
        }
        error("同一 Wi-Fi 内没有找到小丑鸭后端，请确认电脑已启动服务且防火墙允许 8000 端口")
    }

    private object UriHost {
        fun sameSubnet(baseUrl: String, prefix: String): Boolean =
            runCatching { URL(baseUrl).host.startsWith("$prefix.") }.getOrDefault(false)
    }
}
