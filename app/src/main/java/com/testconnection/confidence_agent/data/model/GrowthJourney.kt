package com.testconnection.confidence_agent.data.model

import com.testconnection.confidence_agent.data.remote.GrowthEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

data class GrowthKeyword(val label: String, val eventIds: List<Long>)
data class GrowthMilestone(val stage: Int, val date: String, val eventIds: List<Long>)
data class GrowthJourney(
    val stage: Int = 0,
    val keywords: List<GrowthKeyword> = emptyList(),
    val milestones: List<GrowthMilestone> = emptyList(),
)

val growthStageNames = listOf("起步", "尝试", "成长")

/** All labels describe evidenced behaviours, never a personality score or an exam result. */
object GrowthJourneyAnalyzer {
    fun day(event: GrowthEvent): LocalDate? = runCatching {
        val timestamp = runCatching { OffsetDateTime.parse(event.createdAt) }.getOrElse {
            LocalDateTime.parse(event.createdAt).atOffset(ZoneOffset.UTC)
        }
        timestamp.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    }.getOrNull()

    private val futureOrNegative = Regex("^(?:(?:我|今天|昨天|这次|本周|最近|现在|仍然|还)\\s*)*(想|打算|准备|计划|希望|建议|如果|还没|没有|没能|没去|不想|不敢|未|明天|后天|下周|下个月|以后|将要|将会)")
    private fun stated(value: String?): String = value.orEmpty().trim().takeUnless {
        it.isBlank() || futureOrNegative.containsMatchIn(it) || it in listOf("unknown", "none", "null")
    }.orEmpty()

    private fun has(text: String, words: List<String>) = words.any(text::contains)

    private fun effort(event: GrowthEvent): String {
        val structured = stated(event.ownEffort).ifBlank { stated(event.attempt) }
        if (structured.isNotBlank()) return structured
        // Raw records qualify only when they explicitly describe a completed action.
        if (event.sourceRecordId == null || stated(event.fact).isBlank()) return ""
        return event.fact.takeIf { has(it, listOf("完成了", "整理了", "写下了", "试着", "散步了", "跑了", "请教了", "联系了", "调整了", "休息了")) }.orEmpty()
    }

    fun analyze(events: List<GrowthEvent>, today: LocalDate = LocalDate.now()): GrowthJourney {
        val trusted = events.filter { it.sourceType != "demo" && it.sensitivity != "high" && (it.confidence ?: 0.0) >= 0.75 }
            .distinctBy { it.id }.filter { day(it)?.let { date -> !date.isAfter(today) } == true }
            .sortedWith(compareBy<GrowthEvent> { day(it) }.thenBy { it.id })
        val efforts = trusted.associate { it.id to effort(it) }
        val received = trusted.associate { it.id to stated(it.supportReceived).takeUnless { text ->
            has(text, listOf("没有", "没帮", "不愿", "拒绝", "未联系"))
        }.orEmpty() }
        val categories = linkedMapOf(
            "愿意尝试" to trusted.filter { efforts[it.id].orEmpty().isNotBlank() },
            "主动求助" to trusted.filter { has(efforts[it.id].orEmpty(), listOf("求助", "请教", "提问", "联系", "沟通")) },
            "调整方法" to trusted.filter { has(efforts[it.id].orEmpty(), listOf("调整", "订正", "复盘", "改进", "错误原因", "错题原因")) },
            "照顾自己" to trusted.filter { has(efforts[it.id].orEmpty(), listOf("运动", "散步", "慢跑", "健身", "休息", "睡觉", "户外", "停止熬夜")) },
            "获得支持" to trusted.filter { received[it.id].orEmpty().isNotBlank() },
        )
        val keywords = categories.filterValues { it.isNotEmpty() }.entries
            .sortedByDescending { day(it.value.last()) }.take(4)
            .map { GrowthKeyword(it.key, it.value.map(GrowthEvent::id)) }
        val actionDays = categories.getValue("愿意尝试").distinctBy { day(it) }
        val broader = trusted.firstOrNull { event -> categories.filterKeys { it != "愿意尝试" }.values.any { rows -> rows.any { it.id == event.id } } }
        val milestones = mutableListOf<GrowthMilestone>()
        trusted.firstOrNull()?.let { milestones += GrowthMilestone(0, day(it).toString(), listOf(it.id)) }
        if (actionDays.size >= 2) {
            milestones += GrowthMilestone(1, day(actionDays[1]).toString(), actionDays.take(2).map(GrowthEvent::id))
        }
        if (actionDays.size >= 4 && broader != null) {
            milestones += GrowthMilestone(2, maxOf(requireNotNull(day(actionDays[3])), requireNotNull(day(broader))).toString(),
                (actionDays.take(4).map(GrowthEvent::id) + broader.id).distinct())
        }
        return GrowthJourney(milestones.lastOrNull()?.stage ?: 0, keywords, milestones)
    }
}
