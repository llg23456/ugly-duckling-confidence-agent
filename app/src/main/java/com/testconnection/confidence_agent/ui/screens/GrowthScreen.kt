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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.SectionHeading
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.Terracotta
import com.testconnection.confidence_agent.ui.theme.TerracottaPale
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun GrowthScreen(contentPadding: PaddingValues, onOpenSource: (Long) -> Unit = {}, onOpenFeedback: () -> Unit = {}, growthViewModel: GrowthViewModel = viewModel()) {
    val state by growthViewModel.state.collectAsState()
    var selectedRecord by remember { mutableStateOf<RecordDraft?>(null) }
    var missingRecordText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { growthViewModel.refresh() }
    selectedRecord?.let { RecordDetailDialog(it, onDismiss = { selectedRecord = null }) }
    missingRecordText?.let { content -> AlertDialog(
        onDismissRequest = { missingRecordText = null },
        title = { Text("记录来源") },
        text = { Text("$content\n\n原始照片或录音不在本机时，只能查看保存的说明。") },
        confirmButton = { TextButton(onClick = { missingRecordText = null }) { Text("收好") } },
    ) }
    fun openMoment(messageId: Long?, feedbackId: Long?, recordId: Long?, title: String) {
        if (recordId != null) {
            selectedRecord = growthViewModel.localRecordFor(recordId)
            if (selectedRecord == null) missingRecordText = title
        } else if (feedbackId != null) onOpenFeedback()
        else messageId?.let(onOpenSource)
    }
    val today = LocalDate.now()
    val visibleEvents = state.events.filter { event ->
        val date = eventLocalDay(event.createdAt) ?: return@filter false
        when (state.period) {
            "日" -> date == today
            "周" -> !date.isBefore(today.minusDays(6)) && !date.isAfter(today)
            else -> YearMonth.from(date) == YearMonth.from(today)
        }
    }
    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("成长回望", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "努力不是一个人的独角戏",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DuckArt(R.drawable.duck_step, "正在迈出一步的小鸭", Modifier.size(118.dp))
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().background(SagePale, RoundedCornerShape(24.dp)).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("日", "周", "月").forEach { label ->
                    val selected = label == state.period
                    Button(
                        onClick = { growthViewModel.selectPeriod(label) },
                        enabled = !state.loading,
                        modifier = Modifier.weight(1f),
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

        item {
            WarmCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading(state.review?.title ?: "这一段的回望", "由已记录的经历组成，可查看来源")
                    if (state.loading) CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { growthViewModel.refresh() }) { Text("重试同步与生成") }
                    }
                    state.review?.let { review ->
                        Text(if (review.story.isBlank()) "这段时间还没有成长事件，慢慢来就好。" else review.story,
                            style = MaterialTheme.typography.bodyLarge)
                        if (review.moments.isNotEmpty()) {
                            Text("停顿与重新开始：${review.pauseOrRestart}", style = MaterialTheme.typography.bodyMedium)
                            Text("下一步：${review.nextStep}", style = MaterialTheme.typography.bodyMedium)
                            review.moments.forEach { moment ->
                                TextButton(onClick = { openMoment(moment.sourceMessageId, moment.sourceFeedbackId, moment.sourceRecordId, moment.title) }) {
                                    Text("${moment.date} · ${moment.title}  ›")
                                }
                            }
                            Text("已保存 ${review.sourceEventIds.size} 条来源", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (state.pastDaily != null && state.period == "日") item {
            WarmCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("上一次日回望 · ${state.pastDaily?.rangeStart.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                    Text(state.pastDaily?.story.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        item {
            WarmCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeading("这段时间的成长事件", "只展示已经记录的经历")
                    if (visibleEvents.isEmpty()) Text("这段时间还没有成长事件，慢慢来就好。", style = MaterialTheme.typography.bodyLarge)
                    visibleEvents.forEachIndexed { index, moment ->
                        Row(verticalAlignment = Alignment.Top) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier.size(12.dp).background(
                                        if (index % 2 == 0) SageDark else Terracotta,
                                        CircleShape,
                                    ),
                                )
                                if (index < visibleEvents.lastIndex) {
                                    Box(Modifier.size(width = 2.dp, height = 46.dp).background(SagePale))
                                }
                            }
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(eventLocalDay(moment.createdAt)?.toString() ?: moment.createdAt.take(10), style = MaterialTheme.typography.titleMedium)
                                Text(moment.fact, style = MaterialTheme.typography.bodyLarge)
                            }
                            Surface(
                                onClick = {
                                    openMoment(moment.sourceId, moment.sourceFeedbackId, moment.sourceRecordId, moment.fact)
                                },
                                shape = AppButtonShape,
                                color = SagePale,
                            ) {
                                Text(
                                    if (moment.sourceRecordId != null) "来自记录" else if (moment.sourceFeedbackId != null) "来自反馈" else if (moment.sourceId != null) "查看来源" else "已记录",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SageDark,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            WarmCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeading("我和大家一起", "分别记下自己的尝试与收到的帮助")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SupportSummary(
                            title = "我的一步",
                            main = state.review?.ownEffort ?: "还没有记录",
                            detail = "只记录你明确做过的事。",
                            modifier = Modifier.weight(1f),
                            background = SagePale,
                        )
                        SupportSummary(
                            title = "收到的帮助",
                            main = state.review?.supportReceived ?: "还没有记录",
                            detail = "只记录确实得到的帮助。",
                            modifier = Modifier.weight(1f),
                            background = TerracottaPale,
                        )
                    }
                }
            }
        }

        item {
            WarmCard {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    DuckArt(R.drawable.duck_welcome, "给予肯定的小鸭", Modifier.size(96.dp))
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            "愿意试一小步、或向人开口，\n都值得被认真看见。",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = AppButtonShape,
            ) { Text("生成成长小片", fontWeight = FontWeight.SemiBold) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

private fun eventLocalDay(createdAt: String): LocalDate? = runCatching {
    LocalDateTime.parse(createdAt.take(19)).atOffset(ZoneOffset.UTC)
        .atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
}.getOrElse { runCatching { LocalDate.parse(createdAt.take(10)) }.getOrNull() }

@Composable
private fun SupportSummary(
    title: String,
    main: String,
    detail: String,
    background: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(background, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(main, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
