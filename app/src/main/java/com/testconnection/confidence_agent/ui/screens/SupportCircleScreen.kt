package com.testconnection.confidence_agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.SupportApiClient
import com.testconnection.confidence_agent.data.remote.SupportPerson
import com.testconnection.confidence_agent.data.remote.SupportFeedbackRecord
import com.testconnection.confidence_agent.ui.components.noRippleClickable
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.SagePale
import kotlinx.coroutines.launch

private val kindLabels = listOf(
    "teacher" to "老师", "classmate" to "同学", "friend" to "朋友",
    "family" to "家人", "professional" to "专业人士",
)

@Composable
fun SupportCircleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    val api = remember { SupportApiClient() }
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<SupportPerson>>(emptyList()) }
    var feedback by remember { mutableStateOf<List<SupportFeedbackRecord>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SupportPerson?>(null) }
    var showingEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SupportPerson?>(null) }
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("classmate") }
    var scenarios by remember { mutableStateOf("") }

    fun reload() { scope.launch {
        runCatching { api.people(deviceId) }
            .onSuccess { people = it; error = null }
            .onFailure { error = "支持圈暂时无法加载，请稍后重试。" }
        runCatching { api.feedbackHistory(deviceId) }.onSuccess { feedback = it }
    } }
    fun openEditor(person: SupportPerson?) {
        editing = person
        name = person?.name.orEmpty()
        relationship = person?.relationship.orEmpty()
        kind = person?.kind ?: "classmate"
        scenarios = person?.scenarios?.joinToString("、").orEmpty()
        showingEditor = true
    }
    LaunchedEffect(Unit) { reload() }

    if (showingEditor) AlertDialog(
        onDismissRequest = { showingEditor = false },
        title = { Text(if (editing == null) "添加支持对象" else "修改支持对象") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("称呼") })
                OutlinedTextField(value = relationship, onValueChange = { relationship = it }, label = { Text("与你的关系") })
                Text("适合向谁求助", style = MaterialTheme.typography.titleMedium)
                kindLabels.forEach { (value, label) ->
                    Row {
                        RadioButton(selected = kind == value, onClick = { kind = value })
                        Text(label, modifier = Modifier.padding(top = 12.dp).noRippleClickable { kind = value })
                    }
                }
                OutlinedTextField(value = scenarios, onValueChange = { scenarios = it }, label = { Text("适合帮忙的场景，用顿号分隔") })
            }
        },
        confirmButton = { TextButton(onClick = {
            if (name.isNotBlank() && relationship.isNotBlank()) scope.launch {
                val person = SupportPerson(
                    editing?.id ?: 0, name.trim(), relationship.trim(), kind,
                    scenarios.split('、', ',', '，').map(String::trim).filter(String::isNotBlank),
                )
                runCatching { api.savePerson(deviceId, person) }
                    .onSuccess { showingEditor = false; reload() }
                    .onFailure { error = "保存失败，请检查内容后重试。" }
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { showingEditor = false }) { Text("取消") } },
    )

    deleting?.let { person -> AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("移除${person.name}？") },
        text = { Text("这会将对方从支持圈移除；已记录的反馈仍会保留。") },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { api.deletePerson(deviceId, person.id) }
                .onSuccess { deleting = null; reload() }
                .onFailure { error = "移除失败，请重试。" }
        } }) { Text("移除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
    ) }

    Column(Modifier.fillMaxSize().background(Cream).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("‹ 支持圈", modifier = Modifier.noRippleClickable(onClick = onBack), style = MaterialTheme.typography.displaySmall)
        Text("只填写你愿意记下的人和适合求助的场景。不会读取通讯录，也不会自动联系任何人。", style = MaterialTheme.typography.bodyLarge)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (people.isEmpty()) Text("还没有添加支持对象。", style = MaterialTheme.typography.bodyMedium)
        people.forEach { person ->
            Surface(modifier = Modifier.fillMaxWidth(), color = SagePale, shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(person.name, style = MaterialTheme.typography.titleLarge)
                    Text("${person.relationship} · ${kindLabels.firstOrNull { it.first == person.kind }?.second ?: person.kind}")
                    if (person.scenarios.isNotEmpty()) Text("适合：${person.scenarios.joinToString("、")}")
                    Row {
                        TextButton(onClick = { openEditor(person) }) { Text("修改") }
                        TextButton(onClick = { deleting = person }) { Text("移除") }
                    }
                }
            }
        }
        if (feedback.isNotEmpty()) {
            Text("求助反馈", style = MaterialTheme.typography.titleLarge)
            feedback.forEach { item ->
                Surface(modifier = Modifier.fillMaxWidth(), color = SagePale, shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        val outcome = when (item.outcome) { "helped" -> "帮到了"; "not_helped" -> "没帮到"; else -> "没有联系" }
                        Text("${item.supporterName} · $outcome", style = MaterialTheme.typography.titleMedium)
                        item.ownEffort?.let { Text("我的尝试：$it") }
                        item.supportReceived?.let { Text("收到的帮助：$it") }
                        Text(item.createdAt.take(10), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { openEditor(null) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("添加一个人") }
    }
}
