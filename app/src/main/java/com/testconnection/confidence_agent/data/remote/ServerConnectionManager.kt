package com.testconnection.confidence_agent.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.Collections
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
    private const val DEFAULT_SERVER_PORT = 8000
    private const val SAVED_ADDRESS_TIMEOUT_MS = 800
    private const val SCAN_TIMEOUT_MS = 850
    private const val SCAN_BATCH_SIZE = 48
    private const val MAX_SCAN_HOSTS_PER_SUBNET = 1_022

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
        } catch (error: SocketTimeoutException) {
            throw IllegalStateException("连接超时，请确认电脑后端已启动且防火墙允许访问", error)
        } catch (error: UnknownHostException) {
            throw IllegalStateException("找不到服务器地址，请检查地址是否正确", error)
        } catch (error: ConnectException) {
            throw IllegalStateException("无法连接到服务器，请确认后端已启动且地址、端口正确", error)
        } catch (error: IOException) {
            throw IllegalStateException("连接服务器失败，请检查手机与电脑的网络连接", error)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun detectOnWifi(context: Context): ServerHealth {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // The saved endpoint is cheap to verify and may still work even when Android cannot expose
        // the current LAN interface (some vendors hide hotspot interfaces from app APIs).
        val saved = runCatching { ServerEndpoint.normalize(ServerEndpoint.current()) }.getOrNull()
        if (saved != null) {
            runCatching { check(saved, SAVED_ADDRESS_TIMEOUT_MS) }.getOrNull()?.let { return it }
        }

        val localSubnets = collectLocalSubnets(connectivity)
        if (localSubnets.isEmpty()) {
            error("没有识别到可扫描的局域网。请让手机和电脑连接同一 Wi-Fi，或让电脑连接当前手机热点")
        }

        localSubnets.forEach { subnet ->
            scanSubnet(subnet)?.let { return it }
        }
        error("当前局域网内没有找到小丑鸭后端。请确认电脑已启动服务，并允许防火墙访问 $DEFAULT_SERVER_PORT 端口")
    }

    private suspend fun scanSubnet(subnet: LocalSubnet): ServerHealth? {
        for (batch in subnet.hostAddresses().chunked(SCAN_BATCH_SIZE)) {
            val found = coroutineScope {
                batch.map { address ->
                    async(Dispatchers.IO) {
                        runCatching {
                            check("http://$address:$DEFAULT_SERVER_PORT", SCAN_TIMEOUT_MS)
                        }.getOrNull()
                    }
                }.awaitAll().firstOrNull { it != null }
            }
            if (found != null) return found
        }
        return null
    }

    private fun collectLocalSubnets(connectivity: ConnectivityManager): List<LocalSubnet> {
        val candidates = mutableListOf<LocalSubnet>()

        // Normal Wi-Fi / Ethernet mode. Query every network instead of only activeNetwork because
        // mobile data can remain the active network while a local network is still available.
        connectivity.allNetworks.forEach { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@forEach
            val isLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!isLan) return@forEach
            val properties = connectivity.getLinkProperties(network) ?: return@forEach
            properties.linkAddresses.forEach addressLoop@{ linkAddress ->
                val address = linkAddress.address as? Inet4Address ?: return@addressLoop
                if (address.isUsablePrivateAddress()) {
                    candidates += LocalSubnet(address, linkAddress.prefixLength, 300)
                }
            }
        }

        // Hotspot-host mode. Android usually reports mobile data as activeNetwork, so inspect local
        // interfaces such as wlan0/ap0/softap0 instead. Interface names vary by phone vendor.
        runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrDefault(emptyList())
            .filter { networkInterface ->
                runCatching { networkInterface.isUp && !networkInterface.isLoopback }
                    .getOrDefault(false) && networkInterface.isLikelyLanInterface()
            }
            .forEach { networkInterface ->
                networkInterface.interfaceAddresses.forEach addressLoop@{ interfaceAddress ->
                    val address = interfaceAddress.address as? Inet4Address ?: return@addressLoop
                    if (address.isUsablePrivateAddress()) {
                        candidates += LocalSubnet(
                            address = address,
                            prefixLength = interfaceAddress.networkPrefixLength.toInt(),
                            priority = networkInterface.lanPriority(),
                        )
                    }
                }
            }

        return candidates
            .filter { it.prefixLength in 1..30 }
            .distinctBy { it.networkKey }
            .sortedByDescending { it.priority }
    }

    private data class LocalSubnet(
        val address: Inet4Address,
        val prefixLength: Int,
        val priority: Int,
    ) {
        private val addressValue = address.toIpv4Long()
        private val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
        private val network = addressValue and mask
        private val broadcast = network or (mask.inv() and 0xffffffffL)

        val networkKey: String = "$network/$prefixLength"

        fun hostAddresses(): List<String> {
            val firstHost = network + 1
            val lastHost = broadcast - 1
            if (firstHost > lastHost) return emptyList()

            val hostCount = lastHost - firstHost + 1
            if (hostCount <= MAX_SCAN_HOSTS_PER_SUBNET) {
                return (firstHost..lastHost)
                    .asSequence()
                    .filter { it != addressValue }
                    .map(::ipv4String)
                    .toList()
            }

            // Very large campus/company networks are unsafe to scan in full. Scan the local /24
            // slice, which covers normal router and hotspot allocation while keeping detection fast.
            val localSliceStart = addressValue and 0xffffff00L
            val localSliceEnd = localSliceStart or 0xffL
            val firstLocalHost = maxOf(firstHost, localSliceStart + 1)
            val lastLocalHost = minOf(lastHost, localSliceEnd - 1)
            if (firstLocalHost > lastLocalHost) return emptyList()
            return (firstLocalHost..lastLocalHost)
                .asSequence()
                .filter { it != addressValue }
                .take(MAX_SCAN_HOSTS_PER_SUBNET)
                .map(::ipv4String)
                .toList()
        }
    }

    private fun Inet4Address.toIpv4Long(): Long = address.fold(0L) { value, octet ->
        (value shl 8) or (octet.toInt() and 0xff).toLong()
    }

    private fun ipv4String(value: Long): String = listOf(24, 16, 8, 0)
        .joinToString(".") { shift -> ((value shr shift) and 0xff).toString() }

    private fun Inet4Address.isUsablePrivateAddress(): Boolean {
        if (isLoopbackAddress || isLinkLocalAddress || isMulticastAddress) return false
        val octets = address.map { it.toInt() and 0xff }
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private fun NetworkInterface.isLikelyLanInterface(): Boolean {
        val value = name.lowercase()
        if (listOf("rmnet", "ccmni", "pdp", "wwan", "tun", "vpn").any(value::startsWith)) {
            return false
        }
        return listOf("wlan", "wifi", "ap", "softap", "swlan", "eth", "en", "br", "rndis", "usb")
            .any(value::startsWith)
    }

    private fun NetworkInterface.lanPriority(): Int {
        val value = name.lowercase()
        return when {
            listOf("ap", "softap", "swlan", "br").any(value::startsWith) -> 250
            listOf("wlan", "wifi").any(value::startsWith) -> 220
            listOf("eth", "en", "rndis", "usb").any(value::startsWith) -> 180
            else -> 100
        }
    }
}
