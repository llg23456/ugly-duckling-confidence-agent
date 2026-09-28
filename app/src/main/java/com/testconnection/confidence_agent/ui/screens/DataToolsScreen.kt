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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.BuildConfig
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.SectionHeading
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark

@Composable
fun DataToolsScreen(
    state: GrowthUiState,
    onCreateDemoData: () -> Unit,
    onClearDemoData: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Text("数据导出", style = MaterialTheme.typography.displaySmall)
            }
        }
        item {
            WarmCard {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        SectionHeading("自己的记录")
                        Text("正式的数据打包与导出会在数据闭环阶段接入。你的照片和原声目前仍保存在手机应用私有目录。",
                            style = MaterialTheme.typography.bodyLarge, color = InkMuted)
                    }
                    DuckArt(R.drawable.duck_writing, "整理记录的小鸭", Modifier.size(92.dp))
                }
            }
        }
        if (BuildConfig.DEBUG) item {
            WarmCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    SectionHeading("测试数据准备", "藏在数据管理里，不会出现在成长页和正式演示主线")
                    Text("主题：从害怕课堂展示，到完成一次小组汇报。生成截至昨天的四周文字记录。",
                        style = MaterialTheme.typography.bodyLarge)
                    state.demoNotice?.let { Text(it, color = SageDark) }
                    if (state.loading) CircularProgressIndicator(Modifier.size(24.dp), color = SageDark)
                    Button(
                        onClick = onCreateDemoData,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = AppButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                    ) { Text("生成四周测试数据") }
                    OutlinedButton(
                        onClick = onClearDemoData,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppButtonShape,
                    ) { Text("清除测试数据") }
                    Text("清除只影响这批测试文字，不会删除你自己添加的照片、语音、聊天和记录。",
                        style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
