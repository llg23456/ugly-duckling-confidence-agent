package com.testconnection.confidence_agent.data.remote

import android.net.Uri
import com.testconnection.confidence_agent.BuildConfig
import com.testconnection.confidence_agent.data.model.RecordDraft
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ReviewMoment(
    val eventId: Long?, val date: String, val title: String,
    val sourceMessageId: Long?, val sourceFeedbackId: Long?, val sourceRecordId: Long?,
)

data class ReviewSummary(
    val id: Long?, val period: String, val rangeStart: String, val rangeEnd: String,
    val title: String, val story: String, val ownEffort: String, val supportReceived: String,
    val pauseOrRestart: String, val nextStep: String, val moments: List<ReviewMoment>,
    val sourceEventIds: List<Long>,
)

data class SyncedRecord(val serverId: Long, val clientId: String)

class ReviewApiClient(private val baseUrl: String = BuildConfig.API_BASE_URL) {
    private fun request(path: String, method: String, body: JSONObject? = null): String {
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
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("回望服务返回 $status")
            return content
        } finally {
            connection.disconnect()
        }
    }

    suspend fun syncRecords(deviceId: String, records: List<RecordDraft>): List<SyncedRecord> = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext emptyList()
        val rows = JSONArray()
        records.forEach { record ->
            rows.put(JSONObject()
                .put("client_record_id", record.id)
                .put("mode", record.mode.name.lowercase(Locale.ROOT))
                .put("text", record.text)
                .put("photo_comment", record.photoComment)
                .put("status", record.status)
                .put("created_at_ms", record.createdAt))
        }
        val response = JSONObject(request("/records/sync", "POST", JSONObject().put("device_id", deviceId).put("records", rows)))
        val result = response.getJSONArray("records")
        (0 until result.length()).map { index ->
            result.getJSONObject(index).let { SyncedRecord(it.getLong("id"), it.getString("client_record_id")) }
        }
    }

    suspend fun events(deviceId: String): List<GrowthEvent> = withContext(Dispatchers.IO) {
        val rows = JSONObject(request("/events?device_id=${Uri.encode(deviceId)}", "GET")).getJSONArray("events")
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            GrowthEvent(
                row.getLong("id"), row.getString("fact"),
                row.optString("own_effort").takeIf { it.isNotBlank() && it != "null" },
                row.optString("support_received").takeIf { it.isNotBlank() && it != "null" },
                row.optLong("source_id").takeIf { it > 0 },
                row.optLong("source_feedback_id").takeIf { it > 0 },
                row.optLong("source_record_id").takeIf { it > 0 },
                row.optString("sensitivity").takeIf { it.isNotBlank() && it != "null" },
                row.optJSONArray("people")?.let { people -> (0 until people.length()).map { people.getString(it) } } ?: emptyList(),
                row.getString("created_at"),
            )
        }
    }

    suspend fun generate(deviceId: String, period: String, start: String? = null, end: String? = null): ReviewSummary = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_id", deviceId).put("period", period)
        if (start != null && end != null) {
            body.put("start_date", start)
            body.put("end_date", end)
        }
        parseReview(JSONObject(request("/reviews/generate", "POST", body)))
    }

    suspend fun pendingDaily(deviceId: String): List<ReviewSummary> = withContext(Dispatchers.IO) {
        val rows = JSONObject(request("/reviews/generate-pending-daily", "POST", JSONObject().put("device_id", deviceId)))
            .getJSONArray("reviews")
        (0 until rows.length()).map { parseReview(rows.getJSONObject(it)) }
    }

    private fun parseReview(row: JSONObject): ReviewSummary {
        val moments = row.getJSONArray("moments")
        val sourceIds = row.getJSONArray("source_event_ids")
        return ReviewSummary(
            id = row.optLong("id").takeIf { it > 0 },
            period = row.getString("period"),
            rangeStart = row.optString("range_start"),
            rangeEnd = row.optString("range_end"),
            title = row.getString("title"),
            story = row.optString("story"),
            ownEffort = row.getString("own_effort"),
            supportReceived = row.getString("support_received"),
            pauseOrRestart = row.optString("pause_or_restart"),
            nextStep = row.optString("next_step"),
            moments = (0 until moments.length()).map { index ->
                moments.getJSONObject(index).let { item ->
                    ReviewMoment(
                        item.optLong("event_id").takeIf { it > 0 }, item.getString("date"), item.getString("title"),
                        item.optLong("source_id").takeIf { it > 0 },
                        item.optLong("source_feedback_id").takeIf { it > 0 },
                        item.optLong("source_record_id").takeIf { it > 0 },
                    )
                }
            },
            sourceEventIds = (0 until sourceIds.length()).map { sourceIds.getLong(it) },
        )
    }
}
