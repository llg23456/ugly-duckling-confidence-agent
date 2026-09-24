package com.testconnection.confidence_agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.audio.WavAudioRecorder
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.data.repository.ChatRepository
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.SectionHeading
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.WarmWhite
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import com.testconnection.confidence_agent.widget.WidgetUpdater

@Composable
fun RecordScreen(
    contentPadding: PaddingValues,
    requestedMode: RecordMode = RecordMode.TEXT,
    cameraLaunchToken: Int = 0,
    onRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localRepository = remember { LocalRecordRepository(context.applicationContext) }
    val chatRepository = remember { ChatRepository() }
    val recorder = remember { WavAudioRecorder(context.applicationContext) }
    var mode by remember { mutableStateOf(requestedMode) }
    var note by remember { mutableStateOf("") }
    var photoComment by remember { mutableStateOf("") }
    var photoPath by remember { mutableStateOf<String?>(null) }
    var voiceTempFile by remember { mutableStateOf<File?>(null) }
    var records by remember { mutableStateOf(localRepository.load()) }
    var selectedRecord by remember { mutableStateOf<RecordDraft?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var handledCameraToken by remember { mutableIntStateOf(0) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun acceptPhoto(uri: Uri) {
        runCatching { localRepository.importPhoto(uri) }
            .onSuccess {
                photoPath = it
                mode = RecordMode.PHOTO
                message = "照片准备好了，在旁边写下此刻的感受吧。"
            }
            .onFailure { message = it.message ?: "照片读取失败" }
    }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) acceptPhoto(uri) else message = "已取消拍摄。"
    }
    fun launchCamera() {
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("duck-photo-", ".jpg", directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        pendingCameraUri = uri
        takePhoto.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else message = "需要相机权限才能拍照，也可以从相册选择。"
    }
    fun requestCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) acceptPhoto(uri)
    }

    fun startVoice() {
        voiceTempFile?.delete()
        voiceTempFile = null
        runCatching { recorder.start() }
            .onSuccess { isRecording = true; message = "正在听，点一次结束并转成文字。" }
            .onFailure { message = it.message ?: "无法启动录音" }
    }
    fun stopVoice() {
        val file = recorder.stop()
        isRecording = false
        if (file == null || file.length() <= 44) {
            file?.delete()
            message = "没有录到声音，请再试一次。"
            return
        }
        busy = true
        voiceTempFile = file
        scope.launch {
            runCatching { chatRepository.transcribe(file) }
                .onSuccess { note = it; message = "已经转成文字，你可以修改后保存。" }
                .onFailure { message = "录音已经保留，但暂时没有听清；可以稍后补充文字。" }
            busy = false
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else message = "需要麦克风权限才能记录语音。"
    }
    fun toggleVoice() {
        if (busy) return
        if (isRecording) stopVoice()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun save(status: String) {
        val mainText = note.trim()
        if (mode == RecordMode.PHOTO && photoPath == null) {
            message = "请先拍一张或选择一张照片。"
            return
        }
        if (mode == RecordMode.TEXT && mainText.isBlank()) {
            message = "哪怕只写一句也可以。"
            return
        }
        if (mode == RecordMode.VOICE && voiceTempFile == null) {
            message = "请先录下一段声音。"
            return
        }
        val savedAudioPath = if (mode == RecordMode.VOICE) {
            runCatching { localRepository.importAudio(requireNotNull(voiceTempFile)) }
                .getOrElse {
                    message = it.message ?: "录音保存失败"
                    return
                }
        } else null
        localRepository.save(
            RecordDraft(
                id = localRepository.newId(),
                mode = mode,
                text = mainText,
                audioPath = savedAudioPath,
                photoPath = photoPath,
                photoComment = photoComment.trim(),
                status = status,
            )
        )
        records = localRepository.load()
        scope.launch {
            WidgetUpdater.refreshGrowthWidgets(context)
        }
        note = ""
        voiceTempFile?.delete()
        voiceTempFile = null
        photoComment = ""
        photoPath = null
        message = if (status == "draft") "已经替你保存草稿。" else "这一笔已经好好收下了。"
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            voiceTempFile?.delete()
        }
    }
    LaunchedEffect(requestedMode, cameraLaunchToken) {
        mode = requestedMode
        if (cameraLaunchToken > 0 && cameraLaunchToken != handledCameraToken) {
            handledCameraToken = cameraLaunchToken
            requestCamera()
        }
        onRequestConsumed()
    }

    selectedRecord?.let { record ->
        RecordDetailDialog(record = record, onDismiss = { selectedRecord = null })
    }

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("今日留一笔", style = MaterialTheme.typography.displaySmall)
                    Text("不必完整，留下一点就好。", style = MaterialTheme.typography.bodyLarge, color = InkMuted)
                }
                DuckArt(R.drawable.duck_writing, "正在记录的小鸭", Modifier.size(130.dp))
            }
        }
        item {
            WarmCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(SimpleDateFormat("M月d日  EEEE", Locale.SIMPLIFIED_CHINESE).format(Date()), style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecordModeButton(R.drawable.ic_record_write, "写一句", mode == RecordMode.TEXT, Modifier.weight(1f)) { mode = RecordMode.TEXT }
                        RecordModeButton(R.drawable.ic_record_voice, "说一句", mode == RecordMode.VOICE, Modifier.weight(1f)) { mode = RecordMode.VOICE }
                        RecordModeButton(R.drawable.ic_record_photo, "拍一张", mode == RecordMode.PHOTO, Modifier.weight(1f)) { mode = RecordMode.PHOTO }
                    }
                    when (mode) {
                        RecordMode.TEXT -> RecordTextField(note, { note = it }, "今天发生了什么？此刻的你是什么感受？")
                        RecordMode.VOICE -> {
                            RecordTextField(note, { note = it }, "说完后会在这里显示文字，你可以继续修改。")
                            Button(onClick = ::toggleVoice, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Text(if (isRecording) "结束并转成文字" else "按一下开始说")
                            }
                        }
                        RecordMode.PHOTO -> PhotoRecordEditor(
                            photoPath = photoPath,
                            comment = photoComment,
                            onCommentChange = { photoComment = it },
                            onCamera = ::requestCamera,
                            onGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        )
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SageDark) }
                    Text("▣ 仅自己可见，可随时修改或删除", style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                    Button(
                        onClick = { save("saved") },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = AppButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                    ) { Text("保存这一笔", fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = { save("draft") }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("存为草稿", color = SageDark)
                    }
                }
            }
        }
        item {
            WarmCard {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeading("最近留下的")
                    if (records.isEmpty()) Text("还没有记录，第一笔可以很短。", color = InkMuted)
                    records.take(5).forEach { record ->
                        Row(
                            modifier = Modifier.fillMaxWidth().noRippleClickable { selectedRecord = record }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (record.mode == RecordMode.PHOTO) "照片" else if (record.mode == RecordMode.VOICE) "语音" else "文字", modifier = Modifier.weight(0.2f), color = SageDark)
                            Text(record.photoComment.ifBlank { record.text }.ifBlank { "一张照片" }, modifier = Modifier.weight(0.72f), style = MaterialTheme.typography.bodyLarge)
                            Text("›", modifier = Modifier.weight(0.08f), style = MaterialTheme.typography.titleLarge, color = InkMuted)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun RecordDetailDialog(record: RecordDraft, onDismiss: () -> Unit) {
    var playing by remember { mutableStateOf(false) }
    val player = remember(record.id) { MediaPlayer() }
    DisposableEffect(player) {
        onDispose {
            runCatching { player.stop() }
            player.release()
        }
    }

    fun toggleAudio() {
        val path = record.audioPath ?: return
        if (playing) {
            runCatching { player.pause() }
            playing = false
        } else {
            runCatching {
                if (player.currentPosition > 0) player.start()
                else {
                    player.reset()
                    player.setDataSource(path)
                    player.setOnCompletionListener {
                        playing = false
                        it.seekTo(0)
                    }
                    player.prepare()
                    player.start()
                }
                playing = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Cream,
        shape = RoundedCornerShape(30.dp),
        title = {
            Text(
                when (record.mode) {
                    RecordMode.TEXT -> "那天写下的话"
                    RecordMode.VOICE -> "那天留下的声音"
                    RecordMode.PHOTO -> "那天留下的画面"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    SimpleDateFormat("yyyy年M月d日  HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(record.createdAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkMuted,
                )
                record.photoPath?.let { path ->
                    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                    if (bitmap != null) Image(
                        bitmap = bitmap,
                        contentDescription = "记录中的照片",
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (record.mode == RecordMode.VOICE) {
                    if (record.audioPath?.let { File(it).exists() } == true) {
                        Button(
                            onClick = ::toggleAudio,
                            colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                            shape = AppButtonShape,
                        ) { Text(if (playing) "暂停播放" else "播放原声") }
                    } else {
                        Text("这条旧记录只保留了转译文字。", color = InkMuted)
                    }
                }
                val content = record.photoComment.ifBlank { record.text }
                if (content.isNotBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().background(WarmWhite, RoundedCornerShape(20.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(if (record.mode == RecordMode.VOICE) "转译文字" else "当时写下的感受", color = SageDark)
                        Text(content, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("收好", color = SageDark) } },
    )
}

@Composable
private fun RecordTextField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().height(150.dp),
        placeholder = { Text(placeholder) },
        shape = RoundedCornerShape(22.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = SageDark, unfocusedBorderColor = WarmOutline),
    )
}

@Composable
private fun PhotoRecordEditor(
    photoPath: String?,
    comment: String,
    onCommentChange: (String) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (photoPath == null) {
            Column(modifier = Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCamera, modifier = Modifier.fillMaxWidth()) { Text("打开相机") }
                OutlinedButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) { Text("从相册选") }
            }
        } else {
            val bitmap = remember(photoPath) { BitmapFactory.decodeFile(photoPath)?.asImageBitmap() }
            if (bitmap != null) Image(
                bitmap = bitmap,
                contentDescription = "记录照片",
                modifier = Modifier.weight(0.42f).height(170.dp),
                contentScale = ContentScale.Crop,
            )
        }
        OutlinedTextField(
            value = comment,
            onValueChange = onCommentChange,
            modifier = Modifier.weight(0.58f).height(170.dp),
            placeholder = { Text("照片里的这一刻，让你有什么感受？") },
            shape = RoundedCornerShape(22.dp),
        )
    }
    if (photoPath != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCamera) { Text("重新拍") }
        TextButton(onClick = onGallery) { Text("换一张") }
    }
}

@Composable
private fun RecordModeButton(iconRes: Int, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(6.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(25.dp),
            )
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
