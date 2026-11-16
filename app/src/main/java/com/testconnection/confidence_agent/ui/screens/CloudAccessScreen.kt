package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.data.remote.CloudAccess
import kotlinx.coroutines.launch

@Composable
fun CloudAccessScreen() {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun connect() {
        busy = true
        error = null
        scope.launch {
            try { CloudAccess.connect() }
            catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = if (failure is java.io.IOException) "暂时无法连接云端，请检查网络后重试"
                    else failure.message ?: "连接失败，请稍后重试"
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { connect() }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("正在连接云端", style = MaterialTheme.typography.headlineMedium)
        Text("首次打开会自动连接，之后即可继续使用。")
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!busy && error != null) Button(onClick = { connect() }) { Text("重新连接") }
        Text("你提交的对话、记录及媒体会发送到项目云端服务，AI 功能由百炼提供。请勿提交他人的隐私信息。",
            style = MaterialTheme.typography.bodySmall)
    }
}
