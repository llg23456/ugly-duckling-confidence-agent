package com.testconnection.confidence_agent.data.preferences

import android.content.Context

enum class DuckVoiceMode(
    val displayName: String,
    val description: String,
    val cloudVoice: String?,
) {
    GENTLE_FEMALE("温柔女声", "轻柔、自然，适合耐心陪伴", "Serena"),
    WARM_MALE("温暖男声", "阳光、温暖，适合鼓励回应", "Ethan"),
    SYSTEM("跟随系统", "弱网时也能朗读，音色由手机决定", null),
}

data class VoicePreferences(
    val mode: DuckVoiceMode = DuckVoiceMode.GENTLE_FEMALE,
    val autoPlay: Boolean = true,
    val duckCue: Boolean = true,
)

class VoicePreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences("duck_voice_preferences", Context.MODE_PRIVATE)

    fun load(): VoicePreferences {
        val mode = runCatching {
            DuckVoiceMode.valueOf(
                preferences.getString("mode", DuckVoiceMode.GENTLE_FEMALE.name)
                    ?: DuckVoiceMode.GENTLE_FEMALE.name,
            )
        }.getOrDefault(DuckVoiceMode.GENTLE_FEMALE)
        return VoicePreferences(
            mode = mode,
            autoPlay = preferences.getBoolean("auto_play", true),
            duckCue = preferences.getBoolean("duck_cue", true),
        )
    }

    fun save(value: VoicePreferences) {
        preferences.edit()
            .putString("mode", value.mode.name)
            .putBoolean("auto_play", value.autoPlay)
            .putBoolean("duck_cue", value.duckCue)
            .apply()
    }
}
