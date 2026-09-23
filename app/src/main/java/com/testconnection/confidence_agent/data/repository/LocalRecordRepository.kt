package com.testconnection.confidence_agent.data.repository

import android.content.Context
import android.net.Uri
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.model.RecordMode
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class LocalRecordRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("duck_records", Context.MODE_PRIVATE)

    fun load(): List<RecordDraft> {
        val array = runCatching { JSONArray(preferences.getString("records", "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    RecordDraft(
                        id = item.optString("id"),
                        mode = runCatching { RecordMode.valueOf(item.optString("mode")) }.getOrDefault(RecordMode.TEXT),
                        text = item.optString("text"),
                        audioPath = item.optString("audio_path").takeIf(String::isNotBlank),
                        photoPath = item.optString("photo_path").takeIf(String::isNotBlank),
                        photoComment = item.optString("photo_comment"),
                        createdAt = item.optLong("created_at"),
                        status = item.optString("status", "saved"),
                    )
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    fun save(record: RecordDraft) {
        val current = load().filterNot { it.id == record.id }.toMutableList()
        current.add(0, record)
        val array = JSONArray()
        current.take(100).forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("mode", item.mode.name)
                put("text", item.text)
                put("audio_path", item.audioPath.orEmpty())
                put("photo_path", item.photoPath.orEmpty())
                put("photo_comment", item.photoComment)
                put("created_at", item.createdAt)
                put("status", item.status)
            })
        }
        preferences.edit().putString("records", array.toString()).apply()
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun importPhoto(uri: Uri): String {
        val directory = File(context.filesDir, "record_photos").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取这张照片" }
            target.outputStream().use(input::copyTo)
        }
        return target.absolutePath
    }

    fun importAudio(source: File): String {
        require(source.exists() && source.length() > 44) { "录音文件无效" }
        val directory = File(context.filesDir, "record_audio").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.wav")
        source.inputStream().use { input -> target.outputStream().use(input::copyTo) }
        return target.absolutePath
    }
}
