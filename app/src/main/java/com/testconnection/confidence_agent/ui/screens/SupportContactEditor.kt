package com.testconnection.confidence_agent.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.BuildConfig
import com.testconnection.confidence_agent.data.remote.SupportApiClient
import com.testconnection.confidence_agent.data.remote.SupportPerson
import com.testconnection.confidence_agent.data.remote.WellbeingApiClient
import com.testconnection.confidence_agent.data.sms.DeviceSms
import com.testconnection.confidence_agent.ui.theme.InkMuted
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportContactEditor(device: String, initial: SupportPerson?, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var savedId by remember { mutableLongStateOf(initial?.id ?: 0) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var relationship by remember { mutableStateOf(initial?.relationship.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: "family") }
    var scenes by remember { mutableStateOf(initial?.scenarios?.joinToString("、").orEmpty()) }
    var notify by remember { mutableStateOf(!BuildConfig.CLOUD_TEST && (initial == null || initial.emergencyEnabled || initial.needsConsentUpdate)) }
    var consent by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var readiness by remember { mutableStateOf(DeviceSms.readiness(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        readiness = DeviceSms.readiness(context)
        if (!it) error = "未允许短信权限，可以先关闭紧急提醒并保存联系方式。"
    }
    LaunchedEffect(Unit) {
        runCatching { WellbeingApiClient().request("today", device).getJSONObject("preferences") }
            .onSuccess { available = !BuildConfig.CLOUD_TEST && it.optBoolean("device_sms_available") }
            .onFailure { error = "暂时无法检查短信服务，可先保存联系方式。" }
    }
    BackHandler { if (!busy) onBack() }
    val normalized = phone.replace(Regex("[\\s-]"), "").removePrefix("+86")
    val phoneValid = Regex("1[3-9]\\d{9}").matches(normalized)
    WellbeingPage(if (initial == null) "添加联系人" else "编辑联系人", { if (!busy) onBack() }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        WellbeingCard {
            Text("联系方式", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text("称呼") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(relationship, { relationship = it }, enabled = !busy, label = { Text("与你的关系") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it; consent = false }, enabled = !busy, label = { Text("手机号码 · 必填") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                isError = phone.isNotBlank() && !phoneValid, supportingText = { Text("请与对方核对号码，不读取手机通讯录。") })
        }
        WellbeingCard {
            Text("适合提供什么帮助", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("family" to "家人", "friend" to "朋友", "teacher" to "老师", "classmate" to "同学", "senior" to "师兄师姐", "professional" to "专业人士").forEach { (value, label) ->
                    FilterChip(kind == value, { if (!busy) { kind = value; consent = false } }, label = { Text(label) }, enabled = !busy)
                }
            }
            OutlinedTextField(scenes, { scenes = it; consent = false }, enabled = !busy, label = { Text("支持场景") },
                placeholder = { Text("例如：情绪低落、危机陪伴；或专业问题") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Text("紧急提醒优先匹配情绪支持或陪伴场景，不会转给只负责专业问题的人。", color = InkMuted, style = MaterialTheme.typography.bodySmall)
        }
        WellbeingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("紧急时通知此人", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(notify, { notify = it; consent = false }, enabled = !busy && !BuildConfig.CLOUD_TEST)
            }
            Text("确认本人当前有轻生或伤害意图时自动发短信；普通压力抱怨不触发。短信包含时间、类型及最多 40 字的相关原话。", style = MaterialTheme.typography.bodyMedium)
            if (notify) {
                if (initial?.needsConsentUpdate == true) Text("这位联系人需要确认新版提醒说明。", color = InkMuted, style = MaterialTheme.typography.bodySmall)
                Text("由本机发送，按运营商资费计费；请保持 App 前台及后端连接。", color = InkMuted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "收起说明" else "查看提醒规则") }
                if (showDetails) {
                    Text("否定、引用和玩笑不会仅因出现危险词而发送。通知不含整段对话或照片，也不代表伤害已经发生。同一联系人 10 分钟内最多一次，过期或失败不自动补发；已发出的短信无法撤回。", color = InkMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (!available) Text("当前无法开启短信；可关闭开关，先保存联系方式。", color = InkMuted, style = MaterialTheme.typography.bodySmall)
                if (available && readiness != null) {
                    Text(readiness!!, color = InkMuted, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !busy, onClick = {
                        if (!DeviceSms.hasPermission(context)) permission.launch(Manifest.permission.SEND_SMS)
                        else readiness = DeviceSms.readiness(context)
                    }) { Text(if (DeviceSms.hasPermission(context)) "重新检查短信设置" else "允许短信权限") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(consent, { consent = it }, enabled = !busy)
                    Text("我已成年，已核对号码并取得对方接收同意，允许以上自动提醒及必要原话摘要。", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !busy && name.isNotBlank() && relationship.isNotBlank() && phoneValid && (!notify || (available && consent && readiness == null)),
            modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching {
                        if (notify) DeviceSms.readiness(context)?.let { error(it) }
                        val saved = SupportApiClient().savePerson(device, SupportPerson(savedId, name.trim(), relationship.trim(), kind,
                            scenes.split('、', ',', '，').map(String::trim).filter(String::isNotBlank), "+86$normalized", emergencyEnabled = notify), consent)
                        savedId = saved.id
                        if (notify) {
                            DeviceSms.activate(context, checkNotNull(saved.enrollmentToken), checkNotNull(saved.contactId), saved.phone)
                        } else DeviceSms.removeContact(context, saved.contactId)
                    }.onSuccess { onSaved() }.onFailure { error = it.message ?: "保存失败，请重试" }
                    busy = false
                }
            }) { Text(if (busy) "保存中…" else "保存联系人") }
    }
}
