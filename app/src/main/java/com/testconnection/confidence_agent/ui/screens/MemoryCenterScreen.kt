package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.model.*
import com.testconnection.confidence_agent.data.preferences.*
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.data.remote.ReviewApiClient
import com.testconnection.confidence_agent.data.repository.ChatRepository
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.SageDark
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryCenterScreen(
    profile: UserProfile?, onBack: () -> Unit, onRestartOnboarding: () -> Unit,
    onOpenSource: (Long) -> Unit, onProfileUpdated: (UserProfile) -> Unit,
    onOpenFeedback: () -> Unit, onEditRecord: (RecordDraft) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current.applicationContext
    val store = remember { ProfileJourneyStore(context) }
    val profileStore = remember { OnboardingStore(context) }
    val deviceId = remember { DeviceIdStore(context).get() }
    val api = remember { ReviewApiClient() }
    val chats = remember { ChatRepository(context) }
    val records = remember { LocalRecordRepository(context) }
    val scope = rememberCoroutineScope()
    var displayedProfile by remember { mutableStateOf(profileStore.loadProfile() ?: profile) }
    var journey by remember { mutableStateOf(store.journey()) }
    var events by remember { mutableStateOf(store.events()) }
    var history by remember { mutableStateOf(store.snapshots()) }
    var recordIds by remember { mutableStateOf(emptyMap<Long, String>()) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingEvidence by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var evidenceIds by remember { mutableStateOf<List<Long>?>(null) }
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedRecord by remember { mutableStateOf<RecordDraft?>(null) }

    fun updateDisplay() { journey = store.journey(); events = store.events(); history = store.snapshots() }
    suspend fun reloadEvidence() {
        records.pendingDeletions().forEach { id -> api.deleteRecord(deviceId, id); records.confirmDeletion(id) }
        val synced = api.syncRecords(deviceId, records.load().filter { it.status == "saved" })
        recordIds = synced.associate { it.serverId to it.clientId }
        store.reconcile(api.events(deviceId), recordIds)?.let { restored -> displayedProfile = restored; onProfileUpdated(restored) }
        displayedProfile?.let { store.capture(it, "生活变化") }
        updateDisplay()
    }
    LaunchedEffect(Unit) {
        displayedProfile?.let { store.capture(it, if (history.isEmpty()) "认识你" else "生活变化") }
        history = store.snapshots()
        runCatching { reloadEvidence() }.onFailure { notice = "正在显示本机保存的画像与变化，联网后可重试更新。" }
        loadingEvidence = false
    }
    selected?.let { item -> AlertDialog(
        onDismissRequest = { selected = null }, title = { Text(item.first) },
        text = { Text(item.second, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { selected = null }) { Text("收好") } },
    ) }
    evidenceIds?.let { ids -> AlertDialog(
        onDismissRequest = { evidenceIds = null }, title = { Text("这些变化来自") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                events.filter { it.id in ids }.forEach { event ->
                    Column {
                        Text("${GrowthJourneyAnalyzer.day(event)}${if (event.sourceType == "demo") " · 演示记录" else ""}", style = MaterialTheme.typography.labelLarge, color = SageDark)
                        Text(event.fact)
                        TextButton(onClick = {
                            when {
                                event.sourceRecordId != null -> scope.launch {
                                    runCatching { records.load().firstOrNull { it.id == recordIds[event.sourceRecordId] }
                                        ?: api.record(deviceId, event.sourceRecordId) }
                                        .onSuccess { selectedRecord = it; evidenceIds = null }
                                        .onFailure { notice = "来源暂时无法打开，请联网后重试。"; evidenceIds = null }
                                }
                                event.sourceFeedbackId != null -> { evidenceIds = null; onOpenFeedback() }
                                event.sourceId != null -> { evidenceIds = null; onOpenSource(event.sourceId) }
                            }
                        }, enabled = event.sourceRecordId != null || event.sourceFeedbackId != null || event.sourceId != null) { Text("查看原始记录") }
                    }
                }
                if (events.none { it.id in ids }) Text("来源已移除，请更新画像。")
            }
        }, confirmButton = { TextButton(onClick = { evidenceIds = null }) { Text("收好") } },
    ) }
    selectedRecord?.let { record -> RecordDetailDialog(record, onDismiss = { selectedRecord = null }, onEdit = {
        selectedRecord = null; onEditRecord(record)
    }) }

    Column(Modifier.fillMaxSize().background(Cream).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("记忆中心", style = MaterialTheme.typography.headlineSmall)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                WarmCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Crossfade(targetState = journey.stage, label = "小鸭成长") { stage ->
                            DuckArt(listOf(R.drawable.duck_growth_start, R.drawable.duck_step, R.drawable.duck_growth_open)[stage],
                                "${growthStageNames[stage]}阶段的小鸭", Modifier.size(130.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(growthStageNames[journey.stage], style = MaterialTheme.typography.headlineSmall, color = SageDark)
                            Text(listOf("先认识此刻的自己", "你留下了真实的尝试", "尝试，也照顾生活")[journey.stage])
                            TextButton(onClick = { selected = "小鸭如何变化" to "两个不同日期留下真实尝试，小鸭进入尝试阶段；四个不同日期有尝试，并记录了求助、调整方法或照顾自己，小鸭进入舒展阶段。\n\n形象依据你留下的经历变化。停止记录不会让小鸭降级。删除作为依据的记录后，会重新整理阶段。" }) { Text("查看阶段依据") }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("现在的关键词", style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        journey.keywords.forEach { keyword -> AssistChip(onClick = { evidenceIds = keyword.eventIds }, label = { Text(keyword.label) }) }
                    }
                    if (journey.keywords.isEmpty()) Text("有了真实记录，这里会慢慢出现你的尝试。")
                    if (events.any { it.sourceType == "demo" }) Text("包含演示故事，点击关键词可查看来源。", style = MaterialTheme.typography.bodySmall, color = SageDark)
                }
            }
            item {
                WarmCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("一路的变化", style = MaterialTheme.typography.titleLarge)
                        journey.milestones.forEach { milestone ->
                            TextButton(onClick = { evidenceIds = milestone.eventIds }, contentPadding = PaddingValues(0.dp)) {
                                Text("${milestone.date}  ·  ${growthStageNames[milestone.stage]}  ›")
                            }
                        }
                        if (journey.milestones.isEmpty()) Text("从愿意留下第一条记录开始。")
                        val latest = history.lastOrNull()
                        val previous = history.dropLast(1).lastOrNull()
                        if (latest != null && previous != null) {
                            val beforeTags = previous.keywords.map { it.label }
                            val afterTags = latest.keywords.map { it.label }
                            if (beforeTags != afterTags) {
                                Text("之前：${beforeTags.joinToString(" · ").ifBlank { "尚无行为关键词" }}", style = MaterialTheme.typography.bodyMedium)
                                Text("现在：${afterTags.joinToString(" · ").ifBlank { "等待新的记录" }}", color = SageDark)
                            }
                            val before = previous.profile.displayItems().toMap()
                            latest.profile.displayItems().filter { before[it.first] != it.second }.take(2).forEach { (label, value) ->
                                TextButton(onClick = { selected = label to "之前：${before[label] ?: "尚未记录"}\n\n现在：$value" }) { Text("$label 有了变化 ›") }
                            }
                        }
                        if (history.isNotEmpty()) TextButton(onClick = {
                            selected = "画像更新记录" to history.asReversed().joinToString("\n\n") { snapshot ->
                                val date = Instant.ofEpochMilli(snapshot.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                                "$date · ${snapshot.reason}\n${snapshot.keywords.joinToString("、") { it.label }.ifBlank { "初次认识" }}\n${snapshot.profile.displayItems().filter { it.first in listOf("最近的生活", "想慢慢改变", "支持我的人") }.joinToString("\n") { "${it.first}：${it.second}" }}"
                            }
                        }) { Text("查看画像历史 ›") }
                    }
                }
            }
            item {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起完整画像 ▴" else "查看完整画像 ▾") }
                if (expanded) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    displayedProfile?.displayItems().orEmpty().forEach { item ->
                        Column { Text(item.first, style = MaterialTheme.typography.labelLarge, color = SageDark); Text(item.second) }
                    }
                    if (displayedProfile == null) Text("还没有初次画像，可以点击重新认识。")
                }
            }
            notice?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = SageDark) } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                refreshing = true; notice = null
                scope.launch {
                    runCatching { reloadEvidence(); chats.refreshProfile(displayedProfile ?: UserProfile()) }
                        .onSuccess { result ->
                            displayedProfile = result.profile; onProfileUpdated(result.profile)
                            store.capture(result.profile, "画像更新"); updateDisplay()
                            notice = "已更新画像，变化依据可点开查看。"
                        }.onFailure { notice = "画像更新暂未完成，已保留本机内容，请联网后重试。" }
                    refreshing = false
                }
            }, enabled = !refreshing && !loadingEvidence, modifier = Modifier.weight(1f)) {
                if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("更新画像")
            }
            Button(onClick = onRestartOnboarding, modifier = Modifier.weight(1f), enabled = !refreshing && !loadingEvidence) { Text("重新认识") }
        }
    }
}
