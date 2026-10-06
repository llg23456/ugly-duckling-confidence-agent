package com.testconnection.confidence_agent.ui.screens

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
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
import com.testconnection.confidence_agent.ui.theme.Danger
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.repository.DataManagementRepository
import com.testconnection.confidence_agent.widget.WidgetUpdater
import kotlinx.coroutines.launch

@Composable
fun DataToolsScreen(
    state: GrowthUiState,
    onCreateExamWeekDemoData: () -> Unit,
    onCreateFourWeekDemoData: () -> Unit,
    onClearDemoData: () -> Unit,
    onAllDataDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DataManagementRepository(context.applicationContext) }
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    fun exportData(jsonOnly: Boolean) {
        if (busy) return
        busy = true
        notice = if (jsonOnly) "正在整理 JSON 文档…" else "正在整理聊天、记录、回望和本机媒体…"
        scope.launch {
            runCatching { if (jsonOnly) repository.exportJson(deviceId) else repository.export(deviceId) }
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = if (jsonOnly) "application/json" else "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, if (jsonOnly) "导出 JSON 文档" else "导出完整数据包"))
                    notice = if (jsonOnly) "JSON 文档已生成，请选择保存或分享位置。" else "ZIP 数据包已生成，请选择保存或分享位置。"
                }
                .onFailure { notice = it.message ?: "数据导出失败，请稍后重试。" }
            busy = false
        }
    }

    fun deleteAllData() {
        if (busy) return
        showDeleteConfirmation = false
        busy = true
        notice = "正在删除全部数据…"
        scope.launch {
            runCatching { repository.deleteAll(deviceId) }
                .onSuccess {
                    runCatching { WidgetUpdater.refreshGrowthWidgets(context) }
                    notice = "全部数据已删除。"
                    onAllDataDeleted()
                }
                .onFailure { notice = "删除未完成：${it.message ?: "请确认后端连接后重试"}" }
            busy = false
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除全部数据？") },
            text = { Text("聊天、生活记录、照片与原声、自己的社区分享、记忆、回望、成长小片和画像都会删除，且无法恢复。建议先导出一份。") },
            dismissButton = { TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消") } },
            confirmButton = { TextButton(onClick = ::deleteAllData) { Text("确认全部删除", color = Danger) } },
        )
    }
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
                        Text("JSON 适合查看和展示；ZIP 除同一份 JSON 外，还包含仍保存在本机的照片、原声、社区图片和成长小片。后端暂时离线时也会先导出本机数据。",
                            style = MaterialTheme.typography.bodyLarge, color = InkMuted)
                    }
                    DuckArt(R.drawable.duck_writing, "整理记录的小鸭", Modifier.size(92.dp))
                }
            }
        }
        item {
            WarmCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    notice?.let { Text(it, color = SageDark) }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = SageDark)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { exportData(jsonOnly = true) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = AppButtonShape,
                        ) { Text("仅导出 JSON") }
                        Button(
                            onClick = { exportData(jsonOnly = false) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = AppButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                        ) { Text("导出完整 ZIP") }
                    }
                    TextButton(
                        onClick = { showDeleteConfirmation = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("删除全部数据", color = Danger) }
                }
            }
        }
        if (BuildConfig.DEBUG) item {
            WarmCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    SectionHeading("测试数据准备", "仅开发版本可用，演示记录会在成长回望中标明来源")
                    Text("主故事固定为 2026年9月7日至13日，展示从怀疑自己到愿意行动、求助、调整和照顾生活。演示故事不计入你的真实成长阶段。",
                        style = MaterialTheme.typography.bodyLarge)
                    state.demoNotice?.let { Text(it, color = SageDark) }
                    if (state.loading) CircularProgressIndicator(Modifier.size(24.dp), color = SageDark)
                    Button(
                        onClick = onCreateExamWeekDemoData,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = AppButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                    ) { Text("生成 9.7—9.13 主故事") }
                    OutlinedButton(
                        onClick = onCreateFourWeekDemoData,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppButtonShape,
                    ) { Text("生成最近四周扩展故事") }
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
