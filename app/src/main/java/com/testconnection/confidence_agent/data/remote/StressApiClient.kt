package com.testconnection.confidence_agent.data.remote

import android.net.Uri
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class StressTrendPoint(
    val date: String,
    val level: String,
    val signalCount: Int,
)

data class StressEvidence(
    val eventId: Long,
    val date: String,
    val excerpt: String,
    val sourceType: String,
    val sourceMessageId: Long?,
    val sourceRecordId: Long?,
    val level: String,
)

data class StressSupport(
    val supporterName: String,
    val reason: String,
    val editableMessage: String,
    val smallStep: String,
    val lighterOption: String,
)

data class StressFollowUp(
    val id: Long,
    val prompt: String,
    val ready: Boolean,
)

data class StressAlertDetail(
    val id: Long,
    val level: String,
    val status: String,
    val title: String,
    val prompt: String,
    val trend: List<StressTrendPoint>,
    val factors: List<String>,
    val evidence: List<StressEvidence>,
    val selfCheckImpact: String?,
    val selfCheckCoping: String?,
    val support: StressSupport,
    val supportCopy: String,
    val professionalSupportHint: String,
    val followUp: StressFollowUp?,
    val createdAt: String,
)

internal fun parseStressAlert(json: JSONObject): StressAlertDetail {
    return parseStressAlertMap(json.toValueMap())
}

internal fun parseStressAlertMap(json: Map<String, Any?>): StressAlertDetail {
    val trend = json.list("trend").map { raw ->
        val item = raw.asStringMap()
        StressTrendPoint(
            date = item.string("date"),
            level = item.string("level"),
            signalCount = item.number("signal_count").toInt(),
        )
    }
    val evidence = json.list("evidence").map { raw ->
        val item = raw.asStringMap()
        StressEvidence(
            eventId = item.number("event_id").toLong(),
            date = item.string("date"),
            excerpt = item.string("excerpt"),
            sourceType = item.string("source_type"),
            sourceMessageId = (item["source_message_id"] as? Number)?.toLong(),
            sourceRecordId = (item["source_record_id"] as? Number)?.toLong(),
            level = item.string("level"),
        )
    }
    val support = json.obj("support")
    val selfCheck = (json["self_check"] as? Map<*, *>)?.asStringMap()
    val followUp = (json["follow_up"] as? Map<*, *>)?.asStringMap()
    return StressAlertDetail(
        id = json.number("id").toLong(),
        level = json.string("level"),
        status = json.string("status"),
        title = json.string("title"),
        prompt = json.string("prompt"),
        trend = trend,
        factors = json.list("factors").map { it.toString() },
        evidence = evidence,
        selfCheckImpact = selfCheck?.get("impact")?.toString()?.takeIf(String::isNotBlank),
        selfCheckCoping = selfCheck?.get("coping")?.toString()?.takeIf(String::isNotBlank),
        support = StressSupport(
            supporterName = support.string("supporter_name"),
            reason = support.string("reason"),
            editableMessage = support.string("editable_message"),
            smallStep = support.string("small_step"),
            lighterOption = support.string("lighter_option"),
        ),
        supportCopy = json.string("support_copy"),
        professionalSupportHint = json.string("professional_support_hint"),
        followUp = followUp?.let {
            StressFollowUp(it.number("id").toLong(), it.string("prompt"), it["ready"] as Boolean)
        },
        createdAt = json.string("created_at"),
    )
}

private fun JSONObject.toValueMap(): Map<String, Any?> = keys().asSequence().associateWith { name ->
    when (val value = get(name)) {
        JSONObject.NULL -> null
        is JSONObject -> value.toValueMap()
        is JSONArray -> value.toValueList()
        else -> value
    }
}

private fun JSONArray.toValueList(): List<Any?> = List(length()) { index ->
    when (val value = get(index)) {
        JSONObject.NULL -> null
        is JSONObject -> value.toValueMap()
        is JSONArray -> value.toValueList()
        else -> value
    }
}

private fun Any?.asStringMap(): Map<String, Any?> =
    (this as Map<*, *>).entries.associate { (key, value) -> key.toString() to value }

private fun Map<String, Any?>.string(name: String): String = getValue(name).toString()
private fun Map<String, Any?>.number(name: String): Number = getValue(name) as Number
private fun Map<String, Any?>.list(name: String): List<Any?> = getValue(name) as List<Any?>
private fun Map<String, Any?>.obj(name: String): Map<String, Any?> = getValue(name).asStringMap()

class StressApiClient(private val baseUrl: String? = null) {
    private val resolvedBaseUrl: String get() = baseUrl ?: ServerEndpoint.current()

    suspend fun active(deviceId: String): StressAlertDetail? = withContext(Dispatchers.IO) {
        val json = request("/api/v1/stress-alerts/active?device_id=${Uri.encode(deviceId)}", "GET")
        json.optJSONObject("alert")?.let(::parseStressAlert)
    }

    suspend fun detail(deviceId: String, alertId: Long): StressAlertDetail = withContext(Dispatchers.IO) {
        parseStressAlert(request("/api/v1/stress-alerts/$alertId?device_id=${Uri.encode(deviceId)}", "GET"))
    }

    suspend fun prepareScenario(deviceId: String): StressAlertDetail = post(
        "/api/v1/stress-alerts/prepare-scenario",
        JSONObject().put("device_id", deviceId),
    )

    suspend fun submitSelfCheck(
        deviceId: String,
        alertId: Long,
        impact: String,
        coping: String,
    ): StressAlertDetail = post(
        "/api/v1/stress-alerts/$alertId/self-check",
        JSONObject().put("device_id", deviceId).put("impact", impact).put("coping", coping),
    )

    suspend fun scheduleFollowUp(deviceId: String, alertId: Long): StressAlertDetail = post(
        "/api/v1/stress-alerts/$alertId/follow-up",
        JSONObject().put("device_id", deviceId),
    )

    suspend fun makeFollowUpReady(deviceId: String, alertId: Long): StressAlertDetail = post(
        "/api/v1/stress-alerts/$alertId/follow-up/ready",
        JSONObject().put("device_id", deviceId),
    )

    suspend fun dismiss(deviceId: String, alertId: Long): StressAlertDetail = post(
        "/api/v1/stress-alerts/$alertId/dismiss",
        JSONObject().put("device_id", deviceId),
    )

    private suspend fun post(path: String, body: JSONObject): StressAlertDetail = withContext(Dispatchers.IO) {
        parseStressAlert(request(path, "POST", body))
    }

    private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
        val connection = (URL("${resolvedBaseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 40_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            body?.let { payload ->
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw IllegalStateException(detail?.takeIf(String::isNotBlank) ?: "压力提醒服务返回 $status")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
