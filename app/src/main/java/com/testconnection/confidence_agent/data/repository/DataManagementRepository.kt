package com.testconnection.confidence_agent.data.repository

import android.content.Context
import com.testconnection.confidence_agent.data.local.ChatDatabase
import com.testconnection.confidence_agent.data.remote.ReviewApiClient
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DataManagementRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val records = LocalRecordRepository(appContext)
    private val api = ReviewApiClient()

    suspend fun export(deviceId: String): File = withContext(Dispatchers.IO) {
        val manifest = buildManifest(deviceId, mediaIncluded = true)
        val directory = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val output = File(directory, "小丑鸭数据-$stamp.zip")
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            addDirectory(zip, File(appContext.filesDir, "record_photos"), "media/photos")
            addDirectory(zip, File(appContext.filesDir, "record_audio"), "media/audio")
            addDirectory(zip, File(appContext.filesDir, "chat_images"), "media/chat_images")
            addDirectory(zip, File(appContext.filesDir, "generated_videos"), "media/generated_videos")
        }
        output
    }

    suspend fun exportJson(deviceId: String): File = withContext(Dispatchers.IO) {
        val manifest = buildManifest(deviceId, mediaIncluded = false)
        val directory = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        File(directory, "小丑鸭数据-$stamp.json").also {
            it.writeText(manifest.toString(2), Charsets.UTF_8)
        }
    }

    private suspend fun buildManifest(deviceId: String, mediaIncluded: Boolean): JSONObject {
        val serverData = runCatching { JSONObject(api.exportData(deviceId)) }.getOrElse { error ->
            JSONObject()
                .put("unavailable", true)
                .put("message", error.message ?: "后端数据暂时不可用")
        }
        val serverUnavailable = serverData.optBoolean("unavailable", false)
        val data = serverData.optJSONObject("data") ?: JSONObject()
        val localRows = buildLocalRows()
        val localByClientId = mutableMapOf<String, JSONObject>()
        for (index in 0 until localRows.length()) {
            localRows.optJSONObject(index)?.let { localByClientId[it.optString("id")] = it }
        }

        val growthEvents = data.optJSONArray("growth_events") ?: JSONArray()
        val eventByMessageId = mutableMapOf<Long, JSONObject>()
        val eventByRecordId = mutableMapOf<Long, JSONObject>()
        for (index in 0 until growthEvents.length()) {
            val event = growthEvents.optJSONObject(index) ?: continue
            if (!event.isNull("source_user_message_id")) eventByMessageId[event.optLong("source_user_message_id")] = event
            if (!event.isNull("source_record_id")) eventByRecordId[event.optLong("source_record_id")] = event
        }

        val days = LinkedHashMap<String, JSONArray>()
        val messages = data.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until messages.length()) {
            val source = messages.optJSONObject(index) ?: continue
            val createdAt = source.optString("created_at")
            val localTime = toLocalTimestamp(createdAt)
            val day = localTime.take(10).ifBlank { "unknown" }
            val role = source.optString("role")
            val event = eventByMessageId[source.optLong("id")]
            val confidenceStatus = when {
                role != "user" -> "not_applicable_assistant_reply"
                event != null -> "assessed"
                else -> "no_fact_extracted"
            }
            days.getOrPut(day) { JSONArray() }.put(JSONObject()
                .put("id", source.opt("id"))
                .put("local_time", localTime)
                .put("created_at_utc", createdAt)
                .put("role", role)
                .put("modality", source.optString("modality"))
                .put("content", source.optString("content"))
                .put("media_ref", source.optNullable("media_ref"))
                .put("is_mock", source.optBoolean("is_mock"))
                .put("used_memory_ids", source.optJSONArray("used_memory_ids") ?: JSONArray())
                .put("confidence_status", confidenceStatus)
                .put("confidence", event?.optNullable("confidence") ?: JSONObject.NULL)
                .put("confidence_threshold", if (event != null) 0.75 else JSONObject.NULL)
                .put("extracted_fact", event?.optNullable("fact") ?: JSONObject.NULL)
                .put("value_score", event?.optNullable("value_score") ?: JSONObject.NULL)
                .put("score_components", event?.optNullable("score_components") ?: JSONObject.NULL)
                .put("memory_decision", event?.optNullable("memory_decision") ?: JSONObject.NULL)
                .put("sensitivity", event?.optNullable("sensitivity") ?: JSONObject.NULL))
        }
        val dailyChats = JSONArray()
        days.forEach { (day, dayMessages) ->
            dailyChats.put(JSONObject()
                .put("date", day)
                .put("message_count", dayMessages.length())
                .put("messages", dayMessages))
        }

        val savedRecords = JSONArray()
        val serverRecords = data.optJSONArray("records") ?: JSONArray()
        val includedLocalIds = mutableSetOf<String>()
        for (index in 0 until serverRecords.length()) {
            val source = serverRecords.optJSONObject(index) ?: continue
            val clientId = source.optString("client_record_id")
            val local = localByClientId[clientId]
            includedLocalIds += clientId
            savedRecords.put(recordForExport(source, local, eventByRecordId[source.optLong("id")]))
        }
        for (index in 0 until localRows.length()) {
            val local = localRows.optJSONObject(index) ?: continue
            if (local.optString("id") !in includedLocalIds) savedRecords.put(recordForExport(null, local, null))
        }

        return JSONObject()
            .put("format", "ugly-duckling-data-export-v2")
            .put("export_info", JSONObject()
                .put("exported_at", serverData.optString("exported_at", LocalDateTime.now().toString()))
                .put("device_id", deviceId)
                .put("timezone", TimeZone.getDefault().id)
                .put("media_files_included", mediaIncluded)
                .put("server_data_available", !serverUnavailable)
                .put("server_error", if (serverUnavailable) serverData.optString("message") else JSONObject.NULL)
                .put("confidence_note", "每条消息都有 confidence_status；仅用户消息会参与事实置信度评估，助手回复的 confidence 为 null。")
                .put("summary", JSONObject()
                    .put("chat_day_count", dailyChats.length())
                    .put("message_count", messages.length())
                    .put("saved_record_count", savedRecords.length()))
                .put("assessment_rules", JSONObject()
                    .put("confidence_threshold", 0.75)
                    .put("value_formula", "0.30*long_term_value + 0.25*growth_significance + 0.20*specificity + 0.15*future_reuse + 0.10*support_value")
                    .put("long_term", "value_score >= 0.72")
                    .put("daily_summary", "0.52 <= value_score < 0.72")
                    .put("ignore", "value_score < 0.52 or no concrete fact")))
            .put("daily_chats", dailyChats)
            .put("saved_records", savedRecords)
            .put("memory_and_growth", JSONObject()
                .put("growth_events", growthEvents)
                .put("long_term_memories", data.optJSONArray("memories") ?: JSONArray())
                .put("daily_summaries", data.optJSONArray("daily_summaries") ?: JSONArray())
                .put("period_reviews", data.optJSONArray("reviews") ?: JSONArray()))
            .put("support", JSONObject()
                .put("people", data.optJSONArray("support_people") ?: JSONArray())
                .put("suggestions", data.optJSONArray("support_suggestions") ?: JSONArray())
                .put("feedback", data.optJSONArray("support_feedback") ?: JSONArray()))
            .put("proactive_check_ins", data.optJSONArray("proactive_check_ins") ?: JSONArray())
            .put("generated_content", JSONObject()
                .put("video_scripts", data.optJSONArray("video_scripts") ?: JSONArray()))
    }

    private fun buildLocalRows(): JSONArray = JSONArray().also { rows ->
        records.load().forEach { record ->
            rows.put(JSONObject().apply {
                put("id", record.id)
                put("mode", record.mode.name.lowercase())
                put("text", record.text)
                put("photo_comment", record.photoComment)
                put("ai_description", record.aiDescription)
                put("created_at_ms", record.createdAt)
                put("status", record.status)
                put("photo_file", record.photoPath?.let { "media/photos/${File(it).name}" })
                put("audio_file", record.audioPath?.let { "media/audio/${File(it).name}" })
            })
        }
    }

    private fun recordForExport(server: JSONObject?, local: JSONObject?, event: JSONObject?): JSONObject = JSONObject()
        .put("id", local?.optNullable("id") ?: server?.optNullable("client_record_id") ?: JSONObject.NULL)
        .put("server_id", server?.optNullable("id") ?: JSONObject.NULL)
        .put("mode", local?.optNullable("mode") ?: server?.optNullable("mode") ?: JSONObject.NULL)
        .put("text", local?.optString("text").orEmpty().ifBlank { server?.optString("text").orEmpty() })
        .put("photo_comment", local?.optString("photo_comment").orEmpty().ifBlank { server?.optString("photo_comment").orEmpty() })
        .put("ai_description", local?.optString("ai_description").orEmpty().ifBlank { server?.optString("ai_description").orEmpty() })
        .put("status", local?.optNullable("status") ?: server?.optNullable("status") ?: JSONObject.NULL)
        .put("created_at", server?.optNullable("created_at") ?: local?.optNullable("created_at_ms") ?: JSONObject.NULL)
        .put("photo_file_in_zip", local?.optNullable("photo_file") ?: JSONObject.NULL)
        .put("audio_file_in_zip", local?.optNullable("audio_file") ?: JSONObject.NULL)
        .put("confidence_status", if (event != null) "trusted_user_record" else "not_available")
        .put("confidence", event?.optNullable("confidence") ?: JSONObject.NULL)
        .put("extracted_fact", event?.optNullable("fact") ?: JSONObject.NULL)
        .put("value_score", event?.optNullable("value_score") ?: JSONObject.NULL)
        .put("memory_decision", event?.optNullable("memory_decision") ?: JSONObject.NULL)

    private fun JSONObject.optNullable(name: String): Any =
        if (has(name) && !isNull(name)) opt(name) ?: JSONObject.NULL else JSONObject.NULL

    private fun toLocalTimestamp(value: String): String = runCatching {
        LocalDateTime.parse(value).atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().toString()
    }.getOrDefault(value)

    suspend fun deleteAll(deviceId: String) = withContext(Dispatchers.IO) {
        api.deleteAllData(deviceId)
        ChatDatabase.get(appContext).messages().clear()
        records.clearAll()
        ReviewCacheStore(appContext).clear()
        listOf("chat_images", "generated_videos").forEach { name ->
            File(appContext.filesDir, name).deleteRecursively()
        }
        listOf("exports", "camera").forEach { name ->
            File(appContext.cacheDir, name).deleteRecursively()
        }
        listOf(
            "duck_onboarding",
            "duck_voice_preferences",
            "duck_widget_privacy",
        ).forEach { name -> appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        if (!directory.exists()) return
        directory.listFiles().orEmpty().filter(File::isFile).forEach { file ->
            zip.putNextEntry(ZipEntry("$prefix/${file.name}"))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
