package com.testconnection.confidence_agent.ui.screens

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.data.remote.VideoScene
import com.testconnection.confidence_agent.data.remote.VideoScript
import com.testconnection.confidence_agent.data.remote.VideoScriptApiClient
import com.testconnection.confidence_agent.data.video.GrowthVideoRenderer
import com.testconnection.confidence_agent.data.video.VideoRenderScene
import com.testconnection.confidence_agent.data.repository.ChatRepository
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import com.testconnection.confidence_agent.ui.theme.SageDark
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val stageLabels = mapOf(
    "beginning" to "开始状态", "difficulty" to "遇到困难", "small_step" to "迈出一步",
    "help" to "获得帮助", "change" to "发生变化", "continuing" to "仍在继续",
)

private fun mediaDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 4_000L
    } finally {
        retriever.release()
    }
}

@Composable
fun VideoStudioScreen(events: List<GrowthEvent>, recordIds: Map<Long, String>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { VideoScriptApiClient() }
    val renderer = remember { GrowthVideoRenderer(context.applicationContext) }
    val chatRepository = remember { ChatRepository(context.applicationContext) }
    val localRecordRepository = remember { LocalRecordRepository(context.applicationContext) }
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    val candidates = events.filter { it.sensitivity == null || it.sensitivity == "low" }.take(40)
    var query by remember { mutableStateOf("") }
    var selectedIds by remember(candidates.map { it.id }) { mutableStateOf(emptySet<Long>()) }
    var script by remember { mutableStateOf<VideoScript?>(null) }
    var scenes by remember { mutableStateOf<List<VideoScene>>(emptyList()) }
    var video by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var voice by remember { mutableStateOf("Serena") }
    var keepOriginalVoice by remember { mutableStateOf(false) }
    val localRecords = remember(events, recordIds) { localRecordRepository.load().associateBy { it.id } }
    val recordForEvent: (Long) -> RecordDraft? = { eventId ->
        events.firstOrNull { it.id == eventId }?.sourceRecordId
            ?.let(recordIds::get)?.let(localRecords::get)
    }
    val searchResults = if (query.isBlank()) emptyList() else candidates.filter { event ->
        val searchable = listOfNotNull(event.fact, event.ownEffort, event.supportReceived)
            .plus(event.people).joinToString(" ")
        searchable.contains(query.trim(), ignoreCase = true)
    }.take(5)

    fun createScript() {
        if (selectedIds.size !in 3..5 || busy) return
        busy = true
        error = null
        video = null
        scope.launch {
            runCatching { api.create(deviceId, selectedIds.toList()) }
                .onSuccess { result -> script = result; scenes = result.scenes; notice = null }
                .onFailure { error = "脚本暂时无法生成，请检查服务后重试。" }
            busy = false
        }
    }

    fun exportVideo() {
        val current = script ?: return
        if (scenes.size !in 3..5 || busy) return
        busy = true
        error = null
        video = null
        scope.launch {
            val saved = runCatching { api.update(deviceId, current.id, scenes) }
            if (saved.isFailure) notice = "脚本修改暂未同步，视频仍会保存在本机。"
            else { script = saved.getOrNull(); notice = null }
            val temporaryAudio = mutableListOf<File>()
            val renderScenes = runCatching {
                var originalUsed = false
                val selectedRecords = selectedIds.mapNotNull(recordForEvent)
                val selectedPhotos = selectedRecords.filter { it.mode == RecordMode.PHOTO && it.photoPath?.let(::File)?.exists() == true }
                val selectedVoices = selectedRecords.filter { it.mode == RecordMode.VOICE && it.audioPath?.let(::File)?.exists() == true }
                scenes.mapIndexed { index, scene ->
                    val record = scene.sourceEventIds.firstNotNullOfOrNull(recordForEvent)
                    val visualRecord = record?.takeIf { it.mode == RecordMode.PHOTO && it.photoPath?.let(::File)?.exists() == true }
                        ?: selectedPhotos.getOrNull(index)
                    val voiceRecord = record?.takeIf { it.mode == RecordMode.VOICE && it.audioPath?.let(::File)?.exists() == true }
                        ?: selectedVoices.firstOrNull()
                    val original = if (
                        keepOriginalVoice && !originalUsed && voiceRecord != null
                    ) {
                        originalUsed = true
                        File(requireNotNull(voiceRecord.audioPath))
                    } else null
                    val audioFile = when {
                        original != null -> original
                        voice == "none" -> null
                        else -> File(context.cacheDir, "video-narration-${System.nanoTime()}-$index.wav").also { target ->
                            target.writeBytes(chatRepository.synthesizeSpeech(scene.text, voice))
                            temporaryAudio.add(target)
                        }
                    }
                    val fullAudioDuration = audioFile?.let(::mediaDurationMs)?.coerceAtLeast(1_000L) ?: 0L
                    val audioDuration = if (original != null) {
                        fullAudioDuration.coerceAtMost(5_000L)
                    } else {
                        fullAudioDuration.coerceAtMost(8_400L)
                    }
                    VideoRenderScene(
                        scene = scene,
                        photoPath = visualRecord?.photoPath,
                        annotation = visualRecord?.photoComment.orEmpty().ifBlank { visualRecord?.aiDescription.orEmpty() },
                        audioPath = audioFile?.absolutePath,
                        audioDurationMs = audioDuration,
                        durationMs = max(4_000L, audioDuration + 600L).coerceAtMost(9_000L),
                    )
                }
            }.getOrElse {
                temporaryAudio.forEach(File::delete)
                error = "配音生成失败，请检查网络，或选择无配音后重试。"
                busy = false
                return@launch
            }
            runCatching { renderer.render(renderScenes) }
                .onSuccess { video = it }
                .onFailure { error = "视频合成失败，请重试或减少片段。" }
            temporaryAudio.forEach(File::delete)
            busy = false
        }
    }

    fun shareVideo(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享成长小片"))
        }.onFailure { error = "暂时无法打开分享面板，请稍后重试。" }
    }

    val saveVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val source = video ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri).use { output ->
                        requireNotNull(output) { "无法写入所选位置" }
                        source.inputStream().use { it.copyTo(output) }
                    }
                }
            }.onSuccess { notice = "成长小片副本已保存。" }
                .onFailure { error = "保存失败，请重新选择位置。" }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("‹ 成长小片", modifier = Modifier.noRippleClickable(onClick = onBack), style = MaterialTheme.typography.displaySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("从本周报告里找出三到五个能连成故事的片段，不需要把整周都塞进视频。",
                    modifier = Modifier.weight(1f))
                DuckArt(R.drawable.duck_story_picker, "挑选故事片段的小鸭", Modifier.height(112.dp))
            }
        }
        item {
            WarmCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("搜索故事素材", style = MaterialTheme.typography.titleLarge)
                    if (candidates.isEmpty()) Text("正在读取本周的原始记录。如果稍后仍未出现，请返回成长页重试。")
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("搜索关键词") },
                        placeholder = { Text("例如：汇报、紧张、老师、重新开始") },
                    )
                    if (query.isBlank() && candidates.isNotEmpty()) {
                        Text("输入关键词后，最多显示 5 条相关结果。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (searchResults.isEmpty() && candidates.isNotEmpty()) {
                        Text("没有找到相关片段，换一个更短的关键词试试。")
                    }
                    if (searchResults.isNotEmpty()) {
                        Text("请勾选至少 3 条；可以更换关键词继续查找，已勾选的不会丢失。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                selectedIds = searchResults.map { it.id }.toSet()
                                script = null; scenes = emptyList(); video = null
                            }) { Text("全选结果") }
                            TextButton(onClick = {
                                selectedIds = emptySet()
                                script = null; scenes = emptyList(); video = null
                            }) { Text("全部不选") }
                            Text("已选 ${selectedIds.size}/5", modifier = Modifier.align(Alignment.CenterVertically), color = SageDark)
                        }
                    }
                    searchResults.forEach { event ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = event.id in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked && selectedIds.size < 5) selectedIds + event.id
                                    else if (!checked) selectedIds - event.id else selectedIds
                                    script = null; scenes = emptyList(); video = null
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(event.fact, style = MaterialTheme.typography.bodyMedium)
                                recordForEvent(event.id)?.let { record ->
                                    Text(
                                        when (record.mode) {
                                            RecordMode.PHOTO -> "含真实照片"
                                            RecordMode.VOICE -> "含可选原声"
                                            RecordMode.TEXT -> "文字记录"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SageDark,
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = ::createScript,
                        enabled = selectedIds.size in 3..5 && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (selectedIds.size < 3) "还需选择 ${3 - selectedIds.size} 条" else "用已选片段生成故事脚本")
                    }
                    if (selectedIds.size in 1..2) Text("至少选择三条，故事才有开始、变化和继续。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (script != null) item {
            WarmCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("预览并删改脚本", style = MaterialTheme.typography.titleLarge)
                    Text(if (script?.mock == true) "当前使用可编辑模板；未调用 AI。" else "已生成可编辑脚本，分享前请确认每一句都准确。")
                    Text("配音", style = MaterialTheme.typography.titleMedium)
                    listOf("Serena" to "温柔女声", "Ethan" to "温暖男声", "none" to "无配音").forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = voice == option.first, onClick = { voice = option.first; video = null })
                            Text(option.second)
                        }
                    }
                    val hasOriginalVoice = selectedIds.mapNotNull(recordForEvent).any {
                        it.mode == RecordMode.VOICE && it.audioPath?.let(::File)?.exists() == true
                    }
                    if (hasOriginalVoice) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = keepOriginalVoice,
                                onCheckedChange = { keepOriginalVoice = it; video = null },
                            )
                            Text("保留一小段用户原声（最多 5 秒）")
                        }
                    }
                    scenes.forEachIndexed { index, scene ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stageLabels[scene.stage] ?: scene.stage, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                if (scenes.size > 3) scenes = scenes.filterIndexed { i, _ -> i != index }
                            }, enabled = scenes.size > 3) { Text("移除") }
                        }
                        OutlinedTextField(
                            value = scene.text,
                            onValueChange = { value -> if (value.length <= 120) scenes = scenes.toMutableList().also { it[index] = scene.copy(text = value) } },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    }
                    Text("${scenes.size} 个片段 · 配音时长会自动决定每段画面停留时间")
                    Button(onClick = ::exportVideo, enabled = !busy && scenes.size >= 3 && scenes.all { it.text.isNotBlank() }) { Text("生成并预览成长小片") }
                }
            }
        }
        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Text(if (script == null) "正在生成脚本…" else "正在准备照片、配音并合成视频…")
        } }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        notice?.let { message -> item { Text(message, color = SageDark) } }
        video?.let { file -> item {
            WarmCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("小片已保存在本机", style = MaterialTheme.typography.titleLarge)
                    AndroidView(
                        factory = { VideoView(it).apply { setVideoPath(file.absolutePath); setOnPreparedListener { player -> player.isLooping = true; start() } } },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { saveVideo.launch("小丑鸭成长小片-${System.currentTimeMillis()}.mp4") },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存副本") }
                        OutlinedButton(onClick = { shareVideo(file) }, modifier = Modifier.weight(1f)) { Text("系统分享") }
                    }
                }
            }
        } }
    }
}
