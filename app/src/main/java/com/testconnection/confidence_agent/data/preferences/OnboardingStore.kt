package com.testconnection.confidence_agent.data.preferences

import android.content.Context
import com.testconnection.confidence_agent.data.model.UserProfile
import org.json.JSONObject

class OnboardingStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("duck_onboarding", Context.MODE_PRIVATE)

    fun shouldShow(): Boolean = !preferences.getBoolean("finished_or_skipped", false)

    fun loadProfile(): UserProfile? = preferences.getString("profile_json", null)?.let {
        runCatching { UserProfile.fromJson(JSONObject(it)) }.getOrNull()
    }

    fun saveProfile(profile: UserProfile, complete: Boolean, reason: String = "认识你") {
        preferences.edit()
            .putString("profile_json", profile.toJson().toString())
            .putBoolean("finished_or_skipped", complete)
            .apply()
        ProfileJourneyStore(context).capture(profile, reason)
    }

    fun skip() {
        preferences.edit().putBoolean("finished_or_skipped", true).putBoolean("skipped", true).apply()
    }

    fun reset() {
        preferences.edit().remove("finished_or_skipped").remove("skipped").apply()
    }
}
