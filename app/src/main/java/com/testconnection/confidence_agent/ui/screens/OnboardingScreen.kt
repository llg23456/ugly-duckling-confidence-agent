package com.testconnection.confidence_agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.audio.AudioReplyPlayer
import com.testconnection.confidence_agent.data.audio.WavAudioRecorder
import com.testconnection.confidence_agent.data.model.UserProfile
import com.testconnection.confidence_agent.data.preferences.VoicePreferences
import com.testconnection.confidence_agent.data.repository.ChatRepository
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.TerracottaPale
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    voicePreferences: VoicePreferences,
    initialProfile: UserProfile = UserProfile(),
    onComplete: (UserProfile) -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { WavAudioRecorder(context.applicationContext) }
    val repository = remember { ChatRepository() }
    val player = remember { AudioReplyPlayer(context.applicationContext) }
    var profile by remember { mutableStateOf(initialProfile) }
    var answer by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("先简单给小鸭介绍一下你吧，主人。想到什么就说什么，不用一次说完整。") }
    var isRecording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    val systemTts = remember {
        TextToSpeech(context.applicationContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            player.stop()
            systemTts.stop()
            systemTts.shutdown()
        }
    }
    LaunchedEffect(ttsReady) { if (ttsReady) systemTts.language = Locale.SIMPLIFIED_CHINESE }

    fun speak(text: String) {
        scope.launch {
            val voice = voicePreferences.mode.cloudVoice
            if (voice == null) {
                if (ttsReady) systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "onboarding")
            } else {
                runCatching { repository.synthesizeSpeech(text, voice) }
                    .onSuccess(player::play)
                    .onFailure { if (ttsReady) systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "onboarding-fallback") }
            }
        }
    }

    fun submit() {
        val text = answer.trim()
        if (text.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { repository.analyzeOnboarding(text, profile) }
                .onSuccess { result ->
                    profile = result.profile
                    answer = ""
                    if (result.complete) {
                        onComplete(result.profile)
                    } else {
                        prompt = result.followUp ?: "还有什么想让我了解的吗？"
                        speak(prompt)
                    }
                }
                .onFailure { error = it.message ?: "暂时没有整理清楚，请再说一次。" }
            busy = false
        }
    }

    fun startRecording() {
        runCatching { recorder.start() }
            .onSuccess { isRecording = true; error = null }
            .onFailure { error = it.message ?: "无法启动麦克风" }
    }
    fun stopRecording() {
        val file = recorder.stop()
        isRecording = false
        if (file == null || file.length() <= 44) {
            file?.delete()
            error = "没有录到声音，请再试一次。"
            return
        }
        busy = true
        scope.launch {
            runCatching { repository.transcribe(file) }
                .onSuccess { answer = it }
                .onFailure { error = it.message ?: "没有听清" }
            file.delete()
            busy = false
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = "需要麦克风权限，也可以直接输入文字。"
    }
    fun toggleRecording() {
        if (busy) return
        if (isRecording) stopRecording()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Cream, SagePale.copy(alpha = 0.7f), TerracottaPale.copy(alpha = 0.45f))))
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSkip) { Text("暂时跳过", color = SageDark) }
        }
        DuckArt(R.drawable.duck_listening, "正在认识你的小鸭", Modifier.size(210.dp))
        Text("第一次见面", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Surface(color = Cream.copy(alpha = 0.9f), shape = RoundedCornerShape(24.dp)) {
            Text(prompt, modifier = Modifier.padding(18.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            modifier = Modifier.fillMaxWidth().height(130.dp),
            placeholder = { Text("语音会先转成文字，你也可以直接输入或修改。") },
            shape = RoundedCornerShape(22.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.weight(1f))
        if (busy) CircularProgressIndicator(color = SageDark)
        else Box(contentAlignment = Alignment.Center) {
            Button(
                onClick = ::toggleRecording,
                modifier = Modifier.size(76.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) TerracottaPale else SageDark),
            ) { Text(if (isRecording) "结束" else "说话") }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = ::submit,
            enabled = answer.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = AppButtonShape,
        ) { Text("让小鸭了解这些") }
    }
}
