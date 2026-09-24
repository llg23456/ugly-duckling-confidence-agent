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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.data.remote.SupportApiClient
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
fun GrowthScreen(contentPadding: PaddingValues, onOpenSource: (Long) -> Unit = {}, onOpenFeedback: () -> Unit = {}) {
    val context = LocalContext.current
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    val api = remember { SupportApiClient() }
    var events by remember { mutableStateOf<List<GrowthEvent>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var period by remember { mutableStateOf("月") }
    LaunchedEffect(Unit) {
        runCatching { api.events(deviceId) }
            .onSuccess { events = it; loadError = null }
            .onFailure { loadError = "成长事件暂时无法加载。" }
    }
    val today = LocalDate.now()
    val visibleEvents = events.filter { event ->
        val date = eventLocalDay(event.createdAt) ?: return@filter false
        when (period) {
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
                    val selected = label == period
                    Button(
                        onClick = { period = label },
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
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeading("这段时间的成长事件", "只展示已经记录的经历")
                    loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                                    moment.sourceId?.let(onOpenSource)
                                    if (moment.sourceId == null && moment.sourceFeedbackId != null) onOpenFeedback()
                                },
                                shape = AppButtonShape,
                                color = SagePale,
                            ) {
                                Text(
                                    if (moment.sourceId != null) "查看来源" else if (moment.sourceFeedbackId != null) "来自反馈" else "已记录",
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
                            main = visibleEvents.mapNotNull { it.ownEffort }.firstOrNull() ?: "还没有记录",
                            detail = "只记录你明确做过的事。",
                            modifier = Modifier.weight(1f),
                            background = SagePale,
                        )
                        SupportSummary(
                            title = "收到的帮助",
                            main = visibleEvents.mapNotNull { it.supportReceived }.firstOrNull() ?: "还没有记录",
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
