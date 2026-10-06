package com.testconnection.confidence_agent.data.remote

import android.net.Uri
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.net.HttpURLConnection
import java.net.URL
import java.io.DataOutputStream
import java.io.File
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ReviewMoment(
    val eventId: Long?, val date: String, val title: String,
    val sourceMessageId: Long?, val sourceFeedbackId: Long?, val sourceRecordId: Long?,
)

data class ReviewSection(
    val key: String,
    val title: String,
    val content: String,
)

data class ReviewSummary(
    val id: Long?, val period: String, val rangeStart: String, val rangeEnd: String,
    val title: String, val story: String, val ownEffort: String, val supportReceived: String,
    val pauseOrRestart: String, val nextStep: String, val moments: List<ReviewMoment>,
    val sourceEventIds: List<Long>, val closing: String,
    val sections: List<ReviewSection> = emptyList(),
    val affirmation: String = "",
)

data class SyncedRecord(val serverId: Long, val clientId: String)
data class DemoDataResult(
    val created: Int,
    val deleted: Int,
    val theme: String,
    val rangeStart: String? = null,
    val rangeEnd: String? = null,
)
data class ReviewOverview(
    val review: ReviewSummary,
    val dailyReviews: List<Pair<String, ReviewSummary?>> = emptyList(),
    val weeklyReviews: List<Triple<String, String, ReviewSummary?>> = emptyList(),
    internal val rawJson: String = "",
)

class ReviewApiClient(private val baseUrl: String? = null) {
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
            if (status !in 200..299) throw IllegalStateException("回望服务返回 $status")
            return content
        } finally {
            connection.disconnect()
        }
    }

    suspend fun syncRecords(deviceId: String, records: List<RecordDraft>): List<SyncedRecord> = withContext(Dispatchers.IO) {
        val validRecords = records.filter { it.id.isNotBlank() }.take(100)
        if (validRecords.isEmpty()) return@withContext emptyList()
        val rows = JSONArray()
        validRecords.forEach { record ->
            val safeCreatedAt = record.createdAt.takeIf { it in 946684800000L..4102444800000L }
                ?: System.currentTimeMillis()
            rows.put(JSONObject()
                .put("client_record_id", record.id.take(80))
                .put("mode", record.mode.name.lowercase(Locale.ROOT))
                .put("text", record.text.take(4000))
                .put("photo_comment", record.photoComment.take(2000))
                .put("ai_description", record.aiDescription.take(2000))
                .put("status", if (record.status == "draft") "draft" else "saved")
                .put("created_at_ms", safeCreatedAt))
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
                attempt = row.optString("attempt").takeIf { it.isNotBlank() && it != "null" },
                confidence = if (row.isNull("confidence")) null else row.optDouble("confidence"),
                sourceType = row.optString("source_type").takeIf { it.isNotBlank() && it != "null" },
                feeling = row.optString("feeling").takeIf { it.isNotBlank() && it != "null" },
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

    suspend fun overview(deviceId: String, period: String, start: String, end: String): ReviewOverview =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("period", period)
                .put("start_date", start)
                .put("end_date", end)
            runCatching { parseOverview(request("/reviews/overview", "POST", body)) }
                .getOrElse { error ->
                    val message = error.message.orEmpty()
                    if (!message.contains("返回 404") && !message.contains("返回 405")) throw error
                    // 兼容仍在运行的旧后端：至少加载主报告；重启后端后自动恢复完整子摘要。
                    ReviewOverview(
                        review = parseReview(JSONObject(request("/reviews/generate", "POST", body))),
                    )
                }
        }

    suspend fun pendingDaily(deviceId: String): List<ReviewSummary> = withContext(Dispatchers.IO) {
        val rows = JSONObject(request("/reviews/generate-pending-daily", "POST", JSONObject().put("device_id", deviceId)))
            .getJSONArray("reviews")
        (0 until rows.length()).map { parseReview(rows.getJSONObject(it)) }
    }

    suspend fun record(deviceId: String, serverId: Long): RecordDraft = withContext(Dispatchers.IO) {
        val row = JSONObject(request("/records/$serverId?device_id=${Uri.encode(deviceId)}", "GET"))
        RecordDraft(
            id = row.getString("client_record_id"),
            mode = runCatching { com.testconnection.confidence_agent.data.model.RecordMode.valueOf(row.getString("mode").uppercase(Locale.ROOT)) }
                .getOrDefault(com.testconnection.confidence_agent.data.model.RecordMode.TEXT),
            text = row.optString("text"),
            photoComment = row.optString("photo_comment"),
            aiDescription = row.optString("ai_description"),
            createdAt = runCatching {
                val raw = row.getString("created_at")
                runCatching { OffsetDateTime.parse(raw).toInstant() }
                    .getOrElse { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }
                    .toEpochMilli()
            }.getOrDefault(System.currentTimeMillis()),
            status = row.optString("status", "saved"),
        )
    }

    suspend fun deleteRecord(deviceId: String, clientRecordId: String) = withContext(Dispatchers.IO) {
        request("/records/client/${Uri.encode(clientRecordId)}?device_id=${Uri.encode(deviceId)}", "DELETE")
    }

    suspend fun describePhoto(file: File): String = withContext(Dispatchers.IO) {
        require(file.exists() && file.length() > 0) { "照片文件不存在" }
        val header = ByteArray(12)
        file.inputStream().use { it.read(header) }
        val mimeType = when {
            header.take(8).map { it.toInt() and 0xFF } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
            header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
        val extension = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val boundary = "DuckRecord${System.currentTimeMillis()}"
        val connection = (URL("${resolvedBaseUrl.trimEnd('/')}/api/v1/records/describe-photo").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 70_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(connection.outputStream).use { output ->
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"record.$extension\"\r\n")
                output.writeBytes("Content-Type: $mimeType\r\n\r\n")
                file.inputStream().use { it.copyTo(output) }
                output.writeBytes("\r\n--$boundary--\r\n")
            }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("照片描述服务返回 $status")
            JSONObject(content).getString("description")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun exportData(deviceId: String): String = withContext(Dispatchers.IO) {
        request("/data/export?device_id=${Uri.encode(deviceId)}", "GET")
    }

    suspend fun deleteAllData(deviceId: String) = withContext(Dispatchers.IO) {
        request("/data?device_id=${Uri.encode(deviceId)}", "DELETE")
    }

    suspend fun createDemoData(deviceId: String, preset: String? = null): DemoDataResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_id", deviceId)
        if (preset != null) body.put("preset", preset)
        parseDemoResult(JSONObject(request("/dev/demo-data", "POST", body)))
    }

    suspend fun clearDemoData(deviceId: String): DemoDataResult = withContext(Dispatchers.IO) {
        parseDemoResult(JSONObject(request("/dev/demo-data", "DELETE", JSONObject().put("device_id", deviceId))))
    }

    private fun parseDemoResult(row: JSONObject) = DemoDataResult(
        created = row.optInt("created"),
        deleted = row.optInt("deleted"),
        theme = row.optString("theme"),
        rangeStart = row.optString("range_start").takeIf { it.isNotBlank() && it != "null" },
        rangeEnd = row.optString("range_end").takeIf { it.isNotBlank() && it != "null" },
    )

    companion object {
        fun parseOverview(raw: String): ReviewOverview {
            val row = JSONObject(raw)
            val daily = row.optJSONArray("daily_reviews") ?: JSONArray()
            val weekly = row.optJSONArray("weekly_reviews") ?: JSONArray()
            return ReviewOverview(
                review = parseReview(row.getJSONObject("review")),
                dailyReviews = (0 until daily.length()).map { index ->
                    daily.getJSONObject(index).let { item ->
                        item.getString("date") to item.optJSONObject("review")?.let(::parseReview)
                    }
                },
                weeklyReviews = (0 until weekly.length()).map { index ->
                    weekly.getJSONObject(index).let { item ->
                        Triple(
                            item.getString("start"),
                            item.getString("end"),
                            item.optJSONObject("review")?.let(::parseReview),
                        )
                    }
                },
                rawJson = raw,
            )
        }

        private fun parseReview(row: JSONObject): ReviewSummary {
            val moments = row.getJSONArray("moments")
            val sourceIds = row.getJSONArray("source_event_ids")
            val sections = row.optJSONArray("sections") ?: JSONArray()
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
                closing = row.optString("closing"),
                sections = (0 until sections.length()).map { index ->
                    sections.getJSONObject(index).let { item ->
                        ReviewSection(
                            key = item.optString("key"),
                            title = item.optString("title"),
                            content = item.optString("content"),
                        )
                    }
                },
                affirmation = row.optString("affirmation"),
            )
        }
    }
}
