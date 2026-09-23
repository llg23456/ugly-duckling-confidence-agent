package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.WarmOutline

@Composable
fun RecordScreen(contentPadding: PaddingValues) {
    var note by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("今日留一笔", style = MaterialTheme.typography.displaySmall)
                    Text(
                        "不必完整，留下一点就好。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = InkMuted,
                    )
                }
                DuckArt(R.drawable.duck_writing, "正在记录的小鸭", Modifier.size(130.dp))
            }
        }

        item {
            WarmCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("9月23日  星期三", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        placeholder = { Text("今天发生了什么？此刻的你是什么感受？") },
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SageDark,
                            unfocusedBorderColor = WarmOutline,
                        ),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecordModeButton("▤", "写一句", Modifier.weight(1f))
                        RecordModeButton("♬", "说一句", Modifier.weight(1f))
                        RecordModeButton("▧", "拍一张", Modifier.weight(1f))
                    }
                    Text(
                        "▣ 仅自己可见，可随时修改或删除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkMuted,
                    )
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = AppButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                    ) { Text("保存这一笔", fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = {}, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("存为草稿", color = SageDark)
                    }
                }
            }
        }

        item {
            WarmCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeading("最近留下的")
                    FakeConfidenceRepository.recentRecords.forEach { record ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(0.28f)) {
                                Text(record.date, style = MaterialTheme.typography.titleMedium)
                                Text(record.weekday, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                            }
                            Text(record.content, modifier = Modifier.weight(0.64f), style = MaterialTheme.typography.bodyLarge)
                            Text("›", modifier = Modifier.weight(0.08f), style = MaterialTheme.typography.titleLarge, color = InkMuted)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun RecordModeButton(symbol: String, label: String, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = {},
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(symbol, style = MaterialTheme.typography.titleLarge, color = SageDark)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
