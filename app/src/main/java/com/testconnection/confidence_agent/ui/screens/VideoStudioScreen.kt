package com.testconnection.confidence_agent.ui.screens

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.data.remote.ReviewApiClient
import com.testconnection.confidence_agent.data.remote.VideoKeywordSuggestion
import com.testconnection.confidence_agent.data.remote.VideoRenderAsset
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val stageLabels = mapOf(
    "beginning" to "开始状态", "difficulty" to "遇到困难", "small_step" to "迈出一步",
    "help" to "获得帮助", "change" to "发生变化", "continuing" to "仍在继续",
)

private val videoSearchSeparators = Regex("[\\s,，、;；/|]+")

private fun videoSearchTerms(query: String): List<String> = query
    .split(videoSearchSeparators)
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy { it.lowercase() }

private val localVideoTopicRules = listOf(
    "学习备考" to listOf("考研", "备考", "考试", "院校", "学习", "辅导课", "看书"),
    "图书馆学习" to listOf("图书馆", "自习室", "自习"),
    "做题与复习" to listOf("做题", "刷题", "错题", "复习", "题目", "改错"),
    "压力与不自信" to listOf("压力", "焦虑", "紧张", "忐忑", "不自信", "怀疑", "考不上"),
    "想放弃" to listOf("放弃", "不想学", "没毅力", "坚持不下"),
    "家人支持" to listOf("父母", "家人", "爸爸", "妈妈", "家里"),
    "师兄师姐" to listOf("师兄", "师姐", "学长", "学姐"),
    "老师指导" to listOf("老师", "导师", "学院"),
    "运动与户外" to listOf("运动", "健身", "慢跑", "操场", "散步", "户外", "公园"),
    "休息与调整" to listOf("休息", "睡觉", "熬夜", "调整", "方法"),
    "沟通与求助" to listOf("沟通", "聊天", "求助", "倾诉", "告诉", "商量"),
    "鼓励与陪伴" to listOf("小鸭", "安慰", "鼓励", "陪伴", "支持"),
    "计划与行动" to listOf("计划", "开始", "行动", "完成", "继续", "坚持", "重新"),
    "工作与项目" to listOf("工作", "汇报", "答辩", "项目", "代码", "小组"),
)

private fun fallbackVideoKeywordSuggestions(events: List<GrowthEvent>): List<VideoKeywordSuggestion> {
    val matched = localVideoTopicRules.mapNotNull { (label, terms) ->
        val ids = events.filter { event ->
            val text = (listOfNotNull(event.fact, event.ownEffort, event.supportReceived) + event.people)
                .joinToString(" ")
            terms.any { term -> text.contains(term) }
        }.map { it.id }
        ids.takeIf { it.isNotEmpty() }?.let { VideoKeywordSuggestion(label, it) }
    }
    val suggestions = matched.take(10).ifEmpty {
        events.takeIf { it.isNotEmpty() }
            ?.let { listOf(VideoKeywordSuggestion("本周记录", it.map { event -> event.id })) }
            .orEmpty()
    }
    val covered = suggestions.flatMap { it.eventIds }.toSet()
    val missing = events.map { it.id }.filterNot(covered::contains)
    return if (missing.isEmpty()) suggestions else {
        val retained = suggestions.take(9)
        val retainedIds = retained.flatMap { it.eventIds }.toSet()
        retained + VideoKeywordSuggestion("其他记录", events.map { it.id }.filterNot(retainedIds::contains))
    }
}

private fun mediaDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 4_000L
    } finally {
        retriever.release()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoStudioScreen(
    events: List<GrowthEvent>,
    sourceEventIds: Set<Long>,
    recordIds: Map<Long, String>,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { VideoScriptApiClient() }
    val reviewApi = remember { ReviewApiClient() }
    val renderer = remember { GrowthVideoRenderer(context.applicationContext) }
    val chatRepository = remember { ChatRepository(context.applicationContext) }
    val localRecordRepository = remember { LocalRecordRepository(context.applicationContext) }
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    var sourceEvents by remember(sourceEventIds) {
        mutableStateOf(events.filter { it.id in sourceEventIds })
    }
    var resolvedRecordIds by remember(sourceEventIds) { mutableStateOf(recordIds) }
    var sourceEventsLoading by remember(sourceEventIds) {
        mutableStateOf(sourceEventIds.isNotEmpty() && sourceEvents.isEmpty())
    }
    var sourceEventsError by remember(sourceEventIds) { mutableStateOf<String?>(null) }
    var sourceReloadToken by remember { mutableIntStateOf(0) }
    val candidates = sourceEvents.filter { it.sensitivity != "high" }.take(40)
    val fallbackKeywordSuggestions = remember(candidates.map { it.id }) {
        fallbackVideoKeywordSuggestions(candidates)
    }
    var query by remember { mutableStateOf("") }
    var keywordSuggestions by remember(candidates.map { it.id }) { mutableStateOf(fallbackKeywordSuggestions) }
    var selectedSuggestionLabels by remember(candidates.map { it.id }) { mutableStateOf(emptySet<String>()) }
    var keywordSuggestionsLoading by remember(candidates.map { it.id }) { mutableStateOf(candidates.isNotEmpty()) }
    var keywordSuggestionsUseAi by remember(candidates.map { it.id }) { mutableStateOf(false) }
    var selectedIds by remember(candidates.map { it.id }) { mutableStateOf(emptySet<Long>()) }
    var script by remember { mutableStateOf<VideoScript?>(null) }
    var scenes by remember { mutableStateOf<List<VideoScene>>(emptyList()) }
    var video by remember { mutableStateOf<File?>(null) }
    var showFullscreenVideo by remember { mutableStateOf(false) }
    var inlineVideoView by remember { mutableStateOf<VideoView?>(null) }
    var fullscreenVideoView by remember { mutableStateOf<VideoView?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var voice by remember { mutableStateOf("Serena") }
    var keepOriginalVoice by remember { mutableStateOf(false) }
    val localRecords = remember(sourceEvents, resolvedRecordIds) { localRecordRepository.load().associateBy { it.id } }
    val recordForEvent: (Long) -> RecordDraft? = { eventId ->
        sourceEvents.firstOrNull { it.id == eventId }?.sourceRecordId
            ?.let(resolvedRecordIds::get)?.let(localRecords::get)
    }
    fun closeFullscreenVideo() {
        fullscreenVideoView?.runCatching { stopPlayback() }
        fullscreenVideoView = null
        showFullscreenVideo = false
    }
    DisposableEffect(Unit) {
        onDispose {
            inlineVideoView?.runCatching { stopPlayback() }
            fullscreenVideoView?.runCatching { stopPlayback() }
        }
    }
    LaunchedEffect(deviceId, sourceEventIds, events.map { it.id }, recordIds, sourceReloadToken) {
        val passedEvents = events.filter { it.id in sourceEventIds }
        sourceEvents = passedEvents
        resolvedRecordIds = recordIds
        if (sourceEventIds.isEmpty()) {
            sourceEventsLoading = false
            sourceEventsError = "这份周报告没有可用于视频的来源记录。"
            return@LaunchedEffect
        }
        val missingEventIds = sourceEventIds - passedEvents.map { it.id }.toSet()
        val recordServerIds = passedEvents.mapNotNull { it.sourceRecordId }.toSet()
        val missingRecordMapping = recordServerIds.any { it !in recordIds }
        if (missingEventIds.isEmpty() && !missingRecordMapping) {
            sourceEventsLoading = false
            sourceEventsError = null
            return@LaunchedEffect
        }
        sourceEventsLoading = true
        sourceEventsError = null
        val remoteEvents = if (missingEventIds.isEmpty()) {
            passedEvents
        } else {
            runCatching { reviewApi.events(deviceId) }.getOrNull()
                ?.filter { it.id in sourceEventIds }
                .orEmpty()
        }
        val syncedRecords = runCatching {
            reviewApi.syncRecords(deviceId, localRecordRepository.load())
        }.getOrNull().orEmpty()
        sourceEvents = (passedEvents + remoteEvents).distinctBy { it.id }
        if (syncedRecords.isNotEmpty()) {
            resolvedRecordIds = recordIds + syncedRecords.associate { it.serverId to it.clientId }
        }
        sourceEventsLoading = false
        sourceEventsError = if (sourceEvents.isEmpty()) {
            "本周素材暂时读取失败，请检查后端后重试。"
        } else null
    }
    LaunchedEffect(deviceId, candidates.map { it.id }) {
        if (candidates.isEmpty()) return@LaunchedEffect
        keywordSuggestionsLoading = true
        val result = runCatching { api.suggestKeywords(deviceId, candidates.map { it.id }) }.getOrNull()
        val received = result?.suggestions?.ifEmpty { fallbackKeywordSuggestions }
            ?: fallbackKeywordSuggestions
        keywordSuggestions = if (selectedSuggestionLabels.isEmpty()) {
            received
        } else {
            (keywordSuggestions.filter { it.label in selectedSuggestionLabels } + received)
                .distinctBy { it.label }
                .take(10)
        }
        keywordSuggestionsUseAi = result?.model != null
        keywordSuggestionsLoading = false
    }
    val selectableKeywordSuggestions = if (candidates.isEmpty()) emptyList() else {
        listOf(VideoKeywordSuggestion("全部素材", candidates.map { it.id })) +
            keywordSuggestions.filterNot { it.label == "全部素材" }
    }
    val searchTerms = videoSearchTerms(query)
    val activeSuggestions = selectableKeywordSuggestions.filter { it.label in selectedSuggestionLabels }
    val searchResults = if (searchTerms.isEmpty() && activeSuggestions.isEmpty()) emptyList() else candidates.map { event ->
        val searchableFields = listOfNotNull(event.fact, event.ownEffort, event.supportReceived) + event.people
        val keywordScore = searchTerms.sumOf { term ->
            val matchedFields = searchableFields.count { it.contains(term, ignoreCase = true) }
            if (matchedFields == 0) 0 else 10 + matchedFields
        }
        val topicScore = activeSuggestions.count { event.id in it.eventIds } * 20
        val score = keywordScore + topicScore
        event to score
    }.filter { (_, score) -> score > 0 }
        .sortedByDescending { (_, score) -> score }
        .take(20)
        .map { (event, _) -> event }

    fun createScript() {
        if (selectedIds.size !in 3..7 || busy) return
        busy = true
        progressText = "正在生成故事脚本…"
        error = null
        video = null
        scope.launch {
            runCatching { api.create(deviceId, selectedIds.toList()) }
                .onSuccess { result -> script = result; scenes = result.scenes; notice = null }
                .onFailure { failure -> error = failure.message ?: "脚本暂时无法生成，请检查服务后重试。" }
            busy = false
            progressText = ""
        }
    }

    fun exportVideo() {
        val current = script ?: return
        if (scenes.size !in 3..7 || busy) return
        busy = true
        progressText = "正在保存脚本…"
        error = null
        video = null
        scope.launch {
            val saved = runCatching { api.update(deviceId, current.id, scenes) }
            if (saved.isFailure) notice = "脚本修改暂未同步，视频仍会保存在本机。"
            else { script = saved.getOrNull(); notice = null }
            val temporaryAudio = mutableListOf<File>()
            val renderScenes = runCatching {
                scenes.mapIndexed { index, scene ->
                    progressText = if (voice == "none") {
                        "正在准备第 ${index + 1}/${scenes.size} 段画面…"
                    } else {
                        "正在生成第 ${index + 1}/${scenes.size} 段配音…"
                    }
                    val record = scene.sourceEventIds.firstNotNullOfOrNull(recordForEvent)
                    val visualRecord = record?.takeIf { it.mode == RecordMode.PHOTO && it.photoPath?.let(::File)?.exists() == true }
                    val voiceRecord = record?.takeIf { it.mode == RecordMode.VOICE && it.audioPath?.let(::File)?.exists() == true }
                    val narration = if (voice == "none") null else {
                        val target = File(context.cacheDir, "video-narration-${System.nanoTime()}-$index.wav")
                        val audio = chatRepository.synthesizeSpeech(scene.text, voice)
                        withContext(Dispatchers.IO) { target.writeBytes(audio) }
                        target.also {
                            temporaryAudio.add(it)
                        }
                    }
                    val original = if (keepOriginalVoice && voiceRecord != null) {
                        File(requireNotNull(voiceRecord.audioPath))
                    } else null
                    val narrationDuration = withContext(Dispatchers.IO) {
                        narration?.let(::mediaDurationMs)?.coerceAtLeast(1_000L) ?: 0L
                    }
                    val originalDuration = withContext(Dispatchers.IO) {
                        original?.let(::mediaDurationMs)?.coerceAtLeast(1_000L)?.coerceAtMost(5_000L) ?: 0L
                    }
                    val interAudioGap = if (narrationDuration > 0 && originalDuration > 0) 250L else 0L
                    val spokenDuration = narrationDuration + interAudioGap + originalDuration
                    val silentReadingDuration = (scene.text.length * 150L).coerceIn(4_000L, 18_000L)
                    VideoRenderScene(
                        scene = scene,
                        photoPath = visualRecord?.photoPath,
                        annotation = visualRecord?.photoComment.orEmpty().ifBlank { visualRecord?.aiDescription.orEmpty() },
                        narrationPath = narration?.absolutePath,
                        narrationDurationMs = narrationDuration,
                        originalVoicePath = original?.absolutePath,
                        originalVoiceDurationMs = originalDuration,
                        durationMs = max(4_000L, if (spokenDuration > 0) spokenDuration + 800L else silentReadingDuration),
                        isDemo = sourceEvents.any { it.id in scene.sourceEventIds && it.sourceType == "demo" },
                    )
                }
            }.getOrElse {
                temporaryAudio.forEach(File::delete)
                error = "配音生成失败，请检查网络，或选择无配音后重试。"
                busy = false
                progressText = ""
                return@launch
            }
            progressText = "正在绘制 ${renderScenes.size} 个画面…"
            val frames = runCatching { renderer.createFrames(renderScenes) }.getOrElse {
                temporaryAudio.forEach(File::delete)
                error = "视频画面生成失败，请重试。"
                busy = false
                progressText = ""
                return@launch
            }
            progressText = "正在上传素材并由电脑合成视频…"
            runCatching {
                withTimeout(3 * 60_000L) {
                    val assets = renderScenes.mapIndexed { index, scene ->
                        VideoRenderAsset(
                            frame = frames[index],
                            durationMs = scene.durationMs,
                            narration = scene.narrationPath?.let(::File),
                            narrationDurationMs = scene.narrationDurationMs,
                            originalVoice = scene.originalVoicePath?.let(::File),
                        )
                    }
                    val videoBytes = api.render(deviceId, current.id, assets)
                    val directory = File(context.filesDir, "generated_videos").apply { mkdirs() }
                    File(directory, "growth-${System.currentTimeMillis()}.mp4").also { output ->
                        withContext(Dispatchers.IO) { output.writeBytes(videoBytes) }
                        require(output.length() > 0) { "后端返回了空视频" }
                    }
                }
            }
                .onSuccess { video = it }
                .onFailure { failure ->
                    error = if (failure is TimeoutCancellationException) {
                        "电脑端视频合成超过 3 分钟，已停止。请检查后端窗口后重试。"
                    } else {
                        failure.message ?: "电脑端视频合成失败，请重试。"
                    }
                }
            frames.forEach(File::delete)
            temporaryAudio.forEach(File::delete)
            busy = false
            progressText = ""
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

    val fullscreenFile = video
    if (showFullscreenVideo && fullscreenFile != null) {
        Dialog(
            onDismissRequest = ::closeFullscreenVideo,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { viewContext ->
                        VideoView(viewContext).apply {
                            fullscreenVideoView = this
                            val controls = MediaController(viewContext)
                            controls.setAnchorView(this)
                            setMediaController(controls)
                            setVideoPath(fullscreenFile.absolutePath)
                            setOnPreparedListener { player -> player.isLooping = true; start() }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                TextButton(
                    onClick = ::closeFullscreenVideo,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                ) { Text("关闭", color = Color.White) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("‹ 成长小片", modifier = Modifier.noRippleClickable(onClick = onBack), style = MaterialTheme.typography.displaySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("从本周报告里找出三到七个能连成故事的片段，不需要把整周都塞进视频。",
                    modifier = Modifier.weight(1f))
                DuckArt(R.drawable.duck_story_picker, "挑选故事片段的小鸭", Modifier.height(112.dp))
            }
        }
        item {
            WarmCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("挑选故事素材", style = MaterialTheme.typography.titleLarge)
                    if (candidates.isEmpty()) {
                        when {
                            sourceEventsLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                                Text("正在读取本周的原始记录…")
                            }
                            sourceEventsError != null -> {
                                Text(requireNotNull(sourceEventsError), color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = { sourceReloadToken++ }) { Text("重新读取素材") }
                            }
                            else -> Text("本周没有可用于分享的记录。")
                        }
                    }
                    if (selectableKeywordSuggestions.isNotEmpty()) {
                        Text("先选推荐主题", style = MaterialTheme.typography.titleMedium)
                        Text("本周共有 ${candidates.size} 条可选素材。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            selectableKeywordSuggestions.forEach { suggestion ->
                                FilterChip(
                                    selected = suggestion.label in selectedSuggestionLabels,
                                    onClick = {
                                        selectedSuggestionLabels = if (suggestion.label in selectedSuggestionLabels) {
                                            selectedSuggestionLabels - suggestion.label
                                        } else {
                                            selectedSuggestionLabels + suggestion.label
                                        }
                                    },
                                    label = { Text("${suggestion.label}（${suggestion.eventIds.size}）") },
                                )
                            }
                        }
                        Text(
                            when {
                                keywordSuggestionsLoading -> "正在继续整理主题，当前推荐可以先选。"
                                keywordSuggestionsUseAi -> "已根据本周内容智能归类；推荐里没有的，再到下面补充搜索。"
                                else -> "已根据本周内容自动归类；推荐里没有的，再到下面补充搜索。"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("补充搜索") },
                        placeholder = { Text("例如：考研，图书馆，不自信") },
                    )
                    if (query.isBlank() && selectedSuggestionLabels.isEmpty() && candidates.isNotEmpty()) {
                        Text("点选一个或多个推荐主题即可看到素材；也可以直接输入多个关键词。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (searchResults.isEmpty() && candidates.isNotEmpty()) {
                        Text("没有找到相关片段，换成更短的词或同义表达试试。")
                    }
                    if (searchResults.isNotEmpty()) {
                        Text("找到 ${searchResults.size} 条，已按主题和关键词匹配度排序；视频最多选择 7 条。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val additional = searchResults.map { it.id }
                                    .filterNot(selectedIds::contains)
                                    .take((7 - selectedIds.size).coerceAtLeast(0))
                                selectedIds = selectedIds + additional
                                script = null; scenes = emptyList(); video = null
                            }) { Text("全选结果") }
                            TextButton(onClick = {
                                selectedIds = emptySet()
                                script = null; scenes = emptyList(); video = null
                            }) { Text("全部不选") }
                            Text("已选 ${selectedIds.size}/7", modifier = Modifier.align(Alignment.CenterVertically), color = SageDark)
                        }
                    }
                    searchResults.forEach { event ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = event.id in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked && selectedIds.size < 7) selectedIds + event.id
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
                                if (event.sensitivity == "medium") {
                                    Text(
                                        "这条内容较私密，请确认后再加入分享视频",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        onClick = ::createScript,
                        enabled = selectedIds.size in 3..7 && !busy,
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
                            Text("每段 AI 旁白后播放对应原声（每段最多 5 秒）")
                        }
                    }
                    scenes.forEachIndexed { index, scene ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(scene.date, stageLabels[scene.stage] ?: scene.stage).joinToString(" · "),
                                modifier = Modifier.weight(1f),
                            )
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
                    Button(onClick = ::exportVideo, enabled = !busy && scenes.size in 3..7 && scenes.all { it.text.isNotBlank() }) { Text("生成并预览成长小片") }
                }
            }
        }
        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Text(progressText.ifBlank { if (script == null) "正在生成脚本…" else "正在准备成长小片…" })
        } }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        notice?.let { message -> item { Text(message, color = SageDark) } }
        video?.let { file -> item {
            WarmCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("小片已保存在本机", style = MaterialTheme.typography.titleLarge)
                    AndroidView(
                        factory = {
                            VideoView(it).apply {
                                inlineVideoView = this
                                setVideoPath(file.absolutePath)
                                setOnPreparedListener { player -> player.isLooping = true; start() }
                                setOnClickListener {
                                    pause()
                                    showFullscreenVideo = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                    )
                    Text("点击视频可全屏播放", color = SageDark)
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
