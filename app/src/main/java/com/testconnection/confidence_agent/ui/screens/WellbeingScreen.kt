package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.WellbeingApiClient
import com.testconnection.confidence_agent.data.remote.nullableScore
import com.testconnection.confidence_agent.ui.theme.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun scoreColor(score: Int?) = when {
    score == null -> Color(0xFF9CABA9)
    score < 40 -> Color(0xFF518FA5)
    score < 70 -> Color(0xFFBE9656)
    else -> Color(0xFFBE6C65)
}

private fun scoreLabel(score: Int?) = when {
    score == null -> "还没有足够的记录"
    score < 40 -> "此刻较轻松"
    score < 70 -> "有一些负担"
    else -> "给自己多一点照顾"
}

@Composable
private fun PressureGauge(score: Int?, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState((score ?: 0) / 100f, tween(650), label = "pressure")
    val color = scoreColor(score)
    Canvas(modifier.semantics { contentDescription = score?.let { "压力参考值 $it，满分100" } ?: "压力值暂无数据" }) {
        val x = size.width / 2
        val radius = size.width * .20f
        val top = radius
        val bottom = size.height - radius * 1.5f
        drawRoundRect(color.copy(alpha = .15f), Offset(x - radius * .55f, top),
            Size(radius * 1.1f, bottom - top), CornerRadius(radius))
        val filled = (bottom - top - radius) * fraction
        drawRoundRect(color, Offset(x - radius * .30f, bottom - filled),
            Size(radius * .6f, filled.coerceAtLeast(1f)), CornerRadius(radius))
        drawCircle(color, radius, Offset(x, bottom))
        for (i in 0..4) {
            val y = top + (bottom - top - radius) * i / 4
            drawLine(color.copy(alpha = .4f), Offset(x + radius, y), Offset(x + radius * 1.5f, y), 2.dp.toPx())
        }
    }
}

@Composable
fun DailyWellbeingCard(refreshKey: Any?, onOpen: () -> Unit) {
    val context = LocalContext.current
    val device = remember { DeviceIdStore(context.applicationContext).get() }
    val api = remember { WellbeingApiClient() }
    val scope = rememberCoroutineScope()
    val prompts = remember { context.getSharedPreferences("wellbeing_prompt", 0) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCheck by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey) {
        runCatching { api.request("today", device) }.onSuccess {
            data = it.optJSONObject("today"); error = null
            val date = data?.optString("date").orEmpty()
            if (data?.isNull("mood") == true && prompts.getString("handled_day", "") != date) showCheck = true
        }.onFailure { error = it.message ?: "压力卡片暂时无法加载" }
    }
    fun postpone() {
        prompts.edit().putString("handled_day", data?.optString("date")).apply()
        showCheck = false
    }
    if (showCheck) CheckInDialog(
        initial = data, busy = busy, error = error, onDismiss = { if (!busy) postpone() },
        onSave = { mood, pressure ->
            busy = true
            scope.launch {
                runCatching { api.request("check-in", device, "POST", JSONObject().put("mood", mood).put("pressure", pressure)) }
                    .onSuccess { data = it; error = null; postpone() }
                    .onFailure { error = it.message }
                busy = false
            }
        },
    )
    Surface(onClick = onOpen, color = Color(0xFFF0F4F0), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PressureGauge(data?.nullableScore("score"), Modifier.size(42.dp, 74.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("今日压力 · " + (data?.nullableScore("score")?.toString() ?: "—"), style = MaterialTheme.typography.titleMedium)
                Text(if (error != null) "暂未连接，点击查看" else scoreLabel(data?.nullableScore("score")), color = InkMuted, style = MaterialTheme.typography.bodySmall)
                Text("查看趋势与每日打卡  ›", color = SageDark, style = MaterialTheme.typography.labelMedium)
            }
            Image(painterResource(R.drawable.duck_wellbeing_pause), null, Modifier.size(70.dp))
        }
    }
}

@Composable
private fun CheckInDialog(initial: JSONObject?, busy: Boolean, error: String?, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var mood by remember { mutableStateOf(initial?.nullableScore("mood")) }
    var pressure by remember { mutableStateOf(initial?.nullableScore("self_score")) }
    var step by remember { mutableIntStateOf(0) }
    val faces = listOf(R.drawable.ic_wellbeing_angry, R.drawable.ic_wellbeing_frown,
        R.drawable.ic_wellbeing_meh, R.drawable.ic_wellbeing_smile, R.drawable.ic_wellbeing_laugh)
    val labels = listOf("难受", "低落", "平静", "还好", "开心")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).heightIn(max = 680.dp), shape = RoundedCornerShape(28.dp), color = WellbeingBackground) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("每日打卡  ${step + 1} / 2", color = SageDark, style = MaterialTheme.typography.labelLarge)
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("稍后") }
                }
                Text(if (step == 0) "今天的你，\n感觉怎样？" else "今天有多大压力？", style = MaterialTheme.typography.headlineSmall)
                Text(if (step == 0) "不用想太久，选最像此刻的感受。" else "心情和压力可以不同，按你的感受来。", color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                if (step == 0) {
                    WellbeingCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            faces.forEachIndexed { index, icon ->
                                val active = mood == index + 1
                                Surface(onClick = { if (!busy) mood = index + 1 }, modifier = Modifier.weight(1f),
                                    color = if (active) SagePale else Color.White, shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, if (active) SageDark else Color.Transparent)) {
                                    Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(painterResource(icon), labels[index], tint = Color.Unspecified, modifier = Modifier.size(28.dp))
                                        Text(labels[index], style = MaterialTheme.typography.labelMedium, color = if (active) SageDark else InkMuted)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    listOf("很轻松", "一点点压力", "有负担，还能应对", "负担比较大", "现在很难承受").forEachIndexed { index, label ->
                        WellbeingChoice(label, pressure == index * 25, { if (!busy) pressure = index * 25 })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step == 1) OutlinedButton(enabled = !busy, onClick = { step = 0 }) { Text("上一步") }
                    Button(enabled = !busy && (if (step == 0) mood != null else mood != null && pressure != null),
                        modifier = Modifier.weight(1f), onClick = { if (step == 0) step = 1 else onSave(mood!!, pressure!!) }) {
                        Text(if (busy) "保存中…" else if (step == 0) "下一步" else "记下今天")
                    }
                }
            }
        }
    }
}

@Composable
fun WellbeingScreen(onBack: () -> Unit, onSupportCircle: () -> Unit) {
    val context = LocalContext.current
    val device = remember { DeviceIdStore(context.applicationContext).get() }
    val api = remember { WellbeingApiClient() }
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf<JSONObject?>(null) }
    var days by remember { mutableIntStateOf(7) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf("overview") }
    var showCheck by remember { mutableStateOf(false) }
    var threshold by remember { mutableFloatStateOf(70f) }
    var weight by remember { mutableFloatStateOf(70f) }
    var settingSaved by remember { mutableStateOf(false) }
    var explain by remember { mutableStateOf(false) }
    fun back() { if (page == "overview") onBack() else { page = "overview"; error = null } }
    BackHandler { back() }
    suspend fun reload() {
        loading = true
        runCatching {
            val current = api.request("today", device)
            val trend = api.request("history?days=$days", device)
            current to trend
        }.onSuccess { (current, trend) ->
            data = current; history = trend; error = null
            threshold = current.getJSONObject("preferences").optInt("reminder_threshold", 70).toFloat()
            weight = current.getJSONObject("preferences").optInt("self_weight", 70).toFloat()
        }.onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(days) { reload() }
    if (page == "contacts") { TrustedContactScreen { page = "overview" }; return }
    if (page == "selfcheck") {
        WellbeingSelfCheck(device, api, onDismiss = { page = "overview" }, onSupport = { page = "overview"; onSupportCircle() })
        return
    }
    val today = data?.optJSONObject("today")
    val score = today?.nullableScore("score")
    if (showCheck) CheckInDialog(today, busy, error, { if (!busy) showCheck = false }) { mood, pressure ->
        busy = true
        scope.launch {
            runCatching { api.request("check-in", device, "POST", JSONObject().put("mood", mood).put("pressure", pressure)) }
                .onSuccess { showCheck = false; reload() }.onFailure { error = it.message }
            busy = false
        }
    }
    WellbeingPage(if (page == "settings") "压力偏好" else "压力温度", ::back, action = {
        if (page == "overview") TextButton(onClick = { scope.launch { reload() } }, enabled = !loading && !busy) { Text("刷新") }
    }) {
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (page == "settings") {
            WellbeingCard {
                WellbeingIcon(R.drawable.ic_wellbeing_sliders_horizontal)
                Text("按你的感受微调", style = MaterialTheme.typography.titleLarge)
                Text("日常提醒线  ${threshold.toInt()}", style = MaterialTheme.typography.titleMedium)
                Slider(value = threshold, onValueChange = { threshold = it; settingSaved = false }, enabled = !busy && !loading,
                    valueRange = 50f..90f, steps = 7)
                Text("自评占比  ${weight.toInt()}%", style = MaterialTheme.typography.titleMedium)
                Slider(value = weight, onValueChange = { weight = it; settingSaved = false }, enabled = !busy && !loading,
                    valueRange = 50f..90f, steps = 7)
                Text("从今天起生效，不影响危险信号识别。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                Button(enabled = !busy && !loading && data != null, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true
                    scope.launch {
                        runCatching { api.request("preferences", device, "PATCH", JSONObject().put("reminder_threshold", threshold.toInt()).put("self_weight", weight.toInt())) }
                            .onSuccess { reload(); settingSaved = true }.onFailure { error = it.message }
                        busy = false
                    }
                }) { Text(if (settingSaved) "已保存" else "保存偏好") }
            }
            WellbeingCard {
                TextButton(onClick = { explain = !explain }) { Text(if (explain) "收起计算说明" else "压力分数怎么来？  ›") }
                if (explain) {
                    Text("综合值由自评与 AI 参考加权计算。只有一种来源时单独采用，缺失不计为 0。", color = InkMuted)
                    Text("当天明确表达本人当前伤害意图时，当日参考值至少为 90，避免被平均值掩盖。该标记保留至当天结束；删除对应记录后重新计算。这是产品记录规则，不是危险概率。", color = InkMuted)
                    Text("AI 参考你的对话、文字记录及照片说明，不根据照片里的外貌推断心理状态。", color = InkMuted)
                    Text("蓝色低于 40，黄色 40–69，红色 70 及以上。提醒线只控制日常提醒。分数不是诊断或危险概率。", color = InkMuted)
                }
            }
        } else {
            WellbeingCard {
                Text(today?.optString("date").orEmpty().ifBlank { "今天" }, style = MaterialTheme.typography.labelLarge, color = InkMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PressureGauge(score, Modifier.size(48.dp, 118.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(score?.toString() ?: "—", style = MaterialTheme.typography.displayLarge, color = scoreColor(score))
                        Text(scoreLabel(score), style = MaterialTheme.typography.titleSmall)
                    }
                    Image(painterResource(R.drawable.duck_wellbeing_pause), null, Modifier.size(80.dp))
                }
                Surface(color = Color(0xFFF0F5F7), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("自评  " + (today?.nullableScore("self_score")?.toString() ?: "未填写"), style = MaterialTheme.typography.bodyMedium)
                        Text("AI 参考  " + (today?.nullableScore("ai_score")?.toString() ?: "暂无"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (today?.optString("score_basis") == "daily_strong_expression") {
                    Text("含当日强烈困扰表达 · 参考值已提高", color = InkMuted, style = MaterialTheme.typography.bodySmall)
                } else if (today?.optBoolean("disagreement") == true) Text("两种感受有差异，以你的体验为重。", color = SageDark, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { showCheck = true }, enabled = !loading && !busy && data != null, modifier = Modifier.fillMaxWidth()) {
                    Text(if (today?.isNull("mood") != false) "给今天打个卡" else "更新今日感受")
                }
            }
            WellbeingCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_wellbeing_chart_no_axes_combined), null, tint = Color.Unspecified, modifier = Modifier.size(22.dp))
                    Text("压力变化", style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7 to "一周", 30 to "一月", 365 to "一年").forEach { (count, label) ->
                        FilterChip(selected = days == count, enabled = !loading, onClick = { days = count }, label = { Text(label) })
                    }
                }
                PressureChart(history?.optJSONArray("points") ?: JSONArray())
                Text("已记录 ${history?.optInt("coverage") ?: 0} / $days 天 · 空白表示未记录", color = InkMuted, style = MaterialTheme.typography.bodySmall)
            }
            WellbeingEntry(R.drawable.ic_wellbeing_notebook_pen, "生活影响自查",
                if (score != null && score >= (data?.optJSONObject("preferences")?.optInt("reminder_threshold", 70) ?: 70)) "今天负担较重，看看生活受到哪些影响"
                else "4 个小问题，了解最近的自己") { page = "selfcheck" }
            WellbeingEntry(R.drawable.ic_wellbeing_users, "可信联系人", "绑定与安全提醒") { page = "contacts" }
            WellbeingEntry(R.drawable.ic_wellbeing_sliders_horizontal, "压力偏好", "调整提醒线和自评占比") { page = "settings" }
            Text("仅作日常自我记录，不是医学诊断。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
}

@Composable
private fun PressureChart(points: JSONArray) {
    val values = (0 until points.length()).map { points.getJSONObject(it).nullableScore("score") }
    Row {
        Column(Modifier.height(160.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("100", "50", "0").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = InkMuted) }
        }
        Canvas(Modifier.weight(1f).height(160.dp).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)
            .semantics { contentDescription = values.mapIndexedNotNull { i, v -> v?.let { points.getJSONObject(i).optString("date") + "，压力" + it } }.joinToString("；").ifBlank { "暂无压力趋势数据" } }) {
            listOf(0f, .5f, 1f).forEach { y -> drawLine(Color(0xFFE3E7E1), Offset(0f, size.height * y), Offset(size.width, size.height * y), 1.dp.toPx()) }
            var previous: Offset? = null
            values.forEachIndexed { i, value ->
                if (value == null) { previous = null } else {
                    val point = Offset(size.width * i / (values.size - 1).coerceAtLeast(1), size.height * (1 - value / 100f))
                    previous?.let { drawLine(Color(0xFF6B968E), it, point, 2.dp.toPx()) }
                    drawCircle(scoreColor(value), if (values.size > 30) 2.dp.toPx() else 4.dp.toPx(), point)
                    previous = point
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.optJSONObject(0)?.optString("date").orEmpty(), style = MaterialTheme.typography.labelSmall)
        Text(points.optJSONObject(points.length() - 1)?.optString("date").orEmpty(), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun WellbeingSelfCheck(device: String, api: WellbeingApiClient, onDismiss: () -> Unit, onSupport: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var counselingUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { api.request("resources", device) }.onSuccess {
            counselingUrl = it.optString("counseling_url").takeIf { url -> url.startsWith("https://") }
        }
    }
    var answers by remember { mutableStateOf(listOf<Int?>(null, null, null)) }
    var danger by remember { mutableStateOf<Boolean?>(null) }
    var step by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun back() { if (feedback != null) feedback = null else if (step > 0) step-- else onDismiss() }
    BackHandler { if (!busy) back() }
    WellbeingPage("生活影响自查", { if (!busy) back() }) {
        if (feedback == null) {
            Text("给自己一点时间", style = MaterialTheme.typography.headlineSmall)
            Text("回想最近一周，按实际感受选择。", color = InkMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${step + 1} / 4", color = SageDark, style = MaterialTheme.typography.labelLarge)
                Text("约 1 分钟", color = InkMuted, style = MaterialTheme.typography.labelMedium)
            }
            LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
            WellbeingCard {
                val icons = listOf(R.drawable.ic_wellbeing_moon, R.drawable.ic_wellbeing_notebook_pen,
                    R.drawable.ic_wellbeing_sparkles, R.drawable.ic_wellbeing_shield_check)
                WellbeingIcon(icons[step], background = Color(0xFFE8F0F6))
                Text(listOf("睡眠受到影响了吗？", "学习、工作或生活受影响了吗？", "靠已有的办法，很难缓解吗？", "此刻，你或他人有立即受到伤害的危险吗？")[step],
                    style = MaterialTheme.typography.titleLarge)
                if (step < 3) listOf("没有", "偶尔", "经常", "几乎一直").forEachIndexed { value, label ->
                    WellbeingChoice(label, answers[step] == value, {
                        if (!busy) answers = answers.toMutableList().also { it[step] = value }
                    })
                } else {
                    WellbeingChoice("没有", danger == false, { if (!busy) danger = false })
                    WellbeingChoice("有，或不确定", danger == true, { if (!busy) danger = true })
                }
            }
            if (danger == true) WellbeingCard(TerracottaPale) {
                Text("先找信任的人陪着你。立即危险时请联系当地紧急服务，不必等自查完成。")
                TextButton(onClick = onSupport) { Text("现在找人陪伴  ›") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) OutlinedButton(enabled = !busy, onClick = { step-- }) { Text("上一题") }
                Button(enabled = !busy && (if (step < 3) answers[step] != null else danger != null),
                    modifier = Modifier.weight(1f), onClick = {
                        if (step < 3) step++ else {
                            busy = true; error = null
                            scope.launch {
                                runCatching { api.request("self-check", device, "POST", JSONObject().put("sleep", answers[0])
                                    .put("daily_impact", answers[1]).put("coping", answers[2]).put("immediate_danger", danger)) }
                                    .onSuccess { feedback = it.optString("feedback") }.onFailure { error = it.message }
                                busy = false
                            }
                        }
                    }) { Text(if (busy) "整理中…" else if (step < 3) "下一题" else "查看反馈") }
            }
            TextButton(onClick = onSupport) { Text("我想先找人聊聊  ›") }
            Text("日常自查，不是诊断或标准化心理测验。", style = MaterialTheme.typography.bodySmall, color = InkMuted)
        } else {
            WellbeingCard {
                WellbeingIcon(R.drawable.ic_wellbeing_heart)
                Text("听听自己的需要", style = MaterialTheme.typography.titleLarge)
                Text(feedback.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            }
            WellbeingEntry(R.drawable.ic_wellbeing_users, "找一个人聊聊", "去支持圈联系可信的人") { onSupport() }
            counselingUrl?.let { url ->
                WellbeingEntry(R.drawable.ic_wellbeing_heart_handshake, "专业咨询", "打开预约入口") {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        .onFailure { error = "暂时无法打开预约页，请稍后再试。" }
                }
            } ?: WellbeingCard {
                Text("也可以寻求专业支持", style = MaterialTheme.typography.titleMedium)
                Text("联系学校心理中心或正规医疗机构。", color = InkMuted)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成自查") }
        }
    }
}
