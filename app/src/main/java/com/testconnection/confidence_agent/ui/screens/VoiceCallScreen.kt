package com.testconnection.confidence_agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.audio.AudioReplyPlayer
import com.testconnection.confidence_agent.data.audio.WavAudioRecorder
import com.testconnection.confidence_agent.data.preferences.DuckVoiceMode
import com.testconnection.confidence_agent.data.preferences.VoicePreferences
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.CreamDeep
import com.testconnection.confidence_agent.ui.theme.Danger
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.TerracottaPale
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VoiceCallScreen(
    state: HomeUiState,
    viewModel: HomeViewModel,
    voicePreferences: VoicePreferences,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val recorder = remember { WavAudioRecorder(context.applicationContext) }
    val replyPlayer = remember { AudioReplyPlayer(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()
    var isRecording by rememberSaveable { mutableStateOf(false) }
    var showTranscript by rememberSaveable { mutableStateOf(true) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            replyPlayer.stop()
            tts.stop()
            tts.shutdown()
        }
    }

    LaunchedEffect(ttsReady) {
        if (ttsReady) tts.language = Locale.SIMPLIFIED_CHINESE
    }
    suspend fun speakReply(reply: String, utteranceId: String) {
        replyPlayer.stop()
        tts.stop()
        if (voicePreferences.duckCue) {
            replyPlayer.playDuckCue()
            delay(220)
        }
        val cloudVoice = voicePreferences.mode.cloudVoice
        if (cloudVoice == null) {
            repeat(10) {
                if (ttsReady) return@repeat
                delay(100)
            }
            if (ttsReady) tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            return
        }
        runCatching { viewModel.synthesizeSpeech(reply, cloudVoice) }
            .onSuccess(replyPlayer::play)
            .onFailure {
                if (ttsReady) tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "$utteranceId-fallback")
            }
    }

    LaunchedEffect(state.voiceTurnId) {
        val reply = state.lastVoiceReply
        if (state.voiceTurnId > 0 && voicePreferences.autoPlay && !reply.isNullOrBlank()) {
            speakReply(reply, "duck-${state.voiceTurnId}")
        }
    }

    fun startRecording() {
        runCatching { recorder.start() }
            .onSuccess {
                localMessage = null
                viewModel.clearError()
                isRecording = true
            }
            .onFailure { localMessage = it.message ?: "无法启动麦克风" }
    }

    fun stopAndSend() {
        val file = recorder.stop()
        isRecording = false
        if (file == null || file.length() <= 44) {
            localMessage = "没有录到声音，请再试一次。"
            file?.delete()
        } else {
            viewModel.sendAudio(file)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startRecording() else localMessage = "需要麦克风权限才能进行语音对话。"
    }

    fun toggleRecording() {
        if (state.sending) return
        if (isRecording) {
            stopAndSend()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun closeScreen() {
        if (isRecording) {
            recorder.cancel()
            isRecording = false
        }
        onClose()
    }

    BackHandler(onBack = ::closeScreen)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Cream, TerracottaPale.copy(alpha = 0.55f), SagePale.copy(alpha = 0.8f)),
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("•••", style = MaterialTheme.typography.titleLarge)
            Surface(color = CreamDeep, shape = RoundedCornerShape(50)) {
                Text("和小鸭聊一会儿", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
            Surface(onClick = { showTranscript = !showTranscript }, color = Color.Transparent) {
                Text("字", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(8.dp))
            }
        }

        Spacer(Modifier.height(36.dp))
        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Cream, TerracottaPale, SagePale),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            DuckArt(
                drawable = if (isRecording) R.drawable.duck_listening else R.drawable.duck_welcome,
                description = "陪你语音聊天的小鸭",
                modifier = Modifier.size(205.dp),
            )
        }

        Spacer(Modifier.height(28.dp))
        if (showTranscript && (!state.lastVoiceTranscript.isNullOrBlank() || !state.lastVoiceReply.isNullOrBlank())) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Cream.copy(alpha = 0.85f),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.lastVoiceTranscript?.let { Text("你：$it", style = MaterialTheme.typography.bodyLarge) }
                    state.lastVoiceReply?.let { Text("小鸭：$it", style = MaterialTheme.typography.bodyLarge, color = SageDark) }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        if (state.sending) {
            CircularProgressIndicator(color = SageDark, strokeWidth = 3.dp)
            Text("小鸭正在听懂你…", modifier = Modifier.padding(top = 12.dp), color = SageDark)
        } else {
            Text(
                text = when {
                    isRecording -> "正在听，点一下结束"
                    localMessage != null -> localMessage!!
                    state.error != null -> state.error
                    else -> "点一下麦克风，和小鸭说说吧"
                },
                textAlign = TextAlign.Center,
                color = if (localMessage != null || state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceControl(
                label = if (isRecording) "■" else "●",
                background = if (isRecording) TerracottaPale else CreamDeep,
                onClick = ::toggleRecording,
            )
            VoiceControl(label = "▶", background = CreamDeep) {
                state.lastVoiceReply?.takeIf { it.isNotBlank() }?.let { reply ->
                    coroutineScope.launch { speakReply(reply, "duck-replay-${state.voiceTurnId}") }
                }
            }
            VoiceControl(label = "×", background = Danger.copy(alpha = 0.14f), tint = Danger, onClick = ::closeScreen)
        }
    }
}

@Composable
private fun VoiceControl(
    label: String,
    background: Color,
    tint: Color = SageDark,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(76.dp),
        shape = CircleShape,
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = tint, style = MaterialTheme.typography.headlineMedium)
        }
    }
}
