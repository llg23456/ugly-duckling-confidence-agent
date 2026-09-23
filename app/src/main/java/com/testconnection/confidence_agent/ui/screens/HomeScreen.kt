package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.repository.FakeConfidenceRepository
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.SectionHeading
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.CreamDeep
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    viewModel: HomeViewModel,
    onOpenVoice: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val mimeType = resolver.getType(uri) ?: "image/jpeg"
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("无法读取所选图片")
                        Triple(uri.lastPathSegment ?: "photo.jpg", mimeType, bytes)
                    }
                }.onSuccess { (fileName, mimeType, bytes) ->
                    viewModel.sendImage(fileName, mimeType, bytes)
                }
            }
        }
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
                            Text("☎", color = SageDark, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Surface(shape = CircleShape, color = SagePale, modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("林", color = SageDark) }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("晚上好，林林", style = MaterialTheme.typography.displaySmall)
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
                        Text(message.text, modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.bodyLarge)
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
            Row(
                modifier = Modifier.fillMaxWidth().background(CreamDeep, RoundedCornerShape(28.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "▧",
                    modifier = Modifier
                        .clickable(enabled = !state.sending) {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .padding(horizontal = 8.dp),
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
                Text("♬", modifier = Modifier.padding(horizontal = 8.dp))
                Surface(
                    onClick = viewModel::send,
                    enabled = state.draft.isNotBlank() && !state.sending,
                    color = SageDark,
                    shape = CircleShape,
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("➤", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}
