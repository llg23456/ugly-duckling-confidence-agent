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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.testconnection.confidence_agent.data.repository.ChatRepository
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
    onProfileUpdated: (UserProfile) -> Unit,
) {
    val context = LocalContext.current
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    val api = remember { MemoryApiClient() }
    val scope = rememberCoroutineScope()
    val chatRepository = remember { ChatRepository(context.applicationContext) }
    var displayedProfile by remember(profile) { mutableStateOf(profile) }
    var profileRefreshing by remember { mutableStateOf(false) }
    var profileNotice by remember { mutableStateOf<String?>(null) }
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
    val items = displayedProfile?.displayItems().orEmpty()
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
        modifier = Modifier.fillMaxSize().background(Cream).padding(top = 22.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", modifier = Modifier.noRippleClickable(onClick = onBack).padding(10.dp), style = MaterialTheme.typography.headlineLarge)
            Text("记忆中心", style = MaterialTheme.typography.displaySmall)
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            DuckArt(R.drawable.duck_welcome, "记忆中心的小鸭", Modifier.size(130.dp).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(20.dp))
            if (items.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                ) {
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
        }
        profileNotice?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 8.dp, end = 20.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("已")) SageDark else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val current = displayedProfile ?: UserProfile()
                    profileRefreshing = true
                    profileNotice = null
                    scope.launch {
                        runCatching { chatRepository.refreshProfile(current) }
                            .onSuccess { result ->
                                displayedProfile = result.profile
                                onProfileUpdated(result.profile)
                                profileNotice = "已根据最近对话和主动记录更新画像。"
                            }
                            .onFailure { profileNotice = it.message ?: "画像更新失败，请稍后重试。" }
                        profileRefreshing = false
                    }
                },
                enabled = !profileRefreshing,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                if (profileRefreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = SageDark)
                } else {
                    Text("更新画像")
                }
            }
            Button(
                onClick = onRestartOnboarding,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("重新认识") }
        }
    }
}
