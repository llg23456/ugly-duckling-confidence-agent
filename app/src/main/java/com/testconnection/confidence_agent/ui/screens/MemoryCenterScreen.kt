package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.TerracottaPale
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import com.testconnection.confidence_agent.ui.theme.WarmWhite
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

private val stageArt = listOf(
    R.drawable.duck_growth_start_v3,
    R.drawable.duck_growth_try_v3,
    R.drawable.duck_growth_open_v3,
)

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
    var recordIds by remember { mutableStateOf(store.recordIds()) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingEvidence by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var evidenceIds by remember { mutableStateOf<List<Long>?>(null) }
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedRecord by remember { mutableStateOf<RecordDraft?>(null) }
    var editingProfile by remember { mutableStateOf<UserProfile?>(null) }

    fun updateDisplay() {
        journey = store.journey()
        events = store.events()
        history = store.snapshots()
    }
    suspend fun reloadEvidence() {
        records.pendingDeletions().forEach { id -> api.deleteRecord(deviceId, id); records.confirmDeletion(id) }
        val synced = api.syncRecords(deviceId, records.load().filter { it.status == "saved" })
        recordIds = synced.associate { it.serverId to it.clientId }
        store.reconcile(api.events(deviceId), recordIds)?.let { restored ->
            displayedProfile = restored
            onProfileUpdated(restored)
        }
        displayedProfile?.let { store.capture(it, "生活变化") }
        updateDisplay()
    }

    LaunchedEffect(Unit) {
        displayedProfile?.let { store.capture(it, if (history.isEmpty()) "认识你" else "生活变化") }
        history = store.snapshots()
        runCatching { reloadEvidence() }
            .onFailure { notice = "正在显示本机保存的画像与变化，联网后可重试更新。" }
        loadingEvidence = false
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.first, fontWeight = FontWeight.Bold) },
            text = {
                Surface(color = SagePale, shape = RoundedCornerShape(18.dp)) {
                    Text(item.second, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("收好") } },
        )
    }

    evidenceIds?.let { ids ->
        val evidence = events.filter { it.id in ids }
        AlertDialog(
            onDismissRequest = { evidenceIds = null },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("这些变化来自", fontWeight = FontWeight.Bold)
                    Text("${evidence.size} 条真实片段，按时间整理", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
            },
            text = {
                if (evidence.isEmpty()) {
                    Text("来源已移除，请更新画像。")
                } else {
                    LazyColumn(Modifier.heightIn(max = 470.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(evidence, key = { it.id }) { event ->
                            EvidenceCard(event = event, onOpen = {
                                when {
                                    event.sourceRecordId != null -> scope.launch {
                                        runCatching {
                                            records.load().firstOrNull { it.id == recordIds[event.sourceRecordId] }
                                                ?: api.record(deviceId, event.sourceRecordId)
                                        }.onSuccess {
                                            selectedRecord = it
                                            evidenceIds = null
                                        }.onFailure {
                                            notice = "来源暂时无法打开，请联网后重试。"
                                            evidenceIds = null
                                        }
                                    }
                                    event.sourceFeedbackId != null -> { evidenceIds = null; onOpenFeedback() }
                                    event.sourceId != null -> { evidenceIds = null; onOpenSource(event.sourceId) }
                                }
                            })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { evidenceIds = null }) { Text("收好") } },
        )
    }

    selectedRecord?.let { record ->
        RecordDetailDialog(record, onDismiss = { selectedRecord = null }, onEdit = {
            selectedRecord = null
            onEditRecord(record)
        })
    }

    editingProfile?.let { initial ->
        ProfileEditorDialog(
            initial = initial,
            onDismiss = { editingProfile = null },
            onSave = { updated ->
                displayedProfile = updated
                onProfileUpdated(updated)
                updateDisplay()
                editingProfile = null
                notice = "完整画像已按你的修改保存。"
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Cream).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("记忆中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                WarmCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                        Text("从生活片段，到今天的你", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("记忆不是一段结论，而是从真实经历里慢慢整理出来的。", color = InkMuted)
                        MemoryFormationFlow(
                            recordCount = events.count { it.sourceType != "demo" },
                            keywordCount = journey.keywords.size,
                            hasProfile = displayedProfile != null,
                            stage = journey.stage,
                        )
                    }
                }
            }

            item {
                WarmCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CurrentStageHero(journey.stage, displayedProfile) {
                            selected = "成长阶段怎样变化" to
                                "留下两个不同日期的真实尝试，会进入“尝试”；留下四个不同日期的尝试，并出现求助、调整方法或照顾自己，会进入“舒展”。停止记录不会降级，删除依据后会重新整理。"
                        }
                        GrowthStageTrack(journey.stage)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("现在记住了什么", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        journey.keywords.forEach { keyword ->
                            AssistChip(onClick = { evidenceIds = keyword.eventIds }, label = { Text(keyword.label) })
                        }
                    }
                    if (journey.keywords.isEmpty()) Text("有了真实记录，这里会慢慢出现你的行动关键词。", color = InkMuted)
                    if (events.any { it.sourceType == "demo" }) {
                        Text("考研演示故事只用于展示，不会计入真实画像。", style = MaterialTheme.typography.bodySmall, color = SageDark)
                    }
                }
            }

            item {
                WarmCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Text("一路怎么形成", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("每一次变化都能回到当时留下的生活片段。", color = InkMuted)
                        if (journey.milestones.isEmpty()) {
                            Text("从愿意留下第一条记录开始，成长线会在这里出现。")
                        } else {
                            journey.milestones.forEachIndexed { index, milestone ->
                                MilestoneRow(milestone.date, milestone.stage, index == journey.milestones.lastIndex) {
                                    evidenceIds = milestone.eventIds
                                }
                            }
                        }
                        ProfileChangeSummary(history = history, onOpen = { selected = it })
                        if (history.isNotEmpty()) {
                            TextButton(onClick = {
                                selected = "画像更新记录" to history.asReversed().joinToString("\n\n") { snapshot ->
                                    val date = Instant.ofEpochMilli(snapshot.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                                    "$date · ${snapshot.reason}\n${snapshot.keywords.joinToString("、") { it.label }.ifBlank { "初次认识" }}"
                                }
                            }, contentPadding = PaddingValues(0.dp)) { Text("查看完整画像历史 ›") }
                        }
                    }
                }
            }

            item {
                ProfileOverviewCard(profile = displayedProfile, onEdit = { editingProfile = displayedProfile ?: UserProfile() })
            }

            notice?.let { message ->
                item { Text(message, style = MaterialTheme.typography.bodySmall, color = SageDark) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    refreshing = true
                    notice = null
                    scope.launch {
                        runCatching { reloadEvidence(); chats.refreshProfile(displayedProfile ?: UserProfile()) }
                            .onSuccess { result ->
                                displayedProfile = result.profile
                                onProfileUpdated(result.profile)
                                store.capture(result.profile, "画像更新")
                                updateDisplay()
                                notice = "已更新画像，变化依据可点开查看。"
                            }.onFailure { notice = "画像更新暂未完成，已保留本机内容，请联网后重试。" }
                        refreshing = false
                    }
                },
                enabled = !refreshing && !loadingEvidence,
                modifier = Modifier.weight(1f),
            ) {
                if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("更新画像")
            }
            Button(onClick = onRestartOnboarding, modifier = Modifier.weight(1f), enabled = !refreshing && !loadingEvidence) {
                Text("重新认识")
            }
        }
    }
}

@Composable
private fun MemoryFormationFlow(recordCount: Int, keywordCount: Int, hasProfile: Boolean, stage: Int) {
    val nodes = listOf(
        Triple("1", "生活片段", "$recordCount 条"),
        Triple("2", "可信记忆", if (keywordCount == 0) "整理中" else "$keywordCount 个词"),
        Triple("3", "当前画像", if (hasProfile) "已形成" else "待认识"),
        Triple("4", "成长变化", growthStageNames[stage]),
    )
    val activeNodes = listOf(recordCount > 0, keywordCount > 0, hasProfile, stage > 0)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        nodes.forEachIndexed { index, node ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(34.dp).background(if (activeNodes[index]) SageDark else WarmOutline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(node.first, color = Color.White, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(6.dp))
                Text(node.second, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(node.third, style = MaterialTheme.typography.bodySmall, color = InkMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CurrentStageHero(stage: Int, profile: UserProfile?, onOpenBasis: () -> Unit) {
    @Composable
    fun Copy(modifier: Modifier = Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("现在的我 · ${growthStageNames[stage]}", style = MaterialTheme.typography.titleLarge, color = SageDark)
            Text(profileSummary(profile), style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
            TextButton(onClick = onOpenBasis, contentPadding = PaddingValues(0.dp)) { Text("查看阶段依据 ›") }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Crossfade(targetState = stage, label = "成长阶段插画窄屏") { current ->
                    DuckArt(stageArt[current], "${growthStageNames[current]}阶段", Modifier.fillMaxWidth().height(158.dp).clip(RoundedCornerShape(22.dp)))
                }
                Copy()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crossfade(targetState = stage, label = "成长阶段插画") { current ->
                    DuckArt(stageArt[current], "${growthStageNames[current]}阶段", Modifier.size(132.dp).clip(RoundedCornerShape(22.dp)))
                }
                Spacer(Modifier.width(14.dp))
                Copy(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EvidenceCard(event: GrowthEvent, onOpen: () -> Unit) {
    val canOpen = event.sourceRecordId != null || event.sourceFeedbackId != null || event.sourceId != null
    Surface(color = WarmWhite, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, WarmOutline)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = SagePale, shape = RoundedCornerShape(9.dp)) {
                    Text(GrowthJourneyAnalyzer.day(event)?.toString().orEmpty(), Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = SageDark)
                }
                Text(sourceLabel(event), style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
            Text(event.fact, style = MaterialTheme.typography.bodyLarge, lineHeight = 25.sp)
            if (canOpen) {
                TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("查看原始记录  →") }
            } else {
                Text("该条只保留了整理后的事实", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
        }
    }
}

private fun sourceLabel(event: GrowthEvent): String = when {
    event.sourceType == "demo" -> "考研演示记录"
    event.sourceRecordId != null -> "生活记录"
    event.sourceFeedbackId != null -> "支持圈反馈"
    else -> "对话片段"
}

@Composable
private fun MilestoneRow(date: String, stage: Int, isLast: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(10.dp).background(if (isLast) SageDark else Color(0xFFC98268), CircleShape))
            if (!isLast) Box(Modifier.width(2.dp).height(50.dp).background(WarmOutline))
        }
        Spacer(Modifier.width(12.dp))
        DuckArt(stageArt[stage], growthStageNames[stage], Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(growthStageNames[stage], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(date, style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
        Text("查看依据 ›", style = MaterialTheme.typography.labelMedium, color = SageDark)
    }
}

@Composable
private fun ProfileChangeSummary(history: List<ProfileSnapshot>, onOpen: (Pair<String, String>) -> Unit) {
    val latest = history.lastOrNull() ?: return
    val previous = history.dropLast(1).lastOrNull() ?: return
    val beforeTags = previous.keywords.map { it.label }
    val afterTags = latest.keywords.map { it.label }
    if (beforeTags != afterTags) {
        Surface(color = TerracottaPale, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("关键词的变化", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(beforeTags.joinToString(" · ").ifBlank { "尚未出现" }, Modifier.weight(1f), color = InkMuted)
                    Text("→", Modifier.padding(horizontal = 8.dp), color = SageDark)
                    Text(afterTags.joinToString(" · ").ifBlank { "等待记录" }, Modifier.weight(1f), color = SageDark)
                }
            }
        }
    }
    val before = previous.profile.displayItems().toMap()
    latest.profile.displayItems().firstOrNull { before[it.first] != it.second }?.let { (label, value) ->
        TextButton(
            onClick = { onOpen(label to "之前：${before[label] ?: "尚未记录"}\n\n现在：$value") },
            contentPadding = PaddingValues(0.dp),
        ) { Text("$label 也有了变化 ›") }
    }
}

@Composable
private fun ProfileOverviewCard(profile: UserProfile?, onEdit: () -> Unit) {
    WarmCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("完整画像", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("这是可修改的生活画像，不是给你贴标签。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
                OutlinedButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 13.dp, vertical = 5.dp)) { Text("编辑") }
            }
            if (profile == null || profile.displayItems().isEmpty()) {
                Text("还没有完整画像，可以先编辑或重新认识。")
            } else {
                ProfileGroup("我是谁", profile, listOf("称呼", "性别表达", "年龄阶段", "当前阶段"))
                ProfileGroup("此刻的生活", profile, listOf("最近的生活", "想慢慢改变"))
                ProfileGroup("陪伴与支持", profile, listOf("喜欢的陪伴", "支持我的人"))
            }
        }
    }
}

@Composable
private fun ProfileGroup(title: String, profile: UserProfile, labels: List<String>) {
    val values = profile.displayItems().filter { it.first in labels }
    if (values.isEmpty()) return
    Surface(color = SagePale.copy(alpha = 0.62f), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = SageDark, fontWeight = FontWeight.Bold)
            values.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.width(84.dp), style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ProfileEditorDialog(initial: UserProfile, onDismiss: () -> Unit, onSave: (UserProfile) -> Unit) {
    fun text(value: ProfileValue) = value.value.takeUnless { it == "unknown" || it == "prefer_not_to_say" } ?: ""
    var name by remember(initial) { mutableStateOf(text(initial.preferredName)) }
    var gender by remember(initial) { mutableStateOf(text(initial.gender)) }
    var age by remember(initial) { mutableStateOf(text(initial.ageRange)) }
    var stage by remember(initial) { mutableStateOf(text(initial.lifeStage)) }
    var context by remember(initial) { mutableStateOf(text(initial.currentContext)) }
    var challenge by remember(initial) { mutableStateOf(text(initial.mainChallenge)) }
    var supportStyle by remember(initial) { mutableStateOf(text(initial.preferredSupportStyle)) }
    var supporters by remember(initial) { mutableStateOf(text(initial.importantSupporters)) }
    fun value(raw: String) = raw.trim().takeIf(String::isNotBlank)?.let { ProfileValue(it, 1.0) } ?: ProfileValue()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("编辑完整画像", fontWeight = FontWeight.Bold)
                Text("只修改你愿意留下的内容", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { ProfileField("称呼", name) { name = it } }
                item { ProfileField("性别表达", gender) { gender = it } }
                item { ProfileField("年龄阶段", age) { age = it } }
                item { ProfileField("当前阶段", stage) { stage = it } }
                item { ProfileField("最近的生活", context, false) { context = it } }
                item { ProfileField("想慢慢改变", challenge, false) { challenge = it } }
                item { ProfileField("喜欢的陪伴", supportStyle, false) { supportStyle = it } }
                item { ProfileField("支持我的人", supporters, false) { supporters = it } }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(onClick = {
                onSave(UserProfile(
                    preferredName = value(name), gender = value(gender), ageRange = value(age), lifeStage = value(stage),
                    currentContext = value(context), mainChallenge = value(challenge),
                    preferredSupportStyle = value(supportStyle), importantSupporters = value(supporters),
                ))
            }) { Text("保存", color = SageDark) }
        },
    )
}

@Composable
private fun ProfileField(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun GrowthStageTrack(currentStage: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val currentSize = if (maxWidth < 300.dp) 48.dp else 70.dp
        val otherSize = if (maxWidth < 300.dp) 42.dp else 58.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            growthStageNames.forEachIndexed { index, label ->
                Column(
                    modifier = Modifier.weight(1f)
                        .background(if (index == currentStage) SagePale else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(17.dp))
                        .padding(7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    DuckArt(stageArt[index], "$label 阶段", Modifier.size(if (index == currentStage) currentSize else otherSize).clip(RoundedCornerShape(13.dp)))
                    Text(label, style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (index == currentStage) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == currentStage) SageDark else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun profileSummary(profile: UserProfile?): String {
    if (profile == null) return "从第一条真实记录开始，慢慢认识此刻的自己。"
    val context = profile.currentContext.value.takeUnless { it in listOf("", "unknown", "prefer_not_to_say") }
    val challenge = profile.mainChallenge.value.takeUnless { it in listOf("", "unknown", "prefer_not_to_say") }
    return context?.take(34) ?: challenge?.let { "正在学习面对：${it.take(26)}" }
        ?: "你留下的行动、求助与调整，正在组成更清楚的自己。"
}
