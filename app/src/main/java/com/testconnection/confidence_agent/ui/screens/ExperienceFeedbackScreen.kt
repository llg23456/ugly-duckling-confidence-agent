package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.testconnection.confidence_agent.BuildConfig
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.preferences.OnboardingStore
import com.testconnection.confidence_agent.data.remote.ExperienceFeedbackClient
import com.testconnection.confidence_agent.data.remote.InvalidFeedbackException
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.InkMuted
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class SurveyOption(val id: String, val label: String)
private data class SurveyQuestion(val id: String, val title: String, val page: Int, val kind: String,
    val options: List<SurveyOption>, val required: Boolean, val limit: Int, val exclusive: Set<String>,
    val condition: String, val maxLength: Int)

@Composable
fun ExperienceFeedbackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("experience_feedback", android.content.Context.MODE_PRIVATE) }
    val saved = remember { runCatching { JSONObject(preferences.getString("draft", "{}").orEmpty()) }.getOrDefault(JSONObject()) }
    val catalog = remember { JSONObject(context.assets.open("experience_survey.json").bufferedReader().use { it.readText() }) }
    val questions = remember {
        val raw = catalog.getJSONArray("questions")
        (0 until raw.length()).map { i ->
            val q = raw.getJSONObject(i)
            val options = q.optJSONArray("options") ?: JSONArray()
            val exclusive = q.optJSONArray("exclusive") ?: JSONArray()
            SurveyQuestion(q.getString("id"), q.getString("title"), q.getInt("page"), q.getString("kind"),
                (0 until options.length()).map { options.getJSONObject(it).let { o -> SurveyOption(o.getString("id"), o.getString("label")) } },
                q.optBoolean("required"), q.optInt("limit", 1),
                (0 until exclusive.length()).map { exclusive.getString(it) }.toSet(),
                q.optString("condition"), q.optInt("max_length", 500))
        }
    }
    val profile = remember { OnboardingStore(context).loadProfile() }
    fun clean(value: String?) = value?.takeUnless { it in setOf("unknown", "prefer_not_to_say") }.orEmpty()
    var identity by remember { mutableStateOf(listOf("label", "life_stage", "gender", "age_range").associateWith { key ->
        saved.optJSONObject("identity")?.optString(key) ?: when (key) {
            "life_stage" -> clean(profile?.lifeStage?.value)
            "gender" -> clean(profile?.gender?.value)
            "age_range" -> clean(profile?.ageRange?.value)
            else -> ""
        }
    }) }
    var answers by remember { mutableStateOf(questions.associate { q ->
        val a = saved.optJSONObject("answers")?.optJSONArray(q.id) ?: JSONArray()
        q.id to (0 until a.length()).map { a.getString(it) }
    }) }
    var page by remember { mutableIntStateOf(saved.optInt("page", 0).coerceIn(0, 2)) }
    var consent by remember { mutableStateOf(preferences.getString("pending", null) != null) }
    var pending by remember { mutableStateOf(preferences.getString("pending", null)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var receivedAt by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val locked = busy || pending != null

    fun persist() {
        val json = JSONObject().put("page", page).put("identity", JSONObject(identity))
            .put("answers", JSONObject().apply { answers.forEach { (k,v) -> put(k, JSONArray(v)) } })
        preferences.edit().putString("draft", json.toString()).apply()
    }
    fun shown(q: SurveyQuestion): Boolean = when(q.condition) {
        "experienced" -> !answers["features"].isNullOrEmpty() && "none" !in answers["features"].orEmpty()
        "has_issues" -> !answers["issues"].isNullOrEmpty() && "none" !in answers["issues"].orEmpty()
        "difficult_help" -> answers["difficult_help"]?.firstOrNull() in listOf("clear", "a_little")
        else -> true
    }
    fun change(q: SurveyQuestion, selected: List<String>) {
        answers = answers + (q.id to selected)
        if(q.id == "features") answers = answers + ("helpful_features" to answers["helpful_features"].orEmpty().filter { it in selected || it in listOf("none","unknown") })
        answers = answers.mapValues { (id,v) -> if (shown(questions.first { it.id == id })) v else emptyList() }
        error = null
        persist()
    }
    fun goBack() {
        if(page > 0 && receivedAt == null) { page--; persist() } else onBack()
    }
    BackHandler(onBack = ::goBack)
    LaunchedEffect(page) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(Cream).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::goBack, enabled = !busy) { Text("‹ 返回") }
            Text("评价与建议", style = MaterialTheme.typography.headlineSmall)
        }
        if(receivedAt != null) {
            Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("已收到，谢谢你的反馈", style = MaterialTheme.typography.headlineSmall)
                Text("好的体验和不顺手的地方，都能帮助鸭力Go变得更好。")
                Text("提交时间（北京时间）：$receivedAt", color = InkMuted)
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回我的") }
            }
        } else {
            Text("${page+1}/3 · ${listOf("使用情况", "体验感受", "问题与建议")[page]} · 约3分钟", Modifier.padding(horizontal=20.dp), color=InkMuted)
            LazyColumn(Modifier.weight(1f), state=listState,
                contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(page == 0) {
                    item {
                        Text("用过鸭力Go后，感觉怎么样？好的体验和不顺手的地方，我们都想听听。没有标准答案，也可以跳过不想回答的问题。")
                    }
                    item {
                        WarmCard {
                            Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text("确认填写时的身份", style=MaterialTheme.typography.titleMedium)
                                Text("以下信息保存为本次快照，之后修改画像不会改变这份评价。不需要填写真实姓名。", color=InkMuted)
                                OutlinedTextField(identity["label"].orEmpty(), { identity=identity+("label" to it.take(40)); persist() },
                                    label={ Text("测试代号或称呼（选填）") }, modifier=Modifier.fillMaxWidth(), enabled=!locked, singleLine=true)
                                listOf(
                                    Triple("life_stage", "当前身份", listOf("高中生","大学生","研究生","职场人","自由职业","正在探索","老师","其他","暂不透露")),
                                    Triple("gender", "性别", listOf("女生","男生","其他","暂不透露")),
                                    Triple("age_range", "年龄段", listOf("18岁以下","18—22岁","23—29岁","30岁及以上","暂不透露"))
                                ).forEach { (key,label,choices) ->
                                    Text(label + if(key=="life_stage") " *（也可选择暂不透露）" else "（选填）")
                                    SurveyChips((choices + listOfNotNull(identity[key]?.takeIf { it.isNotBlank() })).distinct().map { SurveyOption(it,it) },
                                        listOf(identity[key].orEmpty()), !locked) { chosen ->
                                        identity=identity+(key to if(identity[key]==chosen) "" else chosen); persist()
                                    }
                                }
                            }
                        }
                    }
                }
                items(questions.filter { it.page==page && shown(it) }, key={it.id}) { q ->
                    WarmCard {
                        Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(q.title + if(q.required) " *" else "", style=MaterialTheme.typography.titleMedium)
                            if(q.kind=="multi") Text("可多选，最多${q.limit}项", color=InkMuted)
                            if(q.kind=="text") {
                                OutlinedTextField(answers[q.id]?.firstOrNull().orEmpty(),{ change(q,listOf(it.take(q.maxLength))) },
                                    modifier=Modifier.fillMaxWidth(),enabled=!locked,minLines=3,maxLines=6,
                                    supportingText={ Text("最多${q.maxLength}字，请勿填写密码、邀请码或他人的个人信息。") })
                            } else {
                                val choices=q.options.filter { q.id!="helpful_features" || it.id in answers["features"].orEmpty() || it.id in listOf("none","unknown") }
                                SurveyChips(choices, answers[q.id].orEmpty(), !locked) { id ->
                                    val previous=answers[q.id].orEmpty()
                                    val next=when {
                                        id in previous -> previous-id
                                        q.kind=="single" || id in q.exclusive -> listOf(id)
                                        else -> previous.filter { it !in q.exclusive }+id
                                    }
                                    if(next.size<=q.limit || q.kind=="single") change(q,next)
                                    else error="这题最多选择${q.limit}项"
                                }
                            }
                        }
                    }
                }
                if(page==2) item {
                    Text("回答将发送到项目云端，关联你的云测设备编号，用于产品改进及不含设备编号的比赛汇总。这不是完全匿名问卷。附带问卷版本、应用版本及服务器提交时间，不自动附带聊天、记录原文或截图。你可通过“删除全部数据”删除已提交问卷。", color=InkMuted)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(consent,{consent=it},enabled=!locked)
                        Text("我同意发送本次回答，用于上述用途。", Modifier.weight(1f))
                    }
                }
                item { TextButton(enabled=!busy, onClick={
                    preferences.edit().clear().apply(); onBack()
                }) { Text("删除草稿并退出") } }
            }
            if(error!=null) Text(error!!,Modifier.padding(horizontal=20.dp),color=MaterialTheme.colorScheme.error)
            if(pending!=null) Text("上次提交结果尚未确认。为避免重复，请重试确认；填写内容已保留。",Modifier.padding(horizontal=20.dp),color=InkMuted)
            Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                if(page>0) OutlinedButton(onClick={page--;persist()},enabled=!busy) { Text("上一步") }
                Button(modifier=Modifier.weight(1f), enabled=!busy, onClick={
                    val missing=questions.firstOrNull { it.required && (it.page==page || page==2) && answers[it.id].isNullOrEmpty() }
                    when {
                        pending!=null && page<2 -> {page=2;persist()}
                        identity["life_stage"].isNullOrBlank() -> {error="请选择当前身份，也可以选择暂不透露。";page=0;persist()}
                        missing!=null -> { error="请完成：${missing.title}";page=missing.page;persist() }
                        page<2 -> {page++;persist();error=null}
                        !consent && pending==null -> error="提交前请确认并勾选用途说明。"
                        else -> {
                            if(pending==null) {
                                val jsonAnswers=JSONObject()
                                questions.filter { shown(it) }.forEach { q ->
                                    val value=answers[q.id].orEmpty()
                                    if(value.isNotEmpty() && value.any { it.isNotBlank() }) jsonAnswers.put(q.id,if(q.kind=="multi") JSONArray(value) else value.first())
                                }
                                pending=JSONObject().put("device_id",DeviceIdStore(context).get())
                                    .put("client_submission_id",UUID.randomUUID().toString()).put("survey_version",catalog.getString("version"))
                                    .put("app_version","${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                                    .put("identity",JSONObject(identity)).put("answers",jsonAnswers).put("consent",true).toString()
                                preferences.edit().putString("pending",pending).commit()
                            }
                            val payload=pending!!
                            busy=true;error=null
                            scope.launch {
                                try {
                                    val response=ExperienceFeedbackClient().submit(payload)
                                    receivedAt=OffsetDateTime.parse(response.getString("submitted_at")).atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                    preferences.edit().clear().commit();pending=null
                                } catch(e: Exception) {
                                    if(e is kotlinx.coroutines.CancellationException) throw e
                                    if(e is InvalidFeedbackException) {pending=null;preferences.edit().remove("pending").apply()}
                                    error=e.message ?: "暂时无法连接，草稿已保留，请稍后重试。"
                                } finally {busy=false}
                            }
                        }
                    }
                }) { Text(if(busy) "正在提交…" else if(page<2) "下一步" else if(pending!=null) "重试确认提交" else "提交评价") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurveyChips(options: List<SurveyOption>, selected: List<String>, enabled: Boolean, onPick: (String)->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            FilterChip(selected=option.id in selected,onClick={onPick(option.id)},enabled=enabled,
                label={Text(option.label)})
        }
    }
}
