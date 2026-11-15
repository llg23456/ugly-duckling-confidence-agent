package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.sms.DeviceSms
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.SupportApiClient
import com.testconnection.confidence_agent.data.remote.SupportPerson
import com.testconnection.confidence_agent.data.remote.WellbeingApiClient
import com.testconnection.confidence_agent.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TrustedContactScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val device = remember { DeviceIdStore(context.applicationContext).get() }
    val api = remember { WellbeingApiClient() }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("overview") }
    var people by remember { mutableStateOf<List<SupportPerson>>(emptyList()) }
    var notices by remember { mutableStateOf(JSONArray()) }
    var preferences by remember { mutableStateOf(JSONObject()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<SupportPerson?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var expandedNotice by remember { mutableStateOf<Long?>(null) }
    fun back() { if (page == "overview") onBack() else { page = "overview"; error = null } }
    BackHandler { back() }
    suspend fun reload() {
        people = SupportApiClient().people(device)
        notices = api.request("notifications", device).optJSONArray("items") ?: JSONArray()
        preferences = api.request("today", device).getJSONObject("preferences")
    }
    fun perform(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            runCatching { work() }.onFailure { error = it.message ?: "操作失败，请重试" }
            busy = false
        }
    }
    LaunchedEffect(Unit) { perform { reload() } }
    if (page == "editor") {
        SupportContactEditor(device, selected, onBack = { page = "overview" }, onSaved = { page = "overview"; perform { reload() } })
        return
    }
    val enabled = preferences.optBoolean("auto_notify")
    val title = when (page) { "settings" -> "自动安全提醒"; "notices" -> "通知记录"; else -> "可信联系人" }
    WellbeingPage(title, ::back, action = {
        TextButton(enabled = !busy, onClick = { perform { reload() } }) { Text("刷新") }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when (page) {
            "overview" -> {
                if (people.isEmpty()) WellbeingCard {
                    WellbeingIcon(R.drawable.ic_wellbeing_users)
                    Text("留一个可以找的人", style = MaterialTheme.typography.titleLarge)
                    Text("添加你信任、愿意接收提醒的人。", color = InkMuted)
                }
                people.forEach { person ->
                    WellbeingCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            WellbeingIcon(R.drawable.ic_wellbeing_heart_handshake)
                            Column(Modifier.weight(1f)) {
                                Text("${person.name} · ${person.relationship}", style = MaterialTheme.typography.titleMedium)
                                Text(maskContactPhone(person.phone), color = InkMuted)
                            }
                            TextButton(enabled = !busy, onClick = { selected = person; page = "editor" }) { Text("编辑") }
                        }
                        Text(when {
                            person.needsConsentUpdate -> "请编辑并确认新版提醒说明"
                            person.emergencyEnabled && enabled -> "紧急提醒已授权 · 按支持场景匹配"
                            person.emergencyEnabled -> "紧急提醒已暂停"
                            else -> "仅保存联系方式"
                        }, color = SageDark, style = MaterialTheme.typography.labelMedium)
                    }
                }
                WellbeingEntry(R.drawable.ic_wellbeing_phone, "添加联系人", "填写手机号与支持场景") { selected = null; page = "editor" }
                if (enabled) DeviceSms.lastError(context)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                WellbeingEntry(R.drawable.ic_wellbeing_shield_check, "自动安全提醒", if (enabled) "已开启 · 查看规则" else "未开启 · 查看规则") { page = "settings" }
                WellbeingEntry(R.drawable.ic_wellbeing_bell, "通知记录", if (notices.length() == 0) "暂无通知" else "查看最近 ${notices.length()} 条记录") { page = "notices" }
            }
            "settings" -> {
                WellbeingCard {
                    WellbeingIcon(R.drawable.ic_wellbeing_shield_check)
                    Text(if (enabled) "安全提醒已开启" else "让信任的人及时知道", style = MaterialTheme.typography.titleLarge)
                    Text("确认本人当前有轻生或伤害意图后，自动通知已授权的情绪支持联系人；普通压力抱怨不触发。")
                    Text("短信包含触发时间、类型和必要原话摘要。", color = InkMuted)
                    TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "收起说明" else "了解提醒规则  ›") }
                    if (showDetails) {
                        HorizontalDivider(color = WellbeingOutline)
                        Text("含糊表达会先澄清，压力分高不会单独触发。仅向你授权的号码发送，不包含整段对话或照片。可以编辑联系人关闭，已发出的短信无法撤回。", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                        Text("当前由本机发送，请保持 App 前台及后端连接。短信可能失败或延迟，立即危险时请直接求助。", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (enabled) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { perform {
                        DeviceSms.stop(context)
                        api.request("preferences", device, "PATCH", JSONObject().put("auto_notify", false))
                        reload()
                    } }) { Text("暂停全部自动提醒") }
                    TextButton(onClick = { page = "overview" }) { Text(if (enabled) "管理联系人" else "编辑联系人并开启提醒") }
                }
            }
            "notices" -> {
                if (notices.length() == 0) WellbeingCard {
                    WellbeingIcon(R.drawable.ic_wellbeing_bell)
                    Text("还没有通知记录", style = MaterialTheme.typography.titleMedium)
                    Text("触发原因与发送状态会显示在这里。", color = InkMuted)
                }
                for (i in 0 until notices.length()) {
                    val notice = notices.getJSONObject(i)
                    val id = notice.getLong("id")
                    val label = when (notice.optString("status")) {
                        "simulated" -> "模拟完成"
                        "queued", "phone_queued" -> "等待处理"
                        "cancelled" -> "已取消"
                        "expired" -> "已过期"
                        "phone_claimed", "phone_sending" -> "手机正在发送"
                        "phone_sent" -> "短信已发出 · 未确认送达"
                        "phone_delivered" -> "运营商已报告送达 · 不代表已读"
                        "phone_failed" -> "发送失败 · 不会自动重发"
                        "phone_unknown" -> "结果待确认 · 请查看系统短信记录"
                        else -> "尚未完成"
                    }
                    WellbeingCard {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text(noticeTime(notice.optString("created_at")), color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                        val message = notice.optString("message").takeUnless { it == "null" }.orEmpty()
                        if (message.isNotBlank()) {
                            TextButton(onClick = { expandedNotice = if (expandedNotice == id) null else id }) { Text(if (expandedNotice == id) "收起短信内容" else "触发原因与短信内容  ›") }
                            if (expandedNotice == id) Text(message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

internal fun maskContactPhone(phone: String): String = if (phone.length >= 11) phone.dropLast(8) + "****" + phone.takeLast(4) else "请补充手机号"

private fun noticeTime(raw: String): String = runCatching {
    val value = if (raw.endsWith("Z") || Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(raw)) raw else raw + "Z"
    DateTimeFormatter.ofPattern("MM 月 dd 日  HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(raw.take(19).replace("T", " "))
