package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.remote.SupportApiClient
import com.testconnection.confidence_agent.data.remote.SupportFeedbackRecord
import com.testconnection.confidence_agent.data.remote.SupportPerson
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.TerracottaPale
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import com.testconnection.confidence_agent.ui.theme.WarmWhite
import kotlinx.coroutines.launch

private val kindLabels = listOf(
    "teacher" to "老师", "senior" to "师兄师姐", "classmate" to "同学", "friend" to "朋友",
    "family" to "家人", "professional" to "专业人士",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportCircleScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val deviceId = remember { DeviceIdStore(context.applicationContext).get() }
    val api = remember { SupportApiClient() }
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf<List<SupportPerson>>(emptyList()) }
    var feedback by remember { mutableStateOf<List<SupportFeedbackRecord>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<SupportPerson?>(null) }
    var showingEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SupportPerson?>(null) }
    var name by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("classmate") }
    var scenarios by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            loading = true
            runCatching { api.people(deviceId) }
                .onSuccess { people = it; error = null }
                .onFailure { error = "支持圈暂时无法加载，请稍后重试。" }
            runCatching { api.feedbackHistory(deviceId) }.onSuccess { feedback = it }
            loading = false
        }
    }
    fun openEditor(person: SupportPerson?) {
        editing = person
        name = person?.name.orEmpty()
        relationship = person?.relationship.orEmpty()
        kind = person?.kind ?: "classmate"
        scenarios = person?.scenarios?.joinToString("、").orEmpty()
        showingEditor = true
    }

    LaunchedEffect(Unit) { reload() }

    if (showingEditor) {
        AlertDialog(
            onDismissRequest = { showingEditor = false },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (editing == null) "添加一位支持者" else "修改支持者", fontWeight = FontWeight.Bold)
                    Text("只填写你愿意记住的信息", style = MaterialTheme.typography.bodySmall, color = InkMuted)
                }
            },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("称呼") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = relationship, onValueChange = { relationship = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("与你的关系") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                        )
                    }
                    item {
                        Text("支持者类型", style = MaterialTheme.typography.labelLarge, color = SageDark)
                        Spacer(Modifier.height(7.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            kindLabels.forEach { (value, label) ->
                                FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(label) })
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = scenarios, onValueChange = { scenarios = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("适合帮忙的场景") }, placeholder = { Text("如：做决定、情绪低落、专业问题") },
                            minLines = 2, shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showingEditor = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && relationship.isNotBlank()) scope.launch {
                        val person = SupportPerson(
                            editing?.id ?: 0,
                            name.trim(),
                            relationship.trim(),
                            kind,
                            scenarios.split('、', ',', '，').map(String::trim).filter(String::isNotBlank),
                        )
                        runCatching { api.savePerson(deviceId, person) }
                            .onSuccess { showingEditor = false; reload() }
                            .onFailure { error = "保存失败，请检查内容后重试。" }
                    }
                }) { Text("保存", color = SageDark) }
            },
        )
    }

    deleting?.let { person ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移除${person.name}？") },
            text = { Text("这会将对方从支持圈移除；已经留下的求助反馈仍会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { api.deletePerson(deviceId, person.id) }
                            .onSuccess { deleting = null; reload() }
                            .onFailure { error = "移除失败，请重试。" }
                    }
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    Column(Modifier.fillMaxSize().background(Cream).statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("支持圈", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SupportHero() }

            error?.let { message ->
                item {
                    Surface(color = TerracottaPale, shape = RoundedCornerShape(16.dp)) {
                        Text(message, Modifier.fillMaxWidth().padding(13.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            item {
                SectionTitle(
                    title = "我的支持者",
                    subtitle = if (people.isEmpty()) "把可以求助的人放在看得见的位置" else "${people.size} 位可以联系的人",
                )
            }

            if (loading && people.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (people.isEmpty()) {
                item {
                    WhitePanel {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("还没有添加支持者", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("可以先记下一个你愿意在困难时联系的人。", color = InkMuted)
                        }
                    }
                }
            } else {
                items(people, key = { it.id }) { person ->
                    SupportPersonCard(person, onEdit = { openEditor(person) }, onRemove = { deleting = person })
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { openEditor(null) },
                        modifier = Modifier.widthIn(min = 146.dp).height(46.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                    ) { Text("＋ 添加支持者") }
                }
            }

            if (feedback.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(4.dp))
                    SectionTitle("求助后的回声", "不只记录结果，也记住你主动迈出的那一步")
                }
                items(feedback, key = { it.id }) { item -> SupportFeedbackCard(item) }
            }
        }
    }
}

@Composable
private fun SupportHero() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SagePale,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, WarmOutline),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(18.dp)) {
            if (maxWidth < 300.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroCopy()
                    DuckArt(R.drawable.duck_story_guidance, "小鸭向同路人请教", Modifier.fillMaxWidth().height(150.dp))
                    PrivacyNote()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HeroCopy(Modifier.weight(1f))
                        DuckArt(R.drawable.duck_story_guidance, "小鸭向同路人请教", Modifier.size(142.dp))
                    }
                    PrivacyNote()
                }
            }
        }
    }
}

@Composable
private fun HeroCopy(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("求助，也是向前的一步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("先想清楚谁适合听、谁能给建议，需要时就不用一个人硬撑。", color = InkMuted)
    }
}

@Composable
private fun PrivacyNote() {
    Surface(color = WarmWhite, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(25.dp).background(SageDark, CircleShape), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(9.dp))
            Text("只保存你主动填写的信息，不读取通讯录，也不会自动联系任何人。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
    }
}

@Composable
private fun WhitePanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WarmWhite,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, WarmOutline),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportPersonCard(person: SupportPerson, onEdit: () -> Unit, onRemove: () -> Unit) {
    WhitePanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(SagePale),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(person.name.take(1), style = MaterialTheme.typography.titleLarge, color = SageDark, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(person.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(person.relationship, style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                }
                Surface(color = SagePale, shape = RoundedCornerShape(10.dp)) {
                    Text(kindLabel(person.kind), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = SageDark)
                }
            }
            HorizontalDivider(color = WarmOutline)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("适合求助的场景", style = MaterialTheme.typography.labelLarge, color = SageDark)
                if (person.scenarios.isEmpty()) {
                    Text("还没有填写，可以修改补充。", style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        person.scenarios.forEach { scenario ->
                            Surface(color = Cream, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, WarmOutline)) {
                                Text(scenario, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRemove) { Text("移除", color = InkMuted) }
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(13.dp)) { Text("修改") }
            }
        }
    }
}

@Composable
private fun SupportFeedbackCard(item: SupportFeedbackRecord) {
    val outcome = when (item.outcome) {
        "helped" -> "得到了帮助"
        "not_helped" -> "这次没帮到"
        else -> "还没有联系"
    }
    val badgeColor = if (item.outcome == "helped") SagePale else TerracottaPale
    WhitePanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.supporterName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(color = badgeColor, shape = RoundedCornerShape(10.dp)) {
                    Text(outcome, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = SageDark)
                }
            }
            item.ownEffort?.let { FeedbackLine("我的尝试", it) }
            item.supportReceived?.let { FeedbackLine("收到的支持", it) }
            Text(item.createdAt.take(10), style = MaterialTheme.typography.bodySmall, color = InkMuted)
        }
    }
}

@Composable
private fun FeedbackLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(82.dp), style = MaterialTheme.typography.bodySmall, color = InkMuted)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun kindLabel(kind: String): String = kindLabels.firstOrNull { it.first == kind }?.second ?: kind
