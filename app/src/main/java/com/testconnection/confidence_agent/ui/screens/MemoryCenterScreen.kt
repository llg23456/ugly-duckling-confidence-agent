package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.model.UserProfile
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.MemoryApiClient
import com.testconnection.confidence_agent.data.remote.SavedMemory
import com.testconnection.confidence_agent.data.remote.DailySummaryDraft
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.TerracottaPale
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun MemoryCenterScreen(
    profile: UserProfile?,
    onBack: () -> Unit,
    onRestartOnboarding: () -> Unit,
    onOpenSource: (Long) -> Unit,
) {
    val context = LocalContext.current
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    val api = remember { MemoryApiClient() }
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<SavedMemory>>(emptyList()) }
    var dailyDrafts by remember { mutableStateOf<List<DailySummaryDraft>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SavedMemory?>(null) }
    var editText by remember { mutableStateOf("") }
    fun reload() { scope.launch {
        runCatching { api.list(deviceId) }
            .onSuccess { memories = it; loadError = null }
            .onFailure { loadError = "记忆暂时无法加载，请稍后重试。" }
        runCatching { api.dailyDrafts(deviceId) }.onSuccess { dailyDrafts = it }
    } }
    LaunchedEffect(Unit) { reload() }
    val items = profile?.displayItems().orEmpty()
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    val colors = listOf(SagePale, TerracottaPale, Color(0xFFF3E7C9), Color(0xFFE6EDF2), Color(0xFFE9E0F0))

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Cream,
            shape = RoundedCornerShape(30.dp),
            title = { Text(item.first) },
            text = { Text(item.second, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("收好") } },
        )
    }
    editing?.let { memory ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("修改这条记忆") },
            text = { OutlinedTextField(value = editText, onValueChange = { editText = it }, label = { Text("记忆内容") }) },
            confirmButton = { TextButton(onClick = {
                if (editText.isNotBlank()) scope.launch {
                    runCatching { api.edit(deviceId, memory.id, editText.trim()) }
                        .onSuccess { editing = null; reload() }
                        .onFailure { loadError = "修改失败，请重试。" }
                }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Cream).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", modifier = Modifier.noRippleClickable(onClick = onBack).padding(10.dp), style = MaterialTheme.typography.headlineLarge)
            Text("记忆中心", style = MaterialTheme.typography.displaySmall)
        }
        Text("这些是你亲口告诉小鸭的。左右翻一翻，点开可以放大查看。", style = MaterialTheme.typography.bodyLarge, color = SageDark)
        Spacer(Modifier.height(20.dp))
        DuckArt(R.drawable.duck_welcome, "记忆中心的小鸭", Modifier.size(130.dp).align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = SagePale) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有建立初步画像", style = MaterialTheme.typography.titleLarge)
                    Text("你可以重新和小鸭认识一下。", style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(items) { index, item ->
                    val size = if (index % 3 == 0) 176.dp else 150.dp
                    Box(
                        modifier = Modifier
                            .size(size)
                            .background(colors[index % colors.size], CircleShape)
                            .noRippleClickable { selected = item }
                            .padding(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(item.first, style = MaterialTheme.typography.titleMedium, color = SageDark)
                            Text(item.second, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 4)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("对话中记住的事", style = MaterialTheme.typography.titleLarge)
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (memories.isEmpty()) Text("还没有保存的对话记忆。", style = MaterialTheme.typography.bodyMedium)
        memories.forEach { memory ->
            Surface(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(20.dp), color = SagePale) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(memory.content, style = MaterialTheme.typography.bodyLarge)
                    Text("${memory.sourceDate ?: "未知日期"} · ${memory.sourceType ?: "对话"}${if (memory.status == "pending") " · 待确认" else ""}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        memory.sourceId?.let { sourceId -> TextButton(onClick = { onOpenSource(sourceId) }) { Text("查看来源") } }
                        if (memory.status == "pending") TextButton(onClick = { scope.launch {
                            runCatching { api.confirm(deviceId, memory.id) }.onSuccess { reload() }.onFailure { loadError = "确认失败，请重试。" }
                        } }) { Text("确认保存") }
                        TextButton(onClick = { editing = memory; editText = memory.content }) { Text("修改") }
                        TextButton(onClick = { scope.launch {
                            runCatching { api.delete(deviceId, memory.id) }.onSuccess { reload() }.onFailure { loadError = "删除失败，请重试。" }
                        } }) { Text("删除") }
                    }
                }
            }
        }
        if (dailyDrafts.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("当日摘要草稿", style = MaterialTheme.typography.titleLarge)
            dailyDrafts.forEach { draft ->
                Surface(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(20.dp), color = TerracottaPale) {
                    Column(Modifier.padding(16.dp)) {
                        Text(draft.day, style = MaterialTheme.typography.bodySmall)
                        Text(draft.content, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRestartOnboarding, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (items.isEmpty()) "现在认识一下" else "重新认识我")
        }
        Text(
            "重新认识不会自动公开任何内容；不愿回答的项目可以选择不透露。",
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
