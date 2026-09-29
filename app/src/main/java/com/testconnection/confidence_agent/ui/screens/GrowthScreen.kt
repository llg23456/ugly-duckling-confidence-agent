package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.remote.ReviewMoment
import com.testconnection.confidence_agent.data.remote.ReviewSummary
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.SectionHeading
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.Terracotta
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val chineseDate = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

@Composable
fun GrowthScreen(
    contentPadding: PaddingValues,
    onOpenSource: (Long) -> Unit = {},
    onOpenFeedback: () -> Unit = {},
    growthViewModel: GrowthViewModel = viewModel(),
    onOpenWeeklyReport: () -> Unit = {},
    onAddRecord: (LocalDate) -> Unit = {},
    onEditRecord: (RecordDraft) -> Unit = {},
) {
    val state by growthViewModel.state.collectAsState()
    var selectedRecord by remember { mutableStateOf<RecordDraft?>(null) }
    var missingRecordMoment by remember { mutableStateOf<ReviewMoment?>(null) }
    LaunchedEffect(Unit) { growthViewModel.refresh() }

    selectedRecord?.let { record ->
        RecordDetailDialog(
            record,
            onDismiss = { selectedRecord = null },
            onEdit = {
                selectedRecord = null
                onEditRecord(record)
            },
        )
    }
    missingRecordMoment?.let { moment ->
        AlertDialog(
            onDismissRequest = { missingRecordMoment = null },
            title = { Text("记录来源") },
            text = { Text("${moment.title}\n\n这条记录目前只有文字，你可以直接编辑内容，或为原日期补上照片和语音。") },
            dismissButton = {
                TextButton(onClick = {
                    val recordId = moment.sourceRecordId ?: return@TextButton
                    missingRecordMoment = null
                    growthViewModel.recordForEdit(recordId) { draft ->
                        if (draft != null) onEditRecord(draft)
                    }
                }) { Text("编辑") }
            },
            confirmButton = { TextButton(onClick = { missingRecordMoment = null }) { Text("收好") } },
        )
    }

    fun openMoment(moment: ReviewMoment) {
        when {
            moment.sourceRecordId != null -> {
                selectedRecord = growthViewModel.localRecordFor(moment.sourceRecordId)
                if (selectedRecord == null) missingRecordMoment = moment
            }
            moment.sourceFeedbackId != null -> onOpenFeedback()
            moment.sourceMessageId != null -> onOpenSource(moment.sourceMessageId)
        }
    }

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { GrowthHeader() }
        item { PeriodTabs(state.period, state.loading, growthViewModel::selectPeriod) }
        item {
            RangeNavigator(
                title = rangeTitle(state),
                loading = state.loading,
                onPrevious = { growthViewModel.shiftRange(-1) },
                onNext = { growthViewModel.shiftRange(1) },
            )
        }

        if (state.loading) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = SageDark)
            }
        }
        if (state.refreshing) item {
            Text(
                "正在后台更新，当前先显示上次内容…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { message -> item {
            WarmCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = growthViewModel::refresh) { Text("重试同步与生成") }
                }
            }
        } }

        when (state.period) {
            "日" -> {
                item { DailyOverview(state.anchorDate, state.review) }
                item { DaySources(state.anchorDate, state.review, ::openMoment, onAddRecord) }
            }
            "周" -> {
                item { WeekDays(state.dailyReviews, growthViewModel::openDay) }
                item { GrowthTimeline(state.review?.moments.orEmpty(), ::openMoment) }
                item {
                    OutlinedButton(
                        onClick = onOpenWeeklyReport,
                        enabled = state.review?.sourceEventIds?.isNotEmpty() == true,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = AppButtonShape,
                    ) { Text("本周报告", fontWeight = FontWeight.SemiBold) }
                }
            }
            else -> {
                item { MonthlyOverview(state.review) }
                item { MonthWeeks(state.weeklyReviews, growthViewModel::openWeek) }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun GrowthHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("成长回望", style = MaterialTheme.typography.displaySmall)
            Text("把真实生活慢慢连成自己的故事", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DuckArt(R.drawable.duck_step, "正在迈出一步的小鸭", Modifier.size(110.dp))
    }
}

@Composable
private fun PeriodTabs(period: String, loading: Boolean, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(SagePale, RoundedCornerShape(24.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("日", "周", "月").forEach { label ->
            val selected = label == period
            Button(
                onClick = { onSelect(label) }, enabled = !loading, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(19.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) SageDark else SagePale,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else SageDark,
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp),
            ) { Text(label) }
        }
    }
}

@Composable
private fun RangeNavigator(title: String, loading: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            TextButton(onClick = onPrevious, enabled = !loading) { Text("‹ 前一段") }
        }
        Box(Modifier.weight(1.35f), contentAlignment = Alignment.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = onNext, enabled = !loading) { Text("后一段 ›") }
        }
    }
}

@Composable
private fun DailyOverview(date: LocalDate, review: ReviewSummary?) {
    WarmCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading(if (date == LocalDate.now().minusDays(1)) "昨天的生活回望" else date.format(chineseDate),
                "聊天和生活记录汇成的一天")
            if (review == null || review.sourceEventIds.isEmpty()) {
                Text("这一天还没有留下记录。空白也没有关系，生活不需要每天都交作业。",
                    style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ReviewSection("发生了什么", shortSummary(review))
                }
                DuckArt(R.drawable.duck_writing, "翻看一天记录的小鸭", Modifier.size(86.dp))
            }
            review.sections.filterNot { it.key == "happened" || it.key == "response" }.forEach {
                ReviewSection(it.title, it.content)
            }
            review.sections.firstOrNull { it.key == "response" }?.let {
                ReviewSection(it.title, it.content)
            }
            Text(review.closing.ifBlank { "愿意留下这些，就已经是在认真看见自己。" },
                style = MaterialTheme.typography.titleMedium, color = SageDark)
        }
    }
}

@Composable
private fun DaySources(
    date: LocalDate,
    review: ReviewSummary?,
    onOpen: (ReviewMoment) -> Unit,
    onAddRecord: (LocalDate) -> Unit,
) {
    WarmCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading("这一天留下的记录", "点击可以回到文字、照片、语音或聊天来源")
            val moments = review?.moments.orEmpty()
            if (moments.isEmpty()) Text("还没有可以查看的来源。")
            moments.forEach { moment ->
                Surface(onClick = { onOpen(moment) }, shape = RoundedCornerShape(18.dp), color = SagePale) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(moment.title, style = MaterialTheme.typography.bodyLarge)
                        Text(sourceLabel(moment), style = MaterialTheme.typography.bodyMedium, color = SageDark)
                    }
                }
            }
            OutlinedButton(
                onClick = { onAddRecord(date) },
                modifier = Modifier.fillMaxWidth(),
                shape = AppButtonShape,
            ) { Text("＋ 为 ${date.monthValue}月${date.dayOfMonth}日补充文字、照片或语音") }
        }
    }
}

@Composable
private fun WeekDays(days: List<DailyReviewEntry>, onOpenDay: (LocalDate) -> Unit) {
    WarmCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SectionHeading("周一到周日", "先看每天的一句话，点击查看当天详情")
            days.forEach { item ->
                val summary = item.review?.let(::dailyCardSummary)
                    ?: if (item.date.isAfter(LocalDate.now())) "还没到这一天" else "这天没有留下记录"
                Surface(onClick = { onOpenDay(item.date) }, enabled = !item.date.isAfter(LocalDate.now()),
                    shape = RoundedCornerShape(18.dp), color = SagePale) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("${weekDayName(item.date)} · ${item.date.monthValue}/${item.date.dayOfMonth}",
                                style = MaterialTheme.typography.titleMedium)
                            Text(summary, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge, color = SageDark)
                    }
                }
            }
        }
    }
}

@Composable
private fun GrowthTimeline(moments: List<ReviewMoment>, onOpen: (ReviewMoment) -> Unit) {
    WarmCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("这周的成长线", "只串联有代表性的真实节点")
            if (moments.isEmpty()) Text("这一周还没有形成成长节点。")
            moments.take(7).forEachIndexed { index, moment ->
                Row(verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(11.dp).background(if (index % 2 == 0) SageDark else Terracotta, CircleShape))
                        if (index < minOf(moments.size, 7) - 1) Box(Modifier.size(2.dp, 42.dp).background(SagePale))
                    }
                    Surface(onClick = { onOpen(moment) }, color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Column {
                            Text(moment.date, style = MaterialTheme.typography.bodyMedium, color = SageDark)
                            Text(moment.title, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyOverview(review: ReviewSummary?) {
    WarmCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeading("这个月，留下了什么", "用几句话看看长时间里的变化")
            if (review == null || review.sourceEventIds.isEmpty()) {
                Text("这个月还没有足够的记录。")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("这个月留下了 ${review.sourceEventIds.size} 个片段",
                        style = MaterialTheme.typography.headlineSmall)
                    Text("不用重读所有记录，先看看最开始和现在。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DuckArt(R.drawable.duck_monthly_reflection, "翻看一个月记忆的小鸭", Modifier.size(118.dp))
            }
            if (review.sections.isNotEmpty()) {
                review.sections.take(4).forEachIndexed { index, section ->
                    HighlightCard(section.title, shortText(section.content, 90),
                        if (index % 2 == 0) SagePale else MaterialTheme.colorScheme.secondaryContainer)
                }
            } else {
                review.moments.firstOrNull()?.let { HighlightCard("月初的你", shortText(it.title), SagePale) }
                review.moments.lastOrNull()?.takeIf { it.eventId != review.moments.firstOrNull()?.eventId }?.let {
                    HighlightCard("现在回头看", shortText(it.title), MaterialTheme.colorScheme.secondaryContainer)
                }
                HighlightCard("接下来的一小步", shortText(review.nextStep), MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun MonthWeeks(weeks: List<WeeklyReviewEntry>, onOpenWeek: (LocalDate) -> Unit) {
    WarmCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading("这个月的每一周", "点击查看完整周报告")
            weeks.forEachIndexed { index, week ->
                Surface(onClick = { onOpenWeek(week.start) }, shape = RoundedCornerShape(18.dp), color = SagePale) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("第 ${index + 1} 周 · ${week.start.monthValue}/${week.start.dayOfMonth}—${week.end.monthValue}/${week.end.dayOfMonth}",
                                style = MaterialTheme.typography.titleMedium)
                            Text(weekCardSummary(week.review),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge, color = SageDark)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewSection(title: String, content: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(content, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HighlightCard(title: String, content: String, background: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier.fillMaxWidth().background(background, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = SageDark)
        Text(content, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun meaningful(value: String, emptyPrefix: String): String =
    value.takeUnless { it.isBlank() || it.startsWith(emptyPrefix) }.orEmpty()

private fun shortSummary(review: ReviewSummary): String {
    val moments = review.moments
    if (moments.isEmpty()) return "这一天留下了 ${review.sourceEventIds.size} 条记录。"
    if (moments.size == 1) return shortText(moments.first().title)
    return "留下了 ${moments.size} 个片段：${shortText(moments.first().title, 34)}；后来，${shortText(moments.last().title, 34)}"
}

private fun dailyCardSummary(review: ReviewSummary): String {
    val happened = review.sections.firstOrNull { it.key == "happened" }?.content
    if (!happened.isNullOrBlank()) return shortText(withoutLeadingDate(happened), 74)
    val titles = review.moments.map { withoutLeadingDate(it.title.trim()) }.filter { it.isNotBlank() }.distinct()
    return when {
        titles.isEmpty() -> "这天没有留下记录"
        titles.size == 1 -> shortText(titles.first(), 74)
        else -> "${shortText(titles.first(), 34)}；${shortText(titles.last(), 34)}"
    }
}

private fun weekCardSummary(review: ReviewSummary?): String {
    if (review == null || review.sourceEventIds.isEmpty()) return "这一周没有留下记录"
    val completed = review.sections.firstOrNull { it.key == "completed" }?.content
    if (!completed.isNullOrBlank()) return shortText(withoutLeadingDate(completed), 84)
    return review.moments.firstOrNull()?.title?.let { shortText(withoutLeadingDate(it), 84) }
        ?: "这一周没有留下记录"
}

private fun withoutLeadingDate(value: String): String =
    value.replace(Regex("^\\s*\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}[，,：:\\s]*"), "")

private fun shortText(value: String, limit: Int = 58): String =
    value.trim().replace(Regex("\\s+"), " ").let { if (it.length <= limit) it else it.take(limit) + "…" }

private fun sourceLabel(moment: ReviewMoment): String = when {
    moment.sourceRecordId != null -> "来自生活记录 · 点击查看"
    moment.sourceFeedbackId != null -> "来自一次真实反馈 · 点击查看"
    moment.sourceMessageId != null -> "来自聊天 · 点击查看"
    else -> "已记录"
}

private fun weekDayName(date: LocalDate): String = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]

private fun rangeTitle(state: GrowthUiState): String = when (state.period) {
    "日" -> state.anchorDate.format(chineseDate)
    "周" -> state.review?.let { "${it.rangeStart} — ${it.rangeEnd}" } ?: "一周"
    else -> "${state.anchorDate.year}年${state.anchorDate.monthValue}月"
}
