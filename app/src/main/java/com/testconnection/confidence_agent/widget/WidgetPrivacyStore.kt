package com.testconnection.confidence_agent.widget

import android.content.Context
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

data class WidgetSnapshot(
    val allowed: Boolean,
    val todayText: String,
    val todayPhotoPath: String?,
    val todaySelected: Boolean,
    val monthCount: Int,
    val monthText: String,
    val monthPhotoPath: String?,
    val monthSelected: Boolean,
    val updatedAtMs: Long,
)

class WidgetPrivacyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("duck_widget_privacy", Context.MODE_PRIVATE)

    fun isAllowed(): Boolean = preferences.getBoolean("allow_public_growth", false)

    fun setAllowed(allowed: Boolean) {
        preferences.edit().putBoolean("allow_public_growth", allowed).apply()
        if (!allowed) preferences.edit().remove("today_text").remove("today_photo_path")
            .remove("month_count").remove("month_text").remove("month_photo_path")
            .remove("updated_at_ms").apply()
    }

    fun selectTodayRecord(record: RecordDraft?) = saveSelection("today", record)

    fun selectMonthRecord(record: RecordDraft?) = saveSelection("month", record)

    fun selectedTodayRecordId(): String? = preferences.getString("today_selected_id", "").orEmpty().ifBlank { null }

    fun selectedMonthRecordId(): String? = preferences.getString("month_selected_id", "").orEmpty().ifBlank { null }

    fun update(events: List<GrowthEvent>, records: List<RecordDraft> = emptyList()) {
        if (!isAllowed()) return
        val today = LocalDate.now()
        val visible = events.filter(::isSafeForWidget)
        val todayEvent = visible.firstOrNull { eventLocalDay(it.createdAt) == today }
        val monthCount = visible.count { eventLocalDay(it.createdAt)?.let(YearMonth::from) == YearMonth.from(today) }
        val todayRecord = resolveSelection("today", records)
        val monthRecord = resolveSelection("month", records)
        preferences.edit()
            .putString("today_text", recordText(todayRecord).ifBlank { todayEvent?.fact?.take(70).orEmpty() })
            .putString("today_photo_path", todayRecord?.photoPath.orEmpty())
            .putString("today_date", today.toString())
            .putInt("month_count", monthCount)
            .putString("month_text", recordText(monthRecord))
            .putString("month_photo_path", monthRecord?.photoPath.orEmpty())
            .putString("month_key", YearMonth.from(today).toString())
            .putLong("updated_at_ms", System.currentTimeMillis())
            .apply()
    }

    fun snapshot(): WidgetSnapshot {
        if (!isAllowed()) return WidgetSnapshot(false, "", null, false, 0, "", null, false, 0)
        val today = LocalDate.now()
        val todaySelected = preferences.getString("today_selected_id", "").orEmpty().isNotBlank()
        return WidgetSnapshot(
            allowed = true,
            todayText = preferences.getString("today_text", "").orEmpty().takeIf {
                todaySelected || preferences.getString("today_date", "") == today.toString()
            }.orEmpty(),
            todayPhotoPath = preferences.getString("today_photo_path", "").orEmpty().ifBlank { null },
            todaySelected = todaySelected,
            monthCount = preferences.getInt("month_count", 0).takeIf {
                preferences.getString("month_key", "") == YearMonth.from(today).toString()
            } ?: 0,
            monthText = preferences.getString("month_text", "").orEmpty(),
            monthPhotoPath = preferences.getString("month_photo_path", "").orEmpty().ifBlank { null },
            monthSelected = preferences.getString("month_selected_id", "").orEmpty().isNotBlank(),
            updatedAtMs = preferences.getLong("updated_at_ms", 0),
        )
    }

    private fun saveSelection(slot: String, record: RecordDraft?) {
        val editor = preferences.edit()
            .putString("${slot}_selected_id", record?.id.orEmpty())
            .putString("${slot}_text", recordText(record))
            .putString("${slot}_photo_path", record?.photoPath.orEmpty())
        if (slot == "today") {
            editor
                .putString("today_text", recordText(record))
                .putString("today_photo_path", record?.photoPath.orEmpty())
                .putString("today_date", LocalDate.now().toString())
        } else {
            editor
                .putString("month_text", recordText(record))
                .putString("month_photo_path", record?.photoPath.orEmpty())
                .putString("month_key", YearMonth.from(LocalDate.now()).toString())
        }
        editor.putLong("updated_at_ms", System.currentTimeMillis()).apply()
    }

    private fun resolveSelection(slot: String, records: List<RecordDraft>): RecordDraft? {
        val id = preferences.getString("${slot}_selected_id", "").orEmpty()
        if (id.isBlank()) return null
        return records.firstOrNull { it.id == id && it.status == "saved" }.also {
            if (it == null) saveSelection(slot, null)
        }
    }

    private fun recordText(record: RecordDraft?): String = record?.let {
        (it.photoComment.ifBlank { it.text }.ifBlank { it.aiDescription }).trim().take(70)
    }.orEmpty()
}

fun isSafeForWidget(event: GrowthEvent): Boolean {
    if (event.sensitivity != "low" || event.people.isNotEmpty()) return false
    val text = event.fact
    val sensitiveTerms = listOf("自杀", "自伤", "伤害", "家暴", "疾病", "病情", "药物", "身份证", "密码", "住址", "电话")
    if (sensitiveTerms.any(text::contains)) return false
    if (Regex("https?://|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+|\\d{4,}").containsMatchIn(text)) return false
    return text.isNotBlank()
}

private fun eventLocalDay(value: String): LocalDate? = runCatching {
    LocalDateTime.parse(value.take(19)).atOffset(ZoneOffset.UTC)
        .atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
}.getOrElse { runCatching { LocalDate.parse(value.take(10)) }.getOrNull() }
