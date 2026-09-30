package com.testconnection.confidence_agent.ui.screens

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.audio.AudioReplyPlayer
import com.testconnection.confidence_agent.data.preferences.DuckVoiceMode
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import com.testconnection.confidence_agent.data.preferences.VoicePreferences
import com.testconnection.confidence_agent.data.remote.ServerConnectionManager
import com.testconnection.confidence_agent.data.repository.ChatRepository
import com.testconnection.confidence_agent.data.repository.FakeConfidenceRepository
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.Danger
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.widget.WidgetPrivacyStore
import com.testconnection.confidence_agent.widget.WidgetUpdater
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    contentPadding: PaddingValues,
    userName: String,
    voicePreferences: VoicePreferences,
    onVoicePreferencesChange: (VoicePreferences) -> Unit,
    onOpenMemoryCenter: () -> Unit,
    onOpenSupportCircle: () -> Unit,
    onOpenDataTools: () -> Unit,
    onServerEndpointChanged: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val chatRepository = remember { ChatRepository() }
    val previewPlayer = remember { AudioReplyPlayer(context.applicationContext) }
    var previewing by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    val systemTts = remember {
        TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }
    var showVoiceSettings by remember { mutableStateOf(false) }
    val widgetPrivacy = remember { WidgetPrivacyStore(context.applicationContext) }
    val localRecordRepository = remember { LocalRecordRepository(context.applicationContext) }
    var showWidgetPrivacy by remember { mutableStateOf(false) }
    var widgetAllowed by remember { mutableStateOf(widgetPrivacy.isAllowed()) }
    var widgetPreview by remember { mutableStateOf(widgetPrivacy.snapshot()) }
    var widgetRecords by remember { mutableStateOf<List<RecordDraft>>(emptyList()) }
    var todayWidgetRecordId by remember { mutableStateOf(widgetPrivacy.selectedTodayRecordId()) }
    var monthWidgetRecordId by remember { mutableStateOf(widgetPrivacy.selectedMonthRecordId()) }
    var showHelpResources by remember { mutableStateOf(false) }
    var serverAddress by remember { mutableStateOf(ServerEndpoint.current()) }
    var serverStatus by remember { mutableStateOf("当前使用：${ServerEndpoint.current()}") }
    var serverChecking by remember { mutableStateOf(false) }

    fun detectServer() {
        if (serverChecking) return
        serverChecking = true
        serverStatus = "正在扫描当前 Wi-Fi…"
        coroutineScope.launch {
            runCatching { ServerConnectionManager.detectOnWifi(context) }
                .onSuccess { health ->
                    serverAddress = ServerEndpoint.save(context, health.baseUrl)
                    serverStatus = "连接成功 · ${health.model}${if (health.liveAi) " · 云端 AI 已启用" else " · 演示回复"}"
                    onServerEndpointChanged()
                }
                .onFailure { error ->
                    serverStatus = error.message ?: "没有找到可用后端"
                }
            serverChecking = false
        }
    }

    fun checkAndSaveServer() {
        if (serverChecking) return
        serverChecking = true
        serverStatus = "正在检测输入的地址…"
        coroutineScope.launch {
            runCatching { ServerConnectionManager.check(serverAddress) }
                .onSuccess { health ->
                    serverAddress = ServerEndpoint.save(context, health.baseUrl)
                    serverStatus = "连接成功 · ${health.model}${if (health.liveAi) " · 云端 AI 已启用" else " · 演示回复"}"
                    onServerEndpointChanged()
                }
                .onFailure { error ->
                    serverStatus = error.message ?: "该地址暂时无法连接"
                }
            serverChecking = false
        }
    }

    if (showHelpResources) AlertDialog(
        onDismissRequest = { if (!serverChecking) showHelpResources = false },
        containerColor = Cream,
        shape = RoundedCornerShape(30.dp),
        title = { Text("帮助与求助资源") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("遇到紧急危险时，请优先联系身边可信任的人或当地紧急援助。小鸭不会替你自动发送消息。")
                HorizontalDivider()
                Text("后端连接 · 测试工具", style = MaterialTheme.typography.titleMedium)
                Text("手机和电脑连接同一 Wi-Fi、电脑已启动后端时，可以自动找到并保存新地址。")
                OutlinedTextField(
                    value = serverAddress,
                    onValueChange = { serverAddress = it; serverStatus = "地址尚未检测" },
                    enabled = !serverChecking,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务器地址") },
                    placeholder = { Text("http://192.168.1.10:8000") },
                )
                Button(
                    onClick = ::detectServer,
                    enabled = !serverChecking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                ) { Text("一键检测当前 Wi-Fi") }
                TextButton(
                    onClick = ::checkAndSaveServer,
                    enabled = !serverChecking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("检测并保存输入地址") }
                if (serverChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp).align(Alignment.CenterHorizontally),
                        color = SageDark,
                        strokeWidth = 2.dp,
                    )
                }
                Text(serverStatus, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = { showHelpResources = false }, enabled = !serverChecking) { Text("收好") }
        },
    )

    if (showWidgetPrivacy) AlertDialog(
        onDismissRequest = { showWidgetPrivacy = false },
        title = { Text("桌面展示许可") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("桌面组件可能被身边的人看见。开启后可自定选择展示一条主动记录：文字记录展示几句话，照片记录会展示照片和配文，语音记录只展示转写文字。不选时仍只展示经敏感过滤的成长事件。可随时关闭。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许桌面展示", modifier = Modifier.weight(1f))
                    Switch(checked = widgetAllowed, onCheckedChange = { allowed ->
                        widgetPrivacy.setAllowed(allowed)
                        widgetAllowed = allowed
                        widgetPreview = widgetPrivacy.snapshot()
                        coroutineScope.launch {
                            runCatching { WidgetUpdater.refreshGrowthWidgets(context.applicationContext) }
                            widgetPreview = widgetPrivacy.snapshot()
                        }
                    })
                }
                Text(if (widgetAllowed) "今日预览：${widgetPreview.todayText.ifBlank { "没有适合展示的事件" }}\n本月可展示：${widgetPreview.monthCount} 条"
                    else "当前组件只显示通用提示，不展示你的经历。")
                if (widgetAllowed) {
                    Text("为两个展示组件选择记录", style = MaterialTheme.typography.titleMedium)
                    Text("只能选择你主动保存的记录；照片将直接出现在桌面。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            widgetPrivacy.selectTodayRecord(null)
                            todayWidgetRecordId = null
                            coroutineScope.launch {
                                WidgetUpdater.refreshGrowthWidgets(context.applicationContext)
                                widgetPreview = widgetPrivacy.snapshot()
                            }
                        }) { Text("清空今日选择") }
                        TextButton(onClick = {
                            widgetPrivacy.selectMonthRecord(null)
                            monthWidgetRecordId = null
                            coroutineScope.launch {
                                WidgetUpdater.refreshGrowthWidgets(context.applicationContext)
                                widgetPreview = widgetPrivacy.snapshot()
                            }
                        }) { Text("清空本月选择") }
                    }
                    widgetRecords.take(8).forEach { record ->
                        val label = when (record.mode) {
                            RecordMode.PHOTO -> "照片·${record.photoComment.ifBlank { record.aiDescription }.ifBlank { "一张照片" }}"
                            RecordMode.VOICE -> "语音·${record.text.ifBlank { "一段语音转写" }}"
                            RecordMode.TEXT -> "文字·${record.text}"
                        }.take(42)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, modifier = Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                widgetPrivacy.selectTodayRecord(record)
                                todayWidgetRecordId = record.id
                                coroutineScope.launch {
                                    WidgetUpdater.refreshGrowthWidgets(context.applicationContext)
                                    widgetPreview = widgetPrivacy.snapshot()
                                }
                            }) { Text(if (todayWidgetRecordId == record.id) "今日已选" else "给今日") }
                            TextButton(onClick = {
                                widgetPrivacy.selectMonthRecord(record)
                                monthWidgetRecordId = record.id
                                coroutineScope.launch {
                                    WidgetUpdater.refreshGrowthWidgets(context.applicationContext)
                                    widgetPreview = widgetPrivacy.snapshot()
                                }
                            }) { Text(if (monthWidgetRecordId == record.id) "本月已选" else "给本月") }
                        }
                    }
                    if (widgetRecords.isEmpty()) Text("还没有已保存的文字、语音或照片记录。", color = InkMuted)
                }
            }
        },
        confirmButton = { TextButton(onClick = { showWidgetPrivacy = false }) { Text("完成") } },
    )

    DisposableEffect(Unit) {
        onDispose {
            previewPlayer.stop()
            systemTts.stop()
            systemTts.shutdown()
        }
    }
    LaunchedEffect(ttsReady) {
        if (ttsReady) systemTts.language = Locale.SIMPLIFIED_CHINESE
    }

    fun previewVoice() {
        if (previewing) return
        previewing = true
        coroutineScope.launch {
            val previewText = "你好呀，我会陪你慢慢向前。"
            previewPlayer.stop()
            systemTts.stop()
            if (voicePreferences.duckCue) {
                previewPlayer.playDuckCue()
                delay(220)
            }
            val cloudVoice = voicePreferences.mode.cloudVoice
            if (cloudVoice == null) {
                if (ttsReady) systemTts.speak(previewText, TextToSpeech.QUEUE_FLUSH, null, "duck-preview")
            } else {
                runCatching { chatRepository.synthesizeSpeech(previewText, cloudVoice) }
                    .onSuccess(previewPlayer::play)
                    .onFailure {
                        if (ttsReady) systemTts.speak(previewText, TextToSpeech.QUEUE_FLUSH, null, "duck-preview-fallback")
                    }
            }
            previewing = false
        }
    }

    if (showVoiceSettings) {
        VoiceSettingsDialog(
            value = voicePreferences,
            onChange = onVoicePreferencesChange,
            previewing = previewing,
            onPreview = ::previewVoice,
            onDismiss = { showVoiceSettings = false },
        )
    }

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("我的", style = MaterialTheme.typography.displaySmall) }

        item {
            WarmCard {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DuckArt(R.drawable.duck_welcome, "小鸭头像", Modifier.size(118.dp))
                    Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(userName, style = MaterialTheme.typography.headlineMedium)
                        Text("小鸭正在慢慢了解你", style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showVoiceSettings = true },
                            shape = AppButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                        ) { Text("调整陪伴方式") }
                    }
                }
            }
        }

        item {
            WarmCard {
                Column {
                    FakeConfidenceRepository.settings.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .noRippleClickable(enabled = entry.title == "记忆中心" || entry.title == "支持圈" || entry.title == "隐私与权限" || entry.title == "数据导出" || entry.title == "帮助与求助资源") {
                                    when (entry.title) {
                                        "记忆中心" -> onOpenMemoryCenter()
                                        "支持圈" -> onOpenSupportCircle()
                                        "隐私与权限" -> {
                                            widgetAllowed = widgetPrivacy.isAllowed()
                                            widgetPreview = widgetPrivacy.snapshot()
                                            widgetRecords = localRecordRepository.load().filter { it.status == "saved" }
                                            todayWidgetRecordId = widgetPrivacy.selectedTodayRecordId()
                                            monthWidgetRecordId = widgetPrivacy.selectedMonthRecordId()
                                            showWidgetPrivacy = true
                                        }
                                        "数据导出" -> onOpenDataTools()
                                        "帮助与求助资源" -> {
                                            serverAddress = ServerEndpoint.current()
                                            serverStatus = "当前使用：${ServerEndpoint.current()}"
                                            showHelpResources = true
                                        }
                                    }
                                }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(46.dp).background(SagePale, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(entry.iconRes),
                                    contentDescription = entry.title,
                                    modifier = Modifier.size(25.dp),
                                )
                            }
                            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                Text(entry.subtitle, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                            }
                            Text("›", style = MaterialTheme.typography.titleLarge, color = InkMuted)
                        }
                        if (index < FakeConfidenceRepository.settings.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 18.dp),
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }

        item {
            TextButton(onClick = onOpenDataTools, modifier = Modifier.fillMaxWidth()) {
                Text("删除全部数据", color = Danger, fontWeight = FontWeight.SemiBold)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun VoiceSettingsDialog(
    value: VoicePreferences,
    onChange: (VoicePreferences) -> Unit,
    previewing: Boolean,
    onPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cream,
        shape = RoundedCornerShape(30.dp),
        title = { Text("小鸭声音") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DuckVoiceMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = value.mode == mode,
                            onClick = { onChange(value.copy(mode = mode)) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(mode.description, style = MaterialTheme.typography.bodySmall, color = InkMuted)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                SettingSwitchRow(
                    title = "自动朗读回复",
                    checked = value.autoPlay,
                    onCheckedChange = { onChange(value.copy(autoPlay = it)) },
                )
                SettingSwitchRow(
                    title = "小鸭提示音",
                    checked = value.duckCue,
                    onCheckedChange = { onChange(value.copy(duckCue = it)) },
                )
                Text(
                    "云端声音不可用时会自动使用手机系统声音。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkMuted,
                )
                TextButton(onClick = onPreview, enabled = !previewing) {
                    Text(if (previewing) "正在生成试听…" else "试听当前声音")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
