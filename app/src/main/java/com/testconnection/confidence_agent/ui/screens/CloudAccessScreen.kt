package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.CloudAccess
import kotlinx.coroutines.launch

@Composable
fun CloudAccessScreen() {
    val context = LocalContext.current
    val deviceId = remember { DeviceIdStore(context).get() }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("鸭力Go · 受邀云测", style = MaterialTheme.typography.headlineMedium)
        Text("输入组织者提供的邀请码，开始本次测试。授权保存在本设备，之后打开即可继续使用。")
        OutlinedTextField(value = code, onValueChange = { code = it.take(64); error = null },
            label = { Text("邀请码") }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth())
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(enabled = code.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            error = null
            scope.launch {
                runCatching { CloudAccess.activate(code) }.onFailure {
                    error = if (it is java.io.IOException) "暂时无法连接云端，请检查网络后重试" else it.message ?: "激活失败，请联系组织者"
                }
                busy = false
            }
        }) { Text(if (busy) "正在验证…" else "开始云测") }
        Text("本次测试会将你提交的对话、记录及媒体发送到项目云端服务，AI 功能由百炼提供。请勿提交他人的隐私信息。",
            style = MaterialTheme.typography.bodySmall)
        Text("如需恢复已有云测数据，请将下方设备编号发给组织者申请专用邀请码。", style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text(deviceId, style = MaterialTheme.typography.bodySmall) }
    }
}
