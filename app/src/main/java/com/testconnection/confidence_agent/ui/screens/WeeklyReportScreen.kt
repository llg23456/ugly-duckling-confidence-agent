package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.remote.ReviewSection
import com.testconnection.confidence_agent.data.remote.ReviewSummary
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.SectionHeading
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale

@Composable
fun WeeklyReportScreen(
    review: ReviewSummary?,
    dailyReviews: List<DailyReviewEntry>,
    onBack: () -> Unit,
    onShare: () -> Unit,
) {
    BackHandler(onBack = onBack)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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

        item {
            WarmCard {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading("七天的一句话", "每天只保留一条最值得回看的内容")
                    dailyReviews.filter { it.review?.sourceEventIds?.isNotEmpty() == true }.forEach { day ->
                        val summary = day.review?.sections?.firstOrNull { it.key == "happened" }?.content
                            ?: day.review?.moments?.firstOrNull()?.title
                            ?: return@forEach
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${day.date.monthValue}/${day.date.dayOfMonth}", color = SageDark,
                                fontWeight = FontWeight.SemiBold)
                            Text(reportText(summary, 68), modifier = Modifier.weight(1f))
                        }
                    }
                    if (dailyReviews.none { it.review?.sourceEventIds?.isNotEmpty() == true }) {
                        Text("这一周还没有形成每日摘要。")
                    }
                }
            }
        }

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

private fun fallbackSections(review: ReviewSummary): List<ReviewSection> = listOf(
    ReviewSection("completed", "这周做了什么", review.moments.take(3).joinToString("；") { it.title }),
    ReviewSection("difficulty", "遇到的困难", review.pauseOrRestart),
    ReviewSection("process", "尝试和解决过程", review.ownEffort),
    ReviewSection("change", "发生的变化", review.moments.lastOrNull()?.title.orEmpty()),
    ReviewSection("unfinished", "还在继续", review.nextStep),
).filter { it.content.isNotBlank() && !it.content.startsWith("还没有") }

private fun reportText(value: String, limit: Int): String =
    value.trim().replace(Regex("\\s+"), " ").let { if (it.length <= limit) it else it.take(limit) + "…" }
