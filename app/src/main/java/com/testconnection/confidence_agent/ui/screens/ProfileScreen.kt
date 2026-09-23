package com.testconnection.confidence_agent.ui.screens

import android.speech.tts.TextToSpeech
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.audio.AudioReplyPlayer
import com.testconnection.confidence_agent.data.preferences.DuckVoiceMode
import com.testconnection.confidence_agent.data.preferences.VoicePreferences
import com.testconnection.confidence_agent.data.repository.ChatRepository
import com.testconnection.confidence_agent.data.repository.FakeConfidenceRepository
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.Danger
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    contentPadding: PaddingValues,
    voicePreferences: VoicePreferences,
    onVoicePreferencesChange: (VoicePreferences) -> Unit,
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
                        Text("林林", style = MaterialTheme.typography.headlineMedium)
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
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(46.dp).background(SagePale, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(entry.symbol, color = SageDark, style = MaterialTheme.typography.titleLarge)
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
            TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
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
