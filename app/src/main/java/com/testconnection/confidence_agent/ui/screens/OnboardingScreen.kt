package com.testconnection.confidence_agent.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.testconnection.confidence_agent.R
import com.testconnection.confidence_agent.data.model.ProfileValue
import com.testconnection.confidence_agent.data.model.UserProfile
import com.testconnection.confidence_agent.ui.components.AppButtonShape
import com.testconnection.confidence_agent.ui.components.DuckArt
import com.testconnection.confidence_agent.ui.components.WarmCard
import com.testconnection.confidence_agent.ui.theme.ConfidenceAgentTheme
import com.testconnection.confidence_agent.ui.theme.Cream
import com.testconnection.confidence_agent.ui.theme.Ink
import com.testconnection.confidence_agent.ui.theme.InkMuted
import com.testconnection.confidence_agent.ui.theme.SageDark
import com.testconnection.confidence_agent.ui.theme.SagePale
import com.testconnection.confidence_agent.ui.theme.WarmOutline
import com.testconnection.confidence_agent.ui.theme.WarmWhite

private fun ProfileValue.choice(): String = when (value) {
    "unknown" -> ""
    "prefer_not_to_say" -> "暂不透露"
    else -> value
}

private fun chosenValue(choice: String, previous: ProfileValue): ProfileValue {
    val value = when (choice.trim()) {
        "" -> "unknown"
        "暂不透露" -> "prefer_not_to_say"
        else -> choice.trim()
    }
    return if (value == previous.value) previous
    else ProfileValue(value, if (value == "unknown") 0.0 else 1.0)
}

@Composable
fun OnboardingScreen(
    initialProfile: UserProfile = UserProfile(),
    onComplete: (UserProfile) -> Unit,
    onSkip: () -> Unit,
) = OnboardingTagFlow(initialProfile, onComplete, onSkip)

@Composable
private fun OnboardingTagFlow(
    initialProfile: UserProfile,
    onComplete: (UserProfile) -> Unit,
    onSkip: () -> Unit,
    initialStep: Int = 0,
) {
    var step by rememberSaveable { mutableStateOf(initialStep) }
    var identity by rememberSaveable { mutableStateOf(initialProfile.lifeStage.choice()) }
    var gender by rememberSaveable { mutableStateOf(initialProfile.gender.choice()) }
    var age by rememberSaveable { mutableStateOf(initialProfile.ageRange.choice()) }
    var name by rememberSaveable { mutableStateOf(initialProfile.preferredName.choice()) }
    var context by rememberSaveable { mutableStateOf(initialProfile.currentContext.choice()) }
    var challenge by rememberSaveable { mutableStateOf(initialProfile.mainChallenge.choice()) }
    var support by rememberSaveable { mutableStateOf(initialProfile.preferredSupportStyle.choice()) }
    var showMore by rememberSaveable { mutableStateOf(age.isNotBlank() || name.isNotBlank()) }

    BackHandler(enabled = step > 0) { step = 0 }

    fun complete() {
        onComplete(initialProfile.copy(
            preferredName = chosenValue(name, initialProfile.preferredName),
            gender = chosenValue(gender, initialProfile.gender),
            ageRange = chosenValue(age, initialProfile.ageRange),
            lifeStage = chosenValue(identity, initialProfile.lifeStage),
            currentContext = chosenValue(context, initialProfile.currentContext),
            mainChallenge = chosenValue(challenge, initialProfile.mainChallenge),
            preferredSupportStyle = chosenValue(support, initialProfile.preferredSupportStyle),
        ))
    }

    Column(
        Modifier.fillMaxSize().background(Cream)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("鸭力Go", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("第一次见面", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            }
            TextButton(onClick = onSkip) { Text("暂时跳过", color = InkMuted) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(2) { index ->
                Surface(
                    Modifier.weight(1f).height(4.dp),
                    color = if (index <= step) SageDark else WarmOutline,
                    shape = RoundedCornerShape(8.dp),
                ) {}
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (step == 0) "认识你，从小标签开始" else "想怎样一起向前？",
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp),
                            fontWeight = FontWeight.Bold, color = Ink)
                        Text(if (step == 0) "选你愿意分享的就好，\n我们可以慢慢熟悉。" else "按现在的感觉选，\n以后也可以改变。",
                            style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                    }
                    DuckArt(R.drawable.duck_listening, "陪你慢慢开始的小鸭", Modifier.size(88.dp))
                }
            }
            if (step == 0) {
                item {
                    TagChoices("你现在的身份", "选一个最接近的", listOf("高中生", "大学生", "研究生", "职场人", "自由职业", "正在探索"), identity) { identity = it }
                }
                item {
                    TagChoices("性别", "不想说也没关系", listOf("女生", "男生", "其他", "暂不透露"), gender) { gender = it }
                }
                item {
                    TextButton(onClick = { showMore = !showMore }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(if (showMore) "收起补充信息 −" else "再补充一点（选填）＋", color = SageDark)
                    }
                }
                if (showMore) {
                    item {
                        TagChoices("年龄阶段", "不必填写具体生日", listOf("18岁以下", "18—22岁", "23—29岁", "30岁及以上", "暂不透露"), age) { age = it }
                    }
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(20) },
                            label = { Text("希望怎么称呼你（选填）") },
                            placeholder = { Text("留空也可以") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                        )
                    }
                }
            } else {
                item {
                    TagChoices("最近在忙些什么", "最多选3项", listOf("考研准备", "学业课程", "求职工作", "适应新环境", "日常生活", "还没想好"), context, maxChoices = 3) { context = it }
                }
                item {
                    TagChoices("想慢慢改变什么", "最多选3项", listOf("更敢表达", "少一点自我怀疑", "找到自己的节奏", "学会向人求助", "照顾好自己", "先随便聊聊"), challenge, maxChoices = 3) { challenge = it }
                }
                item {
                    TagChoices("喜欢怎样的陪伴", "选一个现在更喜欢的", listOf("先听我说", "一起梳理思路", "给一点小建议", "多一点鼓励", "按当时需要来"), support) { support = it }
                }
            }
        }
        Surface(color = Cream, shadowElevation = 3.dp) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("所有选项都可跳过，之后可在记忆中心修改", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) TextButton(onClick = { step = 0 }) { Text("上一步", color = SageDark) }
                    Button(
                        onClick = { if (step == 0) step = 1 else complete() },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        shape = AppButtonShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SageDark),
                    ) { Text(if (step == 0) "下一步" else "和小鸭一起出发", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChoices(
    title: String,
    hint: String,
    options: List<String>,
    value: String,
    maxChoices: Int = 1,
    onChange: (String) -> Unit,
) {
    val selected = value.split("、").filter(String::isNotBlank)
    val visibleOptions = (options + selected).distinct()
    WarmCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(hint, style = MaterialTheme.typography.labelMedium, color = InkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                visibleOptions.forEach { option ->
                    val checked = option in selected
                    FilterChip(
                        selected = checked,
                        onClick = {
                            val next = when {
                                checked -> selected - option
                                maxChoices == 1 -> listOf(option)
                                selected.size < maxChoices -> selected + option
                                else -> selected
                            }
                            onChange(next.joinToString("、"))
                        },
                        label = { Text((if (checked) "✓ " else "") + option, style = MaterialTheme.typography.bodyMedium) },
                        modifier = Modifier.heightIn(min = 44.dp),
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = WarmWhite, labelColor = Ink,
                            selectedContainerColor = SagePale, selectedLabelColor = SageDark,
                        ),
                        border = BorderStroke(1.dp, if (checked) SageDark else WarmOutline),
                    )
                }
            }
            if (maxChoices > 1 && selected.size >= maxChoices) {
                Text("已选满${maxChoices}项，点一下已选标签可取消", style = MaterialTheme.typography.labelSmall, color = SageDark)
            }
        }
    }
}

@Preview(name = "首次见面 标签选择", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingIdentityPreview() {
    ConfidenceAgentTheme { OnboardingTagFlow(UserProfile(), {}, {}) }
}

@Preview(name = "首次见面 小屏幕", showBackground = true, widthDp = 320, heightDp = 640)
@Composable
private fun OnboardingSmallScreenPreview() {
    ConfidenceAgentTheme { OnboardingTagFlow(UserProfile(), {}, {}) }
}

@Preview(name = "首次见面 陪伴偏好", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun OnboardingPreferencesPreview() {
    ConfidenceAgentTheme {
        OnboardingTagFlow(UserProfile(currentContext = ProfileValue("考研准备", 1.0)), {}, {}, initialStep = 1)
    }
}
