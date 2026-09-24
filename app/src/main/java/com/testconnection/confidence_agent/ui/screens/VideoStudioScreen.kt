package com.testconnection.confidence_agent.ui.screens

import android.content.Intent
import android.widget.VideoView
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
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import java.io.File
import kotlinx.coroutines.launch

private val stageLabels = mapOf(
    "difficulty" to "困难", "small_step" to "小尝试", "help" to "获得帮助",
    "change" to "当前变化", "continuing" to "仍在继续",
)

@Composable
fun VideoStudioScreen(events: List<GrowthEvent>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { VideoScriptApiClient() }
    val renderer = remember { GrowthVideoRenderer(context.applicationContext) }
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    val candidates = events.filter { it.sensitivity == null || it.sensitivity == "low" }.take(30)
    var selectedIds by remember(candidates.map { it.id }) { mutableStateOf(candidates.take(5).map { it.id }.toSet()) }
    var script by remember { mutableStateOf<VideoScript?>(null) }
    var scenes by remember { mutableStateOf<List<VideoScene>>(emptyList()) }
    var video by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun createScript() {
        if (selectedIds.isEmpty() || busy) return
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
        if (scenes.size !in 2..5 || busy) return
        busy = true
        error = null
        video = null
        scope.launch {
            val saved = runCatching { api.update(deviceId, current.id, scenes) }
            if (saved.isFailure) notice = "脚本修改暂未同步，视频仍会保存在本机。"
            else { script = saved.getOrNull(); notice = null }
            runCatching { renderer.render(scenes) }
                .onSuccess { video = it }
                .onFailure { error = "视频合成失败，请重试或减少片段。" }
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("‹ 成长小片", modifier = Modifier.noRippleClickable(onClick = onBack), style = MaterialTheme.typography.displaySmall)
            Text("先选真实经历，再预览和删改脚本。只有你主动点击分享，视频才会离开本机。")
        }
        item {
            WarmCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择素材（最多 6 条）", style = MaterialTheme.typography.titleLarge)
                    if (candidates.isEmpty()) Text("目前没有可选的成长事件，先留下一笔或聊聊自己的尝试。")
                    candidates.forEach { event ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = event.id in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked && selectedIds.size < 6) selectedIds + event.id
                                    else if (!checked) selectedIds - event.id else selectedIds
                                    script = null; scenes = emptyList(); video = null
                                },
                            )
                            Text(event.fact, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Button(onClick = ::createScript, enabled = selectedIds.isNotEmpty() && !busy) { Text("生成五段脚本") }
                }
            }
        }
        if (script != null) item {
            WarmCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("预览并删改脚本", style = MaterialTheme.typography.titleLarge)
                    Text(if (script?.mock == true) "当前使用可编辑模板；未调用 AI。" else "已生成可编辑脚本，分享前请确认每一句都准确。")
                    scenes.forEachIndexed { index, scene ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stageLabels[scene.stage] ?: scene.stage, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                if (scenes.size > 2) scenes = scenes.filterIndexed { i, _ -> i != index }
                            }, enabled = scenes.size > 2) { Text("移除") }
                        }
                        OutlinedTextField(
                            value = scene.text,
                            onValueChange = { value -> if (value.length <= 120) scenes = scenes.toMutableList().also { it[index] = scene.copy(text = value) } },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    }
                    Text("${scenes.size} 个片段 · 约 ${if (scenes.size == 2) 15 else if (scenes.size == 3) 15 else 20} 秒")
                    Button(onClick = ::exportVideo, enabled = !busy && scenes.size >= 2 && scenes.all { it.text.isNotBlank() }) { Text("合成本地 MP4") }
                }
            }
        }
        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
            Text(if (script == null) "正在生成脚本…" else "正在合成视频…")
        } }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        notice?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        video?.let { file -> item {
            WarmCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("小片已保存在本机", style = MaterialTheme.typography.titleLarge)
                    AndroidView(
                        factory = { VideoView(it).apply { setVideoPath(file.absolutePath); setOnPreparedListener { player -> player.isLooping = true; start() } } },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                    )
                    OutlinedButton(onClick = { shareVideo(file) }) { Text("打开系统分享") }
                }
            }
        } }
    }
}
