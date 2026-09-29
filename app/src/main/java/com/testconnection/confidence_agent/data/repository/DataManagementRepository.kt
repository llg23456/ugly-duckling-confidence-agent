package com.testconnection.confidence_agent.data.repository

import android.content.Context
import com.testconnection.confidence_agent.data.local.ChatDatabase
import com.testconnection.confidence_agent.data.remote.ReviewApiClient
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
        val serverData = runCatching { JSONObject(api.exportData(deviceId)) }.getOrElse { error ->
            JSONObject()
                .put("unavailable", true)
                .put("message", error.message ?: "后端数据暂时不可用")
        }
        val localRows = JSONArray()
        records.load().forEach { record ->
            localRows.put(JSONObject().apply {
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
        val manifest = JSONObject()
            .put("format", "ugly-duckling-data-export-v1")
            .put("server", serverData)
            .put("local_records", localRows)
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
