package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.CheckInApiClient
import com.testconnection.confidence_agent.data.remote.ProactiveCheckIn
import com.testconnection.confidence_agent.data.remote.StressAlertDetail
import com.testconnection.confidence_agent.data.remote.StressApiClient
import com.testconnection.confidence_agent.data.remote.StressTrendPoint
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.SectionHeading
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.Sage
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.Terracotta
import com.testconnection.confidence_agent.ui.theme.TerracottaPale
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.launch

private const val PAGE_OVERVIEW = "overview"
private const val PAGE_EVIDENCE = "evidence"
private const val PAGE_SELF_CHECK = "self_check"
private const val PAGE_SUPPORT = "support"

@Composable
fun StressAlertScreen(
    alertId: Long,
    onBack: () -> Unit,
    onOpenSupportCircle: () -> Unit,
    onAlertChanged: (StressAlertDetail?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    val api = remember { StressApiClient() }
    val checkInApi = remember { CheckInApiClient() }
    var page by rememberSaveable(alertId) { mutableStateOf(PAGE_OVERVIEW) }
    var alert by remember { mutableStateOf<StressAlertDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var impact by remember { mutableStateOf<String?>(null) }
    var coping by remember { mutableStateOf<String?>(null) }
    var selfCheckNotice by remember { mutableStateOf<String?>(null) }
    var selfCheckNoticeIsError by remember { mutableStateOf(false) }
    var editableMessage by remember { mutableStateOf("") }
    var followUpCheckIn by remember { mutableStateOf<ProactiveCheckIn?>(null) }
    var followUpAcknowledgement by remember { mutableStateOf<String?>(null) }

    fun closePage() {
        if (page == PAGE_OVERVIEW) onBack() else page = PAGE_OVERVIEW
    }

    BackHandler(onBack = ::closePage)

    fun update(updated: StressAlertDetail) {
        alert = updated
        impact = updated.selfCheckImpact
        coping = updated.selfCheckCoping
        editableMessage = updated.support.editableMessage
        onAlertChanged(updated.takeIf { it.status in setOf("active", "confirmed", "follow_up_scheduled") })
    }

    fun runAction(
        action: suspend () -> StressAlertDetail,
        afterSuccess: (StressAlertDetail) -> Unit = {},
    ) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            runCatching { action() }
                .onSuccess {
                    update(it)
                    afterSuccess(it)
                }
                .onFailure { error = it.message ?: "这次操作没有完成，请稍后重试。" }
            loading = false
        }
    }

    fun saveSelfCheck(current: StressAlertDetail) {
        val selectedImpact = impact
        val selectedCoping = coping
        if (selectedImpact == null || selectedCoping == null) {
            selfCheckNotice = "请先完成两个问题，再保存自评。"
            selfCheckNoticeIsError = true
            return
        }
        if (loading) return
        loading = true
        error = null
        selfCheckNotice = "正在保存…"
        selfCheckNoticeIsError = false
        scope.launch {
            runCatching { api.submitSelfCheck(deviceId, current.id, selectedImpact, selectedCoping) }
                .onSuccess { updated ->
                    update(updated)
                    selfCheckNotice = "已保存。当前建议：${levelLabel(updated.level)}。"
                    selfCheckNoticeIsError = false
                }
                .onFailure {
                    selfCheckNotice = "保存失败，请检查后端连接后重试。"
                    selfCheckNoticeIsError = true
                }
            loading = false
        }
    }

    LaunchedEffect(alertId) {
        loading = true
        runCatching { api.detail(deviceId, alertId) }
            .onSuccess(::update)
            .onFailure { error = it.message ?: "暂时无法读取压力趋势。" }
        loading = false
    }

    val current = alert
    if (current == null) {
        StressPageList {
            item { ReportHeader("压力报告", onBack) }
            item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(color = SageDark)
                    else Text(error ?: "这条提醒已经结束。", color = InkMuted)
                }
            }
        }
        return
    }

    when (page) {
        PAGE_EVIDENCE -> StressPageList {
            item { ReportHeader("判断依据", ::closePage) }
            item { DecorativeArt(R.drawable.stress_evidence_art, "小鸭查看压力记录") }
            if (current.evidence.isEmpty()) {
                item { Text("暂时没有可展示的记录。", color = InkMuted) }
            } else {
                items(current.evidence, key = { it.eventId }) { evidence ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, WarmOutline),
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${evidence.date.takeLast(5)} · ${evidence.sourceType}",
                                color = SageDark,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                evidence.excerpt.trim(),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            error?.let { item { InlineNotice(it, isError = true) } }
        }

        PAGE_SELF_CHECK -> StressPageList {
            item { ReportHeader("快速自评", ::closePage) }
            item { DecorativeArt(R.drawable.stress_self_check_art, "小鸭做压力自评") }
            item {
                WarmCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1. 压力影响日常生活吗？", style = MaterialTheme.typography.titleMedium)
                        ChoiceColumn(
                            options = listOf(
                                "none" to "几乎没有",
                                "some" to "有一些",
                                "significant" to "比较明显",
                            ),
                            selected = impact,
                            onSelect = {
                                impact = it
                                selfCheckNotice = null
                            },
                        )
                        Text("2. 现在还能应付吗？", style = MaterialTheme.typography.titleMedium)
                        ChoiceColumn(
                            options = listOf(
                                "manageable" to "基本能",
                                "strained" to "有些吃力",
                                "overwhelmed" to "很难应付",
                            ),
                            selected = coping,
                            onSelect = {
                                coping = it
                                selfCheckNotice = null
                            },
                        )
                        Button(
                            onClick = { saveSelfCheck(current) },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                        ) {
                            Text(
                                when {
                                    loading -> "正在保存…"
                                    current.selfCheckImpact != null -> "更新自评"
                                    else -> "保存自评"
                                },
                            )
                        }
                        selfCheckNotice?.let { InlineNotice(it, isError = selfCheckNoticeIsError) }
                            ?: if (current.selfCheckImpact != null && current.selfCheckCoping != null) {
                                InlineNotice("上次自评已保存。", isError = false)
                            } else Unit
                    }
                }
            }
        }

        PAGE_SUPPORT -> StressPageList {
            item { ReportHeader("支持与回访", ::closePage) }
            item { DecorativeArt(R.drawable.stress_support_art, "小鸭提供支持") }
            item {
                WarmCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        SectionHeading("现在可以做什么")
                        Text(current.support.smallStep, style = MaterialTheme.typography.bodyLarge)
                        Text("可以联系：${current.support.supporterName}", color = SageDark)
                        OutlinedTextField(
                            value = editableMessage,
                            onValueChange = { editableMessage = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("求助话术") },
                            minLines = 2,
                        )
                        OutlinedButton(
                            onClick = onOpenSupportCircle,
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppButtonShape,
                        ) { Text("查看支持圈") }
                    }
                }
            }
            item {
                WarmCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeading("后续回访")
                        val followUp = current.followUp
                        if (followUp == null) {
                            Button(
                                onClick = {
                                    runAction(action = { api.scheduleFollowUp(deviceId, current.id) })
                                },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                                shape = AppButtonShape,
                                colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                            ) { Text("明天再问问我") }
                        } else {
                            Text("已安排明天回访。", color = SageDark)
                            OutlinedButton(
                                onClick = {
                                    if (loading) return@OutlinedButton
                                    loading = true
                                    error = null
                                    scope.launch {
                                        runCatching {
                                            val updated = if (followUp.ready) current
                                            else api.makeFollowUpReady(deviceId, current.id)
                                            update(updated)
                                            updated.followUp?.takeIf { it.ready }?.let { readyFollowUp ->
                                                ProactiveCheckIn(
                                                    id = readyFollowUp.id,
                                                    reason = "stress_follow_up",
                                                    prompt = readyFollowUp.prompt,
                                                    sourceEventIds = updated.evidence.map { it.eventId },
                                                    createdAt = updated.createdAt,
                                                )
                                            }
                                        }.onSuccess { followUpCheckIn = it }
                                            .onFailure { error = it.message ?: "回访还没有到时间。" }
                                        loading = false
                                    }
                                },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                                shape = AppButtonShape,
                            ) { Text("查看后续回访") }
                        }
                        followUpCheckIn?.let { checkIn ->
                            Surface(color = SagePale, shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Text(checkIn.prompt, style = MaterialTheme.typography.bodyLarge)
                                    FollowUpChoices(enabled = !loading) { choice ->
                                        loading = true
                                        error = null
                                        scope.launch {
                                            runCatching { checkInApi.respond(deviceId, checkIn.id, choice) }
                                                .onSuccess { result ->
                                                    followUpAcknowledgement = result.acknowledgement
                                                    followUpCheckIn = null
                                                    runCatching { api.detail(deviceId, current.id) }.getOrNull()?.let(::update)
                                                }
                                                .onFailure { error = "这次回应暂时没有保存，请重试。" }
                                            loading = false
                                        }
                                    }
                                }
                            }
                        }
                        followUpAcknowledgement?.let { InlineNotice(it, isError = false) }
                        TextButton(
                            onClick = {
                                runAction(
                                    action = { api.dismiss(deviceId, current.id) },
                                    afterSuccess = {
                                        onAlertChanged(null)
                                        onBack()
                                    },
                                )
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("暂时不处理", color = InkMuted) }
                    }
                }
            }
            error?.let { item { InlineNotice(it, isError = true) } }
        }

        else -> StressPageList {
            item { ReportHeader("压力报告", onBack) }
            item {
                WarmCard {
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        StressGauge(current.level, Modifier.fillMaxWidth().height(146.dp))
                        GaugeLegend()
                    }
                }
            }
            item {
                WarmCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeading("近7天趋势")
                        StressTrendWithAxes(current.trend)
                    }
                }
            }
            item { ReportEntry("判断依据") { page = PAGE_EVIDENCE } }
            item { ReportEntry("快速自评") { page = PAGE_SELF_CHECK } }
            item { ReportEntry("支持与回访") { page = PAGE_SUPPORT } }
            error?.let { item { InlineNotice(it, isError = true) } }
        }
    }
}

@Composable
private fun StressPageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun ReportHeader(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ 返回") }
        Text(title, style = MaterialTheme.typography.displaySmall)
    }
}

@Composable
private fun DecorativeArt(drawable: Int, description: String) {
    Image(
        painter = painterResource(drawable),
        contentDescription = description,
        modifier = Modifier.fillMaxWidth().height(178.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun ReportEntry(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, WarmOutline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleLarge, color = SageDark)
        }
    }
}

@Composable
private fun InlineNotice(message: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isError) TerracottaPale else SagePale,
    ) {
        Text(
            message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            color = if (isError) Terracotta else SageDark,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ChoiceColumn(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { (value, label) ->
            OutlinedButton(
                onClick = { onSelect(value) },
                modifier = Modifier.fillMaxWidth(),
                shape = AppButtonShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected == value) SagePale else Color.Transparent,
                    contentColor = if (selected == value) SageDark else MaterialTheme.colorScheme.onSurface,
                ),
            ) { Text(label) }
        }
    }
}

@Composable
private fun FollowUpChoices(enabled: Boolean, onChoice: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf("improved" to "好一点", "talk" to "还是很重", "not_now" to "暂时不说").forEach { (value, label) ->
            OutlinedButton(
                onClick = { onChoice(value) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = AppButtonShape,
            ) { Text(label) }
        }
    }
}

@Composable
private fun GaugeLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        GaugeLegendItem(Sage, "平稳")
        GaugeLegendItem(Color(0xFFD7A84B), "需要留意")
        GaugeLegendItem(Terracotta, "压力较高")
    }
}

@Composable
private fun GaugeLegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(modifier = Modifier.size(10.dp), shape = RoundedCornerShape(50), color = color) {}
        Text(label, style = MaterialTheme.typography.labelMedium, color = InkMuted)
    }
}

@Composable
private fun StressTrendWithAxes(points: List<StressTrendPoint>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.width(38.dp).height(142.dp).padding(top = 2.dp, bottom = 11.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text("较高", style = MaterialTheme.typography.labelSmall, color = Terracotta)
            Text("留意", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB7872F))
            Text("平稳", style = MaterialTheme.typography.labelSmall, color = SageDark)
        }
        Spacer(Modifier.width(7.dp))
        StressTrendChart(points, Modifier.weight(1f).height(142.dp))
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(45.dp))
        TrendDateLabels(points, Modifier.weight(1f))
    }
}

@Composable
private fun TrendDateLabels(points: List<StressTrendPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            points.forEach { point ->
                Text(
                    point.date.takeLast(5),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Text(
            "日期",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = InkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private fun levelLabel(level: String) = if (level == "high") "压力较高" else "需要留意"

@Composable
private fun StressGauge(level: String, modifier: Modifier = Modifier) {
    var started by remember(level) { mutableStateOf(false) }
    LaunchedEffect(level) { started = true }
    val target = if (level == "high") 0.84f else 0.52f
    val progress by animateFloatAsState(
        targetValue = if (started) target else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "stressGauge",
    )
    Canvas(modifier) {
        val stroke = 19.dp.toPx()
        val radius = min(size.width / 2f - stroke, size.height - stroke * 1.7f)
        val center = Offset(size.width / 2f, size.height - stroke)
        val arcSize = Size(radius * 2, radius * 2)
        val topLeft = Offset(center.x - radius, center.y - radius)
        drawArc(Sage, 180f, 58f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(Color(0xFFD7A84B), 241f, 58f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(Terracotta, 302f, 58f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val angle = (180f + 180f * progress) * PI.toFloat() / 180f
        val end = Offset(
            center.x + cos(angle) * radius * 0.72f,
            center.y + sin(angle) * radius * 0.72f,
        )
        drawLine(SageDark, center, end, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(SageDark, 7.dp.toPx(), center)
    }
}

@Composable
private fun StressTrendChart(points: List<StressTrendPoint>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 10.dp.toPx()
        val axisY = size.height - 7.dp.toPx()
        val bottom = axisY - 12.dp.toPx()
        repeat(3) { index ->
            val y = top + (bottom - top) * index / 2f
            drawLine(WarmOutline, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        drawLine(InkMuted, Offset(left, axisY), Offset(right, axisY), strokeWidth = 1.2.dp.toPx())
        val offsets = points.mapIndexed { index, point ->
            val x = if (points.size == 1) (left + right) / 2f
            else left + (right - left) * index / (points.size - 1)
            val value = when (point.level) {
                "high" -> 2f
                "watch" -> 1f
                else -> 0f
            }
            Offset(x, bottom - (bottom - top) * value / 2f)
        }
        offsets.forEach { offset ->
            drawLine(
                InkMuted,
                Offset(offset.x, axisY - 3.dp.toPx()),
                Offset(offset.x, axisY + 3.dp.toPx()),
                strokeWidth = 1.2.dp.toPx(),
            )
        }
        val path = Path().apply {
            moveTo(offsets.first().x, offsets.first().y)
            offsets.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, SageDark, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        offsets.forEachIndexed { index, offset ->
            val color = when (points[index].level) {
                "high" -> Terracotta
                "watch" -> Color(0xFFD7A84B)
                else -> Sage
            }
            drawCircle(color, 5.dp.toPx(), offset)
        }
    }
}
