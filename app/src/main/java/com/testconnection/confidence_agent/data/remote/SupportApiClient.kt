package com.testconnection.confidence_agent.data.remote

import android.net.Uri
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SupportPerson(
    val id: Long,
    val name: String,
    val relationship: String,
    val kind: String,
    val scenarios: List<String>,
)

data class SupportSuggestion(
    val id: Long?,
    val supporterName: String,
    val reason: String,
    val editableMessage: String,
    val smallStep: String,
    val lighterOption: String,
)

data class GrowthEvent(
    val id: Long,
    val fact: String,
    val ownEffort: String?,
    val supportReceived: String?,
    val sourceId: Long?,
    val sourceFeedbackId: Long?,
    val sourceRecordId: Long?,
    val sensitivity: String?,
    val people: List<String>,
    val createdAt: String,
    val attempt: String? = null,
    val confidence: Double? = null,
    val sourceType: String? = null,
)

data class SupportFeedbackRecord(
    val id: Long,
    val supporterName: String,
    val outcome: String,
    val ownEffort: String?,
    val supportReceived: String?,
    val createdAt: String,
)

class SupportApiClient(private val baseUrl: String? = null) {
    private val resolvedBaseUrl: String get() = baseUrl ?: ServerEndpoint.current()

    private fun request(path: String, method: String, body: JSONObject? = null): String {
        val connection = (URL("${resolvedBaseUrl.trimEnd('/')}/api/v1$path").openConnection() as HttpURLConnection).apply {
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
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("支持服务返回 $status")
            return content
        } finally {
            connection.disconnect()
        }
    }

    suspend fun people(deviceId: String): List<SupportPerson> = withContext(Dispatchers.IO) {
        val array = JSONObject(request("/support-people?device_id=${Uri.encode(deviceId)}", "GET")).getJSONArray("people")
        buildList {
            for (index in 0 until array.length()) {
                val row = array.getJSONObject(index)
                val scenes = row.optJSONArray("scenarios") ?: JSONArray()
                add(SupportPerson(
                    row.getLong("id"), row.getString("name"), row.getString("relationship"), row.getString("kind"),
                    (0 until scenes.length()).map { scenes.getString(it) },
                ))
            }
        }
    }

    suspend fun savePerson(deviceId: String, person: SupportPerson): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("device_id", deviceId).put("name", person.name).put("relationship", person.relationship)
            .put("kind", person.kind).put("scenarios", JSONArray(person.scenarios))
        request(if (person.id > 0) "/support-people/${person.id}" else "/support-people", if (person.id > 0) "PATCH" else "POST", body)
    }

    suspend fun deletePerson(deviceId: String, id: Long): Unit = withContext(Dispatchers.IO) {
        request("/support-people/$id?device_id=${Uri.encode(deviceId)}", "DELETE")
    }

    suspend fun suggest(deviceId: String, situation: String, sourceMessageId: Long? = null): SupportSuggestion = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_id", deviceId).put("situation", situation)
        if (sourceMessageId != null) body.put("source_message_id", sourceMessageId)
        val json = JSONObject(request("/support/suggest", "POST", body))
        SupportSuggestion(
            json.optLong("suggestion_id").takeIf { it > 0 }, json.getString("supporter_name"),
            json.getString("reason"), json.getString("editable_message"),
            json.getString("small_step"), json.getString("lighter_option"),
        )
    }

    suspend fun feedback(deviceId: String, suggestionId: Long, outcome: String, ownEffort: String?, supportReceived: String?): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_id", deviceId).put("suggestion_id", suggestionId).put("outcome", outcome)
        if (!ownEffort.isNullOrBlank()) body.put("own_effort", ownEffort)
        if (!supportReceived.isNullOrBlank()) body.put("support_received", supportReceived)
        request("/support/feedback", "POST", body)
    }

    suspend fun feedbackHistory(deviceId: String): List<SupportFeedbackRecord> = withContext(Dispatchers.IO) {
        val rows = JSONObject(request("/support/feedback?device_id=${Uri.encode(deviceId)}", "GET")).getJSONArray("feedback")
        buildList {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                add(SupportFeedbackRecord(
                    row.getLong("id"), row.getString("supporter_name"), row.getString("outcome"),
                    row.optString("own_effort").takeIf { it.isNotBlank() && it != "null" },
                    row.optString("support_received").takeIf { it.isNotBlank() && it != "null" },
                    row.getString("created_at"),
                ))
            }
        }
    }

}
