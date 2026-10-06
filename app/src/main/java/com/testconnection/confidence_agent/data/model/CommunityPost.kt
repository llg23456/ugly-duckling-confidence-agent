package com.testconnection.confidence_agent.data.model

data class CommunityPost(
    val id: String,
    val authorName: String,
    val authorBadge: String,
    val title: String,
    val content: String,
    val imagePath: String? = null,
    val sourceRecordId: String? = null,
    val topic: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val shareCount: Int = 0,
    val isMine: Boolean = false,
)

data class CommunityDraft(
    val title: String = "",
    val content: String = "",
    val sourceRecordId: String? = null,
    val sourcePhotoPath: String? = null,
    val topic: String? = null,
)

object CommunityRecordImport {
    fun from(record: RecordDraft): CommunityDraft? {
        if (record.status != "saved") return null
        val content = when (record.mode) {
            RecordMode.TEXT, RecordMode.VOICE -> record.text.trim()
            RecordMode.PHOTO -> record.photoComment.trim().ifBlank { record.aiDescription.trim() }
        }
        if (content.isBlank() && record.photoPath.isNullOrBlank()) return null
        val title = content.lineSequence().firstOrNull().orEmpty().trim().let {
            when {
                it.isBlank() -> "今天留下的一张照片"
                it.length <= 22 -> it
                else -> it.take(22) + "…"
            }
        }
        return CommunityDraft(
            title = title,
            content = content,
            sourceRecordId = record.id,
            sourcePhotoPath = record.photoPath.takeIf { record.mode == RecordMode.PHOTO },
            topic = when (record.mode) {
                RecordMode.TEXT -> "今日记录"
                RecordMode.VOICE -> "想说的话"
                RecordMode.PHOTO -> "生活片段"
            },
        )
    }
}
