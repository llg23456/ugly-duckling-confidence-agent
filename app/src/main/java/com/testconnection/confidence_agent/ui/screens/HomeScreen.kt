package com.testconnection.confidence_agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.testconnection.confidence_agent.data.repository.FakeConfidenceRepository
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.SectionHeading
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
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showImageSourceDialog by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var imageLoadError by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(state.messages.size) {
        if (state.messages.size > FakeConfidenceRepository.conversation.size) {
            listState.animateScrollToItem(2)
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

        item {
            state.messages.forEach { message ->
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
                            message.imageBytes?.let { bytes ->
                                val bitmap = remember(bytes) {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                }
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "对话中的图片",
                                        modifier = Modifier.fillMaxWidth().height(190.dp),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                            if (message.text.isNotBlank()) {
                                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
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

        item {
            WarmCard {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeading("想起一件事", "上周你已经完整练习过 3 次")
                        Button(
                            onClick = {},
                            shape = AppButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                        ) { Text("查看来源") }
                    }
                    DuckArt(
                        drawable = R.drawable.duck_writing,
                        description = "翻看记录的小鸭",
                        modifier = Modifier.size(118.dp),
                    )
                }
            }
        }

        item {
            WarmCard {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeading("要不要一起想下一步？", "不用完美，我们可以一点点来。")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            shape = AppButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                        ) { Text("先练 30 秒") }
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            shape = AppButtonShape,
                        ) { Text("请同学陪练") }
                    }
                    Text(
                        "只提供建议，不会自动给任何人发送消息。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
