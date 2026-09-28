package com.testconnection.confidence_agent.widget

import android.content.Context
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

data class WidgetSnapshot(
    val allowed: Boolean,
    val todayText: String,
    val monthCount: Int,
    val updatedAtMs: Long,
)

class WidgetPrivacyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("duck_widget_privacy", Context.MODE_PRIVATE)

    fun isAllowed(): Boolean = preferences.getBoolean("allow_public_growth", false)

    fun setAllowed(allowed: Boolean) {
        preferences.edit().putBoolean("allow_public_growth", allowed).apply()
        if (!allowed) preferences.edit().remove("today_text").remove("month_count").remove("updated_at_ms").apply()
    }

    fun update(events: List<GrowthEvent>) {
        if (!isAllowed()) return
        val today = LocalDate.now()
        val visible = events.filter(::isSafeForWidget)
        val todayEvent = visible.firstOrNull { eventLocalDay(it.createdAt) == today }
        val monthCount = visible.count { eventLocalDay(it.createdAt)?.let(YearMonth::from) == YearMonth.from(today) }
        preferences.edit()
            .putString("today_text", todayEvent?.fact?.take(70).orEmpty())
            .putString("today_date", today.toString())
            .putInt("month_count", monthCount)
            .putString("month_key", YearMonth.from(today).toString())
            .putLong("updated_at_ms", System.currentTimeMillis())
            .apply()
    }

    fun snapshot(): WidgetSnapshot {
        if (!isAllowed()) return WidgetSnapshot(false, "", 0, 0)
        val today = LocalDate.now()
        return WidgetSnapshot(
            allowed = true,
            todayText = preferences.getString("today_text", "").orEmpty().takeIf {
                preferences.getString("today_date", "") == today.toString()
            }.orEmpty(),
            monthCount = preferences.getInt("month_count", 0).takeIf {
                preferences.getString("month_key", "") == YearMonth.from(today).toString()
            } ?: 0,
            updatedAtMs = preferences.getLong("updated_at_ms", 0),
        )
    }
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
