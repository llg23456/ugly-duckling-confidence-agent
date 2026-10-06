package com.testconnection.confidence_agent.data.repository

import android.content.Context
import android.net.Uri
import com.testconnection.confidence_agent.data.model.CommunityDraft
import com.testconnection.confidence_agent.data.model.CommunityPost
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class CommunityPostStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadOwnPosts(): List<CommunityPost> {
        val rows = runCatching { JSONArray(preferences.getString(KEY_POSTS, "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id")
                if (id.isBlank()) continue
                add(
                    CommunityPost(
                        id = id,
                        authorName = row.optString("author_name", "我"),
                        authorBadge = row.optString("author_badge", "正在成长"),
                        title = row.optString("title"),
                        content = row.optString("content"),
                        imagePath = row.optString("image_path").takeIf(String::isNotBlank),
                        sourceRecordId = row.optString("source_record_id").takeIf(String::isNotBlank),
                        topic = row.optString("topic").takeIf(String::isNotBlank),
                        createdAt = row.optLong("created_at", System.currentTimeMillis()),
                        likeCount = row.optInt("like_count", 0),
                        commentCount = row.optInt("comment_count", 0),
                        shareCount = row.optInt("share_count", 0),
                        isMine = true,
                    )
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    fun avatarPath(): String? = preferences.getString(KEY_AVATAR_PATH, null)
        ?.takeIf { File(it).isFile }

    fun customAvatarPath(postId: String): String? = customAssetPath(KEY_CUSTOM_AVATAR_PREFIX, postId)

    fun customPostImagePath(postId: String): String? = customAssetPath(KEY_CUSTOM_IMAGE_PREFIX, postId)

    fun importAvatar(uri: Uri): String {
        val resolver = appContext.contentResolver
        val extension = when (resolver.getType(uri)?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val directory = File(appContext.filesDir, PROFILE_DIRECTORY).apply { mkdirs() }
        directory.listFiles().orEmpty().forEach(File::delete)
        val target = File(directory, "avatar.$extension")
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use(input::copyTo)
        } ?: error("无法读取头像图片")
        require(target.length() > 0L) { "头像图片为空" }
        preferences.edit().putString(KEY_AVATAR_PATH, target.absolutePath).commit()
        return target.absolutePath
    }

    fun importCustomAvatar(postId: String, uri: Uri): String =
        importCustomAsset(postId, uri, "avatar", KEY_CUSTOM_AVATAR_PREFIX)

    fun importCustomPostImage(postId: String, uri: Uri): String =
        importCustomAsset(postId, uri, "post", KEY_CUSTOM_IMAGE_PREFIX)

    fun publish(draft: CommunityDraft, authorName: String): CommunityPost {
        val title = draft.title.trim().ifBlank {
            draft.content.trim().take(22).ifBlank { "今天留下的一点" }
        }
        val content = draft.content.trim()
        val hasSourcePhoto = draft.sourcePhotoPath?.let { File(it).isFile } == true
        require(title.isNotBlank() && (content.isNotBlank() || hasSourcePhoto)) {
            "写下一点内容或选择一张照片后再发布"
        }
        val id = UUID.randomUUID().toString()
        val copiedPhoto = draft.sourcePhotoPath?.let { copyCommunityPhoto(it, id) }
        val post = CommunityPost(
            id = id,
            authorName = authorName.ifBlank { "我" },
            authorBadge = "正在成长",
            title = title.take(80),
            content = content.take(4000),
            imagePath = copiedPhoto,
            sourceRecordId = draft.sourceRecordId,
            topic = draft.topic?.take(40),
            createdAt = System.currentTimeMillis(),
            isMine = true,
        )
        write(listOf(post) + loadOwnPosts())
        return post
    }

    fun delete(postId: String) {
        val target = loadOwnPosts().firstOrNull { it.id == postId } ?: return
        target.imagePath?.let { path ->
            val file = File(path)
            val mediaDir = File(appContext.filesDir, MEDIA_DIRECTORY)
            if (file.parentFile?.canonicalFile == mediaDir.canonicalFile) file.delete()
        }
        write(loadOwnPosts().filterNot { it.id == postId })
        setLiked(postId, false)
        clearCustomAsset(KEY_CUSTOM_AVATAR_PREFIX, postId)
        clearCustomAsset(KEY_CUSTOM_IMAGE_PREFIX, postId)
    }

    fun isLiked(postId: String): Boolean = postId in likedPostIds()

    fun toggleLike(postId: String): Boolean {
        val liked = !isLiked(postId)
        setLiked(postId, liked)
        return liked
    }

    fun exportJson(): JSONArray = JSONArray().also { rows ->
        loadOwnPosts().forEach { post ->
            rows.put(JSONObject()
                .put("id", post.id)
                .put("author_name", post.authorName)
                .put("title", post.title)
                .put("content", post.content)
                .put("topic", post.topic ?: JSONObject.NULL)
                .put("source_record_id", post.sourceRecordId ?: JSONObject.NULL)
                .put("created_at_ms", post.createdAt)
                .put("liked_by_me", isLiked(post.id))
                .put("photo_file_in_zip", post.imagePath?.let { "media/community/${File(it).name}" } ?: JSONObject.NULL))
        }
    }

    fun exportProfileJson(): JSONObject = JSONObject()
        .put(
            "avatar_file_in_zip",
            avatarPath()?.let { "media/community_profile/${File(it).name}" } ?: JSONObject.NULL,
        )

    fun exportCustomizationsJson(): JSONArray = JSONArray().also { rows ->
        preferences.all.keys
            .filter { it.startsWith(KEY_CUSTOM_AVATAR_PREFIX) || it.startsWith(KEY_CUSTOM_IMAGE_PREFIX) }
            .sorted()
            .forEach { key ->
                val path = preferences.getString(key, null)?.takeIf { File(it).isFile } ?: return@forEach
                val isAvatar = key.startsWith(KEY_CUSTOM_AVATAR_PREFIX)
                rows.put(JSONObject()
                    .put("post_id", key.removePrefix(if (isAvatar) KEY_CUSTOM_AVATAR_PREFIX else KEY_CUSTOM_IMAGE_PREFIX))
                    .put("type", if (isAvatar) "avatar" else "post_image")
                    .put("file_in_zip", "media/community_custom/${File(path).name}"))
            }
    }

    fun clearAll() {
        preferences.edit().clear().commit()
        File(appContext.filesDir, MEDIA_DIRECTORY).deleteRecursively()
        File(appContext.filesDir, PROFILE_DIRECTORY).deleteRecursively()
        File(appContext.filesDir, CUSTOM_DIRECTORY).deleteRecursively()
    }

    private fun customAssetPath(prefix: String, postId: String): String? =
        preferences.getString(prefix + postId, null)?.takeIf { File(it).isFile }

    private fun importCustomAsset(postId: String, uri: Uri, kind: String, keyPrefix: String): String {
        require(postId.isNotBlank()) { "帖子标识为空" }
        val resolver = appContext.contentResolver
        val extension = when (resolver.getType(uri)?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val directory = File(appContext.filesDir, CUSTOM_DIRECTORY).apply { mkdirs() }
        clearCustomAsset(keyPrefix, postId)
        val safeId = postId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, "$kind-$safeId.$extension")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
            ?: error("无法读取图片")
        require(target.length() > 0L) { "图片内容为空" }
        preferences.edit().putString(keyPrefix + postId, target.absolutePath).commit()
        return target.absolutePath
    }

    private fun clearCustomAsset(prefix: String, postId: String) {
        preferences.getString(prefix + postId, null)?.let { path ->
            val file = File(path)
            val directory = File(appContext.filesDir, CUSTOM_DIRECTORY)
            if (runCatching { file.parentFile?.canonicalFile == directory.canonicalFile }.getOrDefault(false)) file.delete()
        }
        preferences.edit().remove(prefix + postId).commit()
    }

    private fun copyCommunityPhoto(sourcePath: String, postId: String): String? {
        val source = File(sourcePath)
        if (!source.exists() || !source.isFile) return null
        val extension = source.extension.takeIf { it.length in 2..5 } ?: "jpg"
        val directory = File(appContext.filesDir, MEDIA_DIRECTORY).apply { mkdirs() }
        val target = File(directory, "$postId.$extension")
        source.inputStream().use { input -> target.outputStream().use(input::copyTo) }
        return target.absolutePath
    }

    private fun write(posts: List<CommunityPost>) {
        val rows = JSONArray()
        posts.forEach { post ->
            rows.put(JSONObject()
                .put("id", post.id)
                .put("author_name", post.authorName)
                .put("author_badge", post.authorBadge)
                .put("title", post.title)
                .put("content", post.content)
                .put("image_path", post.imagePath.orEmpty())
                .put("source_record_id", post.sourceRecordId.orEmpty())
                .put("topic", post.topic.orEmpty())
                .put("created_at", post.createdAt)
                .put("like_count", post.likeCount)
                .put("comment_count", post.commentCount)
                .put("share_count", post.shareCount))
        }
        preferences.edit().putString(KEY_POSTS, rows.toString()).commit()
    }

    private fun likedPostIds(): Set<String> =
        preferences.getStringSet(KEY_LIKED, emptySet()).orEmpty().toSet()

    private fun setLiked(postId: String, liked: Boolean) {
        val ids = likedPostIds().toMutableSet()
        if (liked) ids += postId else ids -= postId
        preferences.edit().putStringSet(KEY_LIKED, ids).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "duck_community_posts"
        const val MEDIA_DIRECTORY = "community_media"
        const val PROFILE_DIRECTORY = "community_profile"
        const val CUSTOM_DIRECTORY = "community_custom"
        private const val KEY_POSTS = "posts"
        private const val KEY_LIKED = "liked_post_ids"
        private const val KEY_AVATAR_PATH = "avatar_path"
        private const val KEY_CUSTOM_AVATAR_PREFIX = "custom_avatar_"
        private const val KEY_CUSTOM_IMAGE_PREFIX = "custom_image_"
    }
}
