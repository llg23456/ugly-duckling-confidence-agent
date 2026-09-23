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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.repository.FakeConfidenceRepository
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.SectionHeading
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.Terracotta
import com.testconnection.confidence_agent.ui.theme.TerracottaPale

@Composable
fun GrowthScreen(contentPadding: PaddingValues) {
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
                    val selected = label == "月"
                    Button(
                        onClick = {},
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
                    SectionHeading("九月的成长故事", "每一条结论都能回到原始记录")
                    FakeConfidenceRepository.growthMoments.forEachIndexed { index, moment ->
                        Row(verticalAlignment = Alignment.Top) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier.size(12.dp).background(
                                        if (index % 2 == 0) SageDark else Terracotta,
                                        CircleShape,
                                    ),
                                )
                                if (index < FakeConfidenceRepository.growthMoments.lastIndex) {
                                    Box(Modifier.size(width = 2.dp, height = 46.dp).background(SagePale))
                                }
                            }
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(moment.date, style = MaterialTheme.typography.titleMedium)
                                Text(moment.title, style = MaterialTheme.typography.bodyLarge)
                            }
                            Surface(shape = AppButtonShape, color = SagePale) {
                                Text(
                                    moment.source,
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
                    SectionHeading("这个月，我和大家一起", "成长从来不是一个人的事")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SupportSummary(
                            title = "我的一步",
                            main = "主动开口求助",
                            detail = "我尝试表达自己的困惑。",
                            modifier = Modifier.weight(1f),
                            background = SagePale,
                        )
                        SupportSummary(
                            title = "收到的帮助",
                            main = "老师解答、室友陪练",
                            detail = "有人耐心回应并陪我练习。",
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
                            "你没有独自完成这一切，\n也没有少付出一分努力。",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = AppButtonShape,
            ) { Text("生成成长小片", fontWeight = FontWeight.SemiBold) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

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
