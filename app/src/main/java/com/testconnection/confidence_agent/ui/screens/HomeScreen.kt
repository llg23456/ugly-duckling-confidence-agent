package com.testconnection.confidence_agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import com.testconnection.confidence_agent.ui.theme.CreamDeep
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    viewModel: HomeViewModel,
    userName: String,
    onOpenVoice: () -> Unit,
    onOpenRecordSource: (Long) -> Unit = {},
    sourceMessageId: Long? = null,
    onSourceLocated: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var requestedSourceId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showImageSourceDialog by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var imageLoadError by remember { mutableStateOf<String?>(null) }
    var editableSupportMessage by remember(state.supportSuggestion?.id) {
        mutableStateOf(state.supportSuggestion?.editableMessage.orEmpty())
    }
    var feedbackChoice by remember { mutableStateOf<String?>(null) }
    var effortText by remember { mutableStateOf("") }
    var helpText by remember { mutableStateOf("") }

    state.checkInScheduledNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCheckInScheduledNotice,
            title = { Text("小鸭记住了") },
            text = { Text(notice, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissCheckInScheduledNotice) {
                    Text("知道啦", color = SageDark)
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    feedbackChoice?.let { choice ->
        AlertDialog(
            onDismissRequest = { feedbackChoice = null },
            title = { Text(if (choice == "helped") "记录这次帮助" else "记录这次尝试") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = effortText, onValueChange = { effortText = it }, label = { Text("我自己做了什么（选填）") })
                    if (choice == "helped") OutlinedTextField(value = helpText, onValueChange = { helpText = it }, label = { Text("对方怎样帮到了我（选填）") })
                }
            },
            confirmButton = { TextButton(onClick = {
                viewModel.submitSupportFeedback(choice, effortText, helpText)
                feedbackChoice = null
            }) { Text("保存反馈") } },
            dismissButton = { TextButton(onClick = { feedbackChoice = null }) { Text("取消") } },
        )
    }

    fun acceptChatImage(uri: Uri, fallbackName: String, deleteAfterRead: Boolean = false) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val mimeType = resolver.getType(uri) ?: "image/jpeg"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选图片")
                    Triple(uri.lastPathSegment ?: fallbackName, mimeType, bytes)
                }
            }.onSuccess { (fileName, mimeType, bytes) ->
                viewModel.selectImage(fileName, mimeType, bytes)
                imageLoadError = null
            }.onFailure {
                imageLoadError = it.message ?: "图片读取失败，请重新选择。"
            }
            if (deleteAfterRead) pendingCameraFile?.delete()
            pendingCameraFile = null
            pendingCameraUri = null
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) acceptChatImage(uri, "gallery-photo.jpg")
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) acceptChatImage(uri, "camera-photo.jpg", deleteAfterRead = true)
        else {
            pendingCameraFile?.delete()
            pendingCameraFile = null
            pendingCameraUri = null
        }
    }
    fun launchCamera() {
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("chat-photo-", ".jpg", directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        pendingCameraFile = file
        pendingCameraUri = uri
        takePhoto.launch(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else imageLoadError = "需要相机权限才能拍照，也可以从相册选择。"
    }
    fun requestCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            title = { Text("添加一张图片") },
            text = { Text("拍一张新照片，或从相册选择。图片只用于本次对话。") },
            confirmButton = {
                TextButton(onClick = {
                    showImageSourceDialog = false
                    requestCamera()
                }) { Text("拍照", color = SageDark) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImageSourceDialog = false
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("从相册选择", color = SageDark) }
            },
        )
    }

    LaunchedEffect(
        state.messages.size,
        state.pendingMessage,
        state.proactiveCheckIn?.id,
        state.checkInAcknowledgement,
    ) {
        if (
            sourceMessageId == null && requestedSourceId == null &&
            (state.messages.isNotEmpty() || state.pendingMessage != null ||
                state.proactiveCheckIn != null || state.checkInAcknowledgement != null)
        ) {
            val chatCount = state.messages.size + if (state.pendingMessage != null) 1 else 0
            val hasCheckInCard = state.proactiveCheckIn != null || state.checkInAcknowledgement != null
            listState.animateScrollToItem(if (hasCheckInCard) 2 + chatCount else 1 + chatCount)
        }
    }
    LaunchedEffect(sourceMessageId) {
        if (sourceMessageId != null) {
            requestedSourceId = sourceMessageId
            viewModel.refreshHistory()
        }
    }
    LaunchedEffect(requestedSourceId, state.messages.size) {
        if (requestedSourceId != null) {
            val index = state.messages.indexOfFirst { it.id == requestedSourceId }
            if (index >= 0) {
                listState.animateScrollToItem(index + 2)
                requestedSourceId = null
                if (sourceMessageId != null) onSourceLocated()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.padding(contentPadding),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = onOpenVoice,
                        shape = CircleShape,
                        color = CreamDeep,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(R.drawable.ic_home_voice_call),
                                contentDescription = "进入语音通话",
                                modifier = Modifier.size(25.dp),
                            )
                        }
                    }
                    Surface(shape = CircleShape, color = SagePale, modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(userName.take(1), color = SageDark) }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("晚上好，$userName", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "不管今天怎样，你都已经很努力了。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DuckArt(
                    drawable = R.drawable.duck_listening,
                    description = "正在认真倾听的小鸭",
                    modifier = Modifier.size(138.dp),
                )
            }
        }

        items(
            items = state.messages + listOfNotNull(state.pendingMessage),
            key = { it.id ?: Long.MIN_VALUE },
        ) { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = if (message.fromUser) Arrangement.Start else Arrangement.End,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.84f),
                    shape = RoundedCornerShape(24.dp),
                    color = if (message.fromUser) CreamDeep else MaterialTheme.colorScheme.surface,
                    border = if (message.fromUser) null else BorderStroke(1.dp, WarmOutline),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val bitmap = remember(message.imageBytes, message.imagePath) {
                            when {
                                message.imageBytes != null -> BitmapFactory.decodeByteArray(
                                    message.imageBytes, 0, message.imageBytes.size,
                                )
                                message.imagePath != null -> BitmapFactory.decodeFile(message.imagePath)
                                else -> null
                            }?.asImageBitmap()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "对话中的图片",
                                modifier = Modifier.fillMaxWidth().height(190.dp),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        if (message.text.isNotBlank()) {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }

        if (state.proactiveCheckIn != null || state.checkInAcknowledgement != null) item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, WarmOutline),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("小鸭来问问", style = MaterialTheme.typography.bodyMedium, color = SageDark)
                        state.proactiveCheckIn?.let { checkIn ->
                            Text(checkIn.prompt, style = MaterialTheme.typography.bodyLarge)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = { viewModel.respondToCheckIn("talk") },
                                    enabled = !state.checkInLoading,
                                    modifier = Modifier.weight(1f),
                                    shape = AppButtonShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                ) { Text("想聊聊") }
                                TextButton(
                                    onClick = { viewModel.respondToCheckIn("improved") },
                                    enabled = !state.checkInLoading,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                ) { Text("好一点") }
                                TextButton(
                                    onClick = { viewModel.respondToCheckIn("not_now") },
                                    enabled = !state.checkInLoading,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                ) { Text("暂时不说") }
                            }
                            if (state.checkInLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp).align(Alignment.CenterHorizontally),
                                    color = SageDark,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        if (state.proactiveCheckIn == null) {
                            state.checkInAcknowledgement?.let {
                                Text(it, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }

        item {
            if (state.sending) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = SageDark,
                        strokeWidth = 2.dp,
                    )
                }
            }
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.lastReplyWasMock == true) {
                Text(
                    text = "当前为演示回复",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        if (state.lastEvidence.isNotEmpty()) item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = SagePale,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("想起一件事", style = MaterialTheme.typography.bodyMedium, color = SageDark)
                        Text(
                            state.lastEvidence.first().summary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    state.lastEvidence.first().sourceId?.let { sourceId ->
                        TextButton(onClick = {
                            viewModel.dismissEvidence()
                            requestedSourceId = sourceId
                            if (state.messages.none { it.id == sourceId }) viewModel.refreshHistory()
                        }) { Text("查看") }
                    }
                    state.lastEvidence.first().sourceRecordId?.let { recordId ->
                        TextButton(onClick = {
                            viewModel.dismissEvidence()
                            onOpenRecordSource(recordId)
                        }) { Text("查看") }
                    }
                }
            }
        }

        if (state.supportSuggestion != null) item {
            WarmCard {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.supportSuggestion?.let { suggestion ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("一起想下一步", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::dismissSupportSuggestion) { Text("收起") }
                        }
                        Text("可以自己试一小步，也可以请人帮忙。", style = MaterialTheme.typography.bodyMedium)
                        Text("我自己先试：${suggestion.smallStep}", style = MaterialTheme.typography.bodyLarge)
                        Text("可以找谁：${suggestion.supporterName}。${suggestion.reason}", style = MaterialTheme.typography.bodyLarge)
                        Text("更轻的选择：${suggestion.lighterOption}", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = editableSupportMessage,
                            onValueChange = { editableSupportMessage = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("可修改的求助话术") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("求助话术", editableSupportMessage))
                            }, modifier = Modifier.weight(1f), shape = AppButtonShape) { Text("复制") }
                            OutlinedButton(onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, editableSupportMessage)
                                }
                                context.startActivity(Intent.createChooser(intent, "选择分享方式"))
                            }, modifier = Modifier.weight(1f), shape = AppButtonShape) { Text("分享") }
                        }
                        if (state.supportFeedbackOutcome == null && suggestion.id != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { effortText = ""; helpText = ""; feedbackChoice = "helped" }, modifier = Modifier.weight(1f)) { Text("帮到了") }
                                TextButton(onClick = { effortText = ""; helpText = ""; feedbackChoice = "not_helped" }, modifier = Modifier.weight(1f)) { Text("没帮到") }
                                TextButton(onClick = { viewModel.submitSupportFeedback("not_contacted", null, null) }, modifier = Modifier.weight(1f)) { Text("没有联系") }
                            }
                        } else if (state.supportFeedbackOutcome != null) {
                            Text("这次反馈已记录。", style = MaterialTheme.typography.bodyMedium, color = SageDark)
                        }
                    }
                    Text(
                        "只提供建议，不会自动给任何人发送消息。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.supportSuggestion == null) item {
            val latest = state.messages.lastOrNull { it.fromUser }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.supportLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = SageDark, strokeWidth = 2.dp)
                    Text(" 正在想一个轻一点的办法…", style = MaterialTheme.typography.bodyMedium)
                } else if (latest != null) {
                    OutlinedButton(
                        onClick = { viewModel.requestSupport(latest.text, latest.id) },
                        shape = AppButtonShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                    ) { Text("一起想下一步") }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.pendingImage?.let { selected ->
                    val bitmap = remember(selected.bytes) {
                        BitmapFactory.decodeByteArray(selected.bytes, 0, selected.bytes.size)?.asImageBitmap()
                    }
                    if (bitmap != null) {
                        Box {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "准备发送的图片",
                                modifier = Modifier.fillMaxWidth().height(170.dp),
                                contentScale = ContentScale.Crop,
                            )
                            TextButton(
                                onClick = viewModel::removeSelectedImage,
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) { Text("移除") }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().background(CreamDeep, RoundedCornerShape(28.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_record_photo),
                        contentDescription = "拍照或选择图片",
                        modifier = Modifier
                            .size(28.dp)
                            .noRippleClickable(enabled = !state.sending) { showImageSourceDialog = true }
                            .padding(2.dp),
                    )
                    BasicTextField(
                        value = state.draft,
                        onValueChange = viewModel::updateDraft,
                        modifier = Modifier.weight(1f),
                        enabled = !state.sending,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (state.draft.isEmpty()) {
                                    Text(
                                        "和小鸭说说吧…",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    Surface(
                        onClick = viewModel::send,
                        enabled = (state.draft.isNotBlank() || state.pendingImage != null) && !state.sending,
                        color = SageDark,
                        shape = CircleShape,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("➤", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                imageLoadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
