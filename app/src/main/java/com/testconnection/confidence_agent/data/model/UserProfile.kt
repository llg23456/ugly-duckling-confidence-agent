package com.testconnection.confidence_agent.data.model

import org.json.JSONObject

data class ProfileValue(val value: String = "unknown", val certainty: Double = 0.0)

data class UserProfile(
    val preferredName: ProfileValue = ProfileValue(),
    val gender: ProfileValue = ProfileValue(),
    val ageRange: ProfileValue = ProfileValue(),
    val lifeStage: ProfileValue = ProfileValue(),
    val currentContext: ProfileValue = ProfileValue(),
    val mainChallenge: ProfileValue = ProfileValue(),
    val preferredSupportStyle: ProfileValue = ProfileValue(),
    val importantSupporters: ProfileValue = ProfileValue(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("preferred_name", preferredName.toJson())
        put("gender", gender.toJson())
        put("age_range", ageRange.toJson())
        put("life_stage", lifeStage.toJson())
        put("current_context", currentContext.toJson())
        put("main_challenge", mainChallenge.toJson())
        put("preferred_support_style", preferredSupportStyle.toJson())
        put("important_supporters", importantSupporters.toJson())
    }

    fun displayItems(): List<Pair<String, String>> = listOf(
        "称呼" to preferredName.value,
        "性别表达" to gender.value,
        "年龄阶段" to ageRange.value,
        "当前阶段" to lifeStage.value,
        "最近的生活" to currentContext.value,
        "想慢慢改变" to mainChallenge.value,
        "喜欢的陪伴" to preferredSupportStyle.value,
        "支持我的人" to importantSupporters.value,
    ).filter { it.second != "unknown" }

    companion object {
        fun fromJson(json: JSONObject): UserProfile = UserProfile(
            preferredName = json.profileValue("preferred_name"),
            gender = json.profileValue("gender"),
            ageRange = json.profileValue("age_range"),
            lifeStage = json.profileValue("life_stage"),
            currentContext = json.profileValue("current_context"),
            mainChallenge = json.profileValue("main_challenge"),
            preferredSupportStyle = json.profileValue("preferred_support_style"),
            importantSupporters = json.profileValue("important_supporters"),
        )
    }
}

private fun ProfileValue.toJson() = JSONObject().put("value", value).put("certainty", certainty)

private fun JSONObject.profileValue(key: String): ProfileValue {
    val item = optJSONObject(key) ?: return ProfileValue()
    return ProfileValue(item.optString("value", "unknown"), item.optDouble("certainty", 0.0))
}
