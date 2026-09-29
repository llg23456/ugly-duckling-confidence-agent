package com.testconnection.confidence_agent.data.repository

import android.content.Context
import com.testconnection.confidence_agent.data.remote.ReviewApiClient
import com.testconnection.confidence_agent.data.remote.ReviewOverview

class ReviewCacheStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("review_overview_cache", Context.MODE_PRIVATE)

    private fun key(period: String, start: String, end: String) = "$period:$start:$end"

    fun load(period: String, start: String, end: String): ReviewOverview? {
        val raw = preferences.getString("json:${key(period, start, end)}", null) ?: return null
        return runCatching { ReviewApiClient.parseOverview(raw) }.getOrNull()
    }

    fun generation(): Long = preferences.getLong("generation", 0L)

    fun save(period: String, start: String, end: String, overview: ReviewOverview, generation: Long) {
        if (overview.rawJson.isBlank()) return
        preferences.edit()
            .putString("json:${key(period, start, end)}", overview.rawJson)
            .putLong("saved:${key(period, start, end)}", System.currentTimeMillis())
            .putLong("generation:${key(period, start, end)}", generation)
            .apply()
    }

    fun isFresh(period: String, start: String, end: String, maxAgeMs: Long = 5 * 60_000L): Boolean {
        val cacheKey = key(period, start, end)
        val generation = preferences.getLong("generation", 0L)
        if (preferences.getLong("generation:$cacheKey", -1L) != generation) return false
        val savedAt = preferences.getLong("saved:$cacheKey", 0L)
        return savedAt > 0L && System.currentTimeMillis() - savedAt < maxAgeMs
    }

    @Synchronized
    fun markDirty() {
        val nextGeneration = preferences.getLong("generation", 0L) + 1L
        preferences.edit().putLong("generation", nextGeneration).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}
