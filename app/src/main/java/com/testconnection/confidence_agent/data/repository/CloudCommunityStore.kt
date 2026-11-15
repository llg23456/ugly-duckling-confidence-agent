package com.testconnection.confidence_agent.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CloudCommunityDraft(
    val title: String = "", val content: String = "", val topic: String = "成长分享",
    val pictures: List<String> = emptyList(), val uploaded: List<String> = emptyList(),
    val clientId: String = UUID.randomUUID().toString(), val pending: Boolean = false,
) {
    fun payload() = JSONObject().put("client_id", clientId).put("title", title.trim())
        .put("content", content.trim()).put("topic", topic).put("media_ids", JSONArray(uploaded))
    fun json() = payload().put("pictures", JSONArray(pictures)).put("pending", pending)
    companion object {
        fun parse(row: JSONObject) = CloudCommunityDraft(row.optString("title"), row.optString("content"),
            row.optString("topic", "成长分享"), row.optJSONArray("pictures").strings(),
            row.optJSONArray("media_ids").strings(), row.optString("client_id", UUID.randomUUID().toString()), row.optBoolean("pending"))
    }
}

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).map { getString(it) }
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).map { getJSONObject(it) }

/** Local-only drafts and last public feed. No private profile/record is copied automatically. */
class CloudCommunityStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val directory get() = File(app.filesDir, DIRECTORY).apply { mkdirs() }
    fun draft(): CloudCommunityDraft = runCatching {
        CloudCommunityDraft.parse(JSONObject(prefs.getString("draft", "{}")!!))
    }.getOrDefault(CloudCommunityDraft())
    fun save(draft: CloudCommunityDraft) { check(prefs.edit().putString("draft", draft.json().toString()).commit()) }
    fun clearDraft() {
        prefs.edit().remove("draft").commit()
        File(directory, "drafts").deleteRecursively()
    }
    fun cache(rows: List<JSONObject>) { prefs.edit().putString("feed", JSONArray(rows.take(100)).toString()).apply() }
    fun cache(): List<JSONObject> = runCatching { JSONArray(prefs.getString("feed", "[]")).objects() }.getOrDefault(emptyList())
    fun clearFeed() { prefs.edit().remove("feed").commit() }
    fun clearAll() {
        prefs.edit().clear().commit()
        directory.deleteRecursively()
        CommunityImageCache.clearAll(app)
    }

    suspend fun importPicture(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null,
            BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("无法读取图片")
        val orientation = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f); 7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
            }
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val scale = minOf(1f, 1600f / maxOf(rotated.width, rotated.height))
        val scaled = Bitmap.createScaledBitmap(rotated, maxOf(1, (rotated.width*scale).toInt()), maxOf(1, (rotated.height*scale).toInt()), true)
        val target = File(File(directory, "drafts").apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        try { target.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
        finally { scaled.recycle(); if (rotated !== scaled) rotated.recycle(); if (decoded !== rotated && decoded !== scaled) decoded.recycle() }
        require(target.length() <= 5 * 1024 * 1024) { "图片过大，请换一张" }
        target.absolutePath
    }

    companion object {
        const val PREFERENCES = "cloud_community_v1"
        const val DIRECTORY = "cloud_community"
    }
}
