package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.data.remote.ReviewSection
import com.testconnection.confidence_agent.data.remote.ReviewSummary
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.SectionHeading
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun WeeklyReportScreen(
    review: ReviewSummary?,
    dailyReviews: List<DailyReviewEntry>,
    events: List<GrowthEvent>,
    onBack: () -> Unit,
    onShare: () -> Unit,
) {
    BackHandler(onBack = onBack)
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("‹ 本周报告", modifier = Modifier.noRippleClickable(onClick = onBack),
                style = MaterialTheme.typography.displaySmall)
            Text(
                review?.let { "${it.rangeStart} — ${it.rangeEnd}" }.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            WarmCard {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("把这一周整理成一条故事", style = MaterialTheme.typography.headlineSmall)
                        Text("不追求面面俱到，只看困难、尝试和真正发生的变化。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DuckArt(R.drawable.duck_weekly_report, "整理本周故事的小鸭", Modifier.size(142.dp))
                }
            }
        }

        item { WeeklyMoodCard(dailyReviews, events) }

        review?.takeIf { it.sourceEventIds.isNotEmpty() }?.let { summary ->
            val sections = if (summary.sections.isNotEmpty()) summary.sections else fallbackSections(summary)
            item {
                WarmCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeading("这一周的主线", "由真实聊天和生活记录整理")
                        sections.forEachIndexed { index, section ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(
                                        if (index % 2 == 0) SagePale else MaterialTheme.colorScheme.secondaryContainer,
                                        RoundedCornerShape(18.dp),
                                    )
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(section.title, style = MaterialTheme.typography.titleMedium, color = SageDark)
                                Text(reportText(section.content, 120), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            item {
                WarmCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("小鸭想认真夸夸你", style = MaterialTheme.typography.titleLarge)
                        Text(
                            summary.affirmation.ifBlank {
                                summary.closing.ifBlank { "你愿意把这一周留下来，也愿意继续往前看，这本身就很珍贵。" }
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = AppButtonShape,
                ) { Text("一键生成与分享", fontWeight = FontWeight.SemiBold) }
            }
        } ?: item {
            WarmCard { Text("本周还没有足够记录生成报告。", Modifier.padding(20.dp)) }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

internal enum class MoodBand(val label: String, val score: Int) {
    LOW("低落", 1),
    TENSE("忐忑", 2),
    STEADY("平稳", 3),
    BRIGHT("轻松", 4),
}

internal data class DailyMood(val date: LocalDate, val mood: MoodBand?)

@Composable
private fun WeeklyMoodCard(dailyReviews: List<DailyReviewEntry>, events: List<GrowthEvent>) {
    val moods = remember(dailyReviews, events) { analyzeWeeklyMoods(dailyReviews, events) }
    WarmCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionHeading("本周心情", "根据每天留下的记录整理，不代表医学测量")
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 260.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        MoodTrendChart(moods, Modifier.fillMaxWidth())
                        MoodPieChart(moods, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        MoodTrendChart(moods, Modifier.weight(1f))
                        MoodPieChart(moods, Modifier.width(128.dp))
                    }
                }
            }
            Text(
                "没有留下记录的日期会留空；你可以把它当作一周状态的温和提示。",
                style = MaterialTheme.typography.bodySmall,
                color = InkMuted,
            )
        }
    }
}

@Composable
private fun MoodTrendChart(moods: List<DailyMood>, modifier: Modifier = Modifier) {
    val orderedBands = listOf(MoodBand.BRIGHT, MoodBand.STEADY, MoodBand.TENSE, MoodBand.LOW)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("每日变化", style = MaterialTheme.typography.titleMedium, color = SageDark)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(
                Modifier.width(28.dp).height(112.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                orderedBands.forEach { Text(it.label.take(1), style = MaterialTheme.typography.labelSmall, color = InkMuted) }
            }
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                    val inset = 6.dp.toPx()
                    val usableHeight = size.height - inset * 2
                    val levelHeight = usableHeight / 3f
                    repeat(4) { level ->
                        val y = inset + level * levelHeight
                        drawLine(
                            color = WarmOutline,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    val xStep = if (moods.size > 1) size.width / (moods.size - 1) else 0f
                    var previous: Offset? = null
                    moods.forEachIndexed { index, day ->
                        val mood = day.mood
                        if (mood == null) {
                            previous = null
                            return@forEachIndexed
                        }
                        val point = Offset(index * xStep, inset + (4 - mood.score) * levelHeight)
                        previous?.let {
                            drawLine(
                                color = SageDark,
                                start = it,
                                end = point,
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                        drawCircle(color = moodColor(mood), radius = 4.5.dp.toPx(), center = point)
                        drawCircle(
                            color = Color.White,
                            radius = 2.dp.toPx(),
                            center = point,
                        )
                        previous = point
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    moods.forEach { day ->
                        Text(weekdayLabel(day.date), style = MaterialTheme.typography.labelSmall, color = InkMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodPieChart(moods: List<DailyMood>, modifier: Modifier = Modifier) {
    val recorded = moods.mapNotNull { it.mood }
    val counts = MoodBand.entries.associateWith { band -> recorded.count { it == band } }
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("心情分布", style = MaterialTheme.typography.titleMedium, color = SageDark)
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                if (recorded.isEmpty()) {
                    drawCircle(WarmOutline)
                } else {
                    var start = -90f
                    MoodBand.entries.forEach { band ->
                        val sweep = 360f * (counts.getValue(band).toFloat() / recorded.size)
                        if (sweep > 0f) drawArc(moodColor(band), start, sweep, useCenter = true)
                        start += sweep
                    }
                }
            }
        }
        Text("已记录 ${recorded.size} 天", style = MaterialTheme.typography.labelSmall, color = InkMuted)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MoodBand.entries.reversed().forEach { band ->
                val count = counts.getValue(band)
                if (count > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(7.dp).background(moodColor(band), CircleShape))
                        Text("${band.label} $count", style = MaterialTheme.typography.labelSmall, color = InkMuted)
                    }
                }
            }
            if (recorded.isEmpty()) Text("暂无记录", style = MaterialTheme.typography.labelSmall, color = InkMuted)
        }
    }
}

internal fun analyzeWeeklyMoods(
    dailyReviews: List<DailyReviewEntry>,
    events: List<GrowthEvent>,
): List<DailyMood> {
    val eventsByDate = events.mapNotNull { event -> eventLocalDate(event.createdAt)?.let { it to event } }
        .groupBy({ it.first }, { it.second })
    return dailyReviews.sortedBy { it.date }.map { day ->
        val dayEvents = eventsByDate[day.date].orEmpty()
        val review = day.review
        val hasEvidence = dayEvents.isNotEmpty() || review?.sourceEventIds?.isNotEmpty() == true
        val explicitFeelings = dayEvents.mapNotNull { it.feeling }.joinToString("；")
        val text = buildString {
            dayEvents.forEach { append(it.fact).append('；') }
            review?.sections?.forEach { append(it.content).append('；') }
            review?.moments?.forEach { append(it.title).append('；') }
            append(review?.story.orEmpty())
        }
        DailyMood(day.date, if (hasEvidence) classifyMood(explicitFeelings, text) else null)
    }
}

internal fun classifyMood(explicitFeelings: String, text: String): MoodBand {
    val explicit = explicitFeelings.trim()
    if (explicit.containsAny("绝望", "崩溃", "低落", "难过", "沮丧")) return MoodBand.LOW
    if (explicit.containsAny("紧张", "忐忑", "焦虑", "压力", "担心", "害怕", "不自信")) return MoodBand.TENSE
    if (explicit.containsAny("开心", "轻松", "平静", "有力量", "期待")) return MoodBand.BRIGHT

    var score = 3
    if (text.containsAny("绝望", "崩溃", "很难过", "低落", "沮丧", "想放弃", "错了很多")) score -= 2
    if (text.containsAny("紧张", "忐忑", "焦虑", "压力", "担心", "害怕", "怀疑", "受挫")) score -= 1
    if (text.containsAny("轻松", "平静", "开心", "更敢", "愿意", "帮助", "支持", "陪伴", "调整", "完成", "弄清楚", "看清", "休息", "运动", "户外")) score += 1
    return when (score.coerceIn(1, 4)) {
        1 -> MoodBand.LOW
        2 -> MoodBand.TENSE
        3 -> MoodBand.STEADY
        else -> MoodBand.BRIGHT
    }
}

private fun String.containsAny(vararg words: String): Boolean = words.any(::contains)

private fun eventLocalDate(value: String): LocalDate? =
    runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }
        .recoverCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }
        .recoverCatching { LocalDateTime.parse(value).atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }
        .recoverCatching { LocalDate.parse(value.take(10)) }
        .getOrNull()

private fun weekdayLabel(date: LocalDate): String = listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]

private fun moodColor(mood: MoodBand): Color = when (mood) {
    MoodBand.LOW -> Color(0xFF7E72A8)
    MoodBand.TENSE -> Color(0xFFE0A83E)
    MoodBand.STEADY -> Color(0xFF83A99A)
    MoodBand.BRIGHT -> Color(0xFF76A979)
}

private fun fallbackSections(review: ReviewSummary): List<ReviewSection> = listOf(
    ReviewSection("completed", "这周做了什么", review.moments.take(3).joinToString("；") { it.title }),
    ReviewSection("difficulty", "遇到的困难", review.pauseOrRestart),
    ReviewSection("process", "尝试和解决过程", review.ownEffort),
    ReviewSection("change", "发生的变化", review.moments.lastOrNull()?.title.orEmpty()),
    ReviewSection("unfinished", "还在继续", review.nextStep),
).filter { it.content.isNotBlank() && !it.content.startsWith("还没有") }

private fun reportText(value: String, limit: Int): String =
    value.trim().replace(Regex("\\s+"), " ").let { if (it.length <= limit) it else it.take(limit) + "…" }
