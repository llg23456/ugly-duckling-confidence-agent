package com.testconnection.confidence_agent.data.preferences

import android.content.Context
import com.testconnection.confidence_agent.data.model.GrowthJourney
import com.testconnection.confidence_agent.data.model.GrowthJourneyAnalyzer
import com.testconnection.confidence_agent.data.model.GrowthKeyword
import com.testconnection.confidence_agent.data.model.UserProfile
import com.testconnection.confidence_agent.data.model.ProfileSnapshot
import com.testconnection.confidence_agent.data.model.ProfileHistory
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import org.json.JSONArray
import org.json.JSONObject

/** Shares the existing private profile preferences and their export/deletion lifecycle. */
class ProfileJourneyStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("duck_onboarding", Context.MODE_PRIVATE)

    private fun array(key: String) = runCatching { JSONArray(preferences.getString(key, "[]")) }.getOrDefault(JSONArray())
    private fun JSONArray.ids(): List<Long> = (0 until length()).map { optLong(it) }.filter { it > 0 }
    private fun JSONObject.nullable(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }

    fun snapshots(): List<ProfileSnapshot> = array("profile_history").let { rows ->
        (0 until rows.length()).mapNotNull { index -> runCatching {
            val row = rows.getJSONObject(index)
            val tags = row.optJSONArray("keywords") ?: JSONArray()
            ProfileSnapshot(row.getLong("created_at_ms"), UserProfile.fromJson(row.getJSONObject("profile")),
                (0 until tags.length()).map { tagIndex -> tags.getJSONObject(tagIndex).let {
                    GrowthKeyword(it.getString("label"), it.getJSONArray("event_ids").ids())
                } }, row.optInt("stage"), row.optJSONArray("source_event_ids")?.ids().orEmpty(), row.optString("reason"))
        }.getOrNull() }
    }

    fun events(): List<GrowthEvent> = array("journey_events").let { rows ->
        (0 until rows.length()).mapNotNull { parseEvent(rows.optJSONObject(it) ?: return@mapNotNull null) }
    }

    fun journey(): GrowthJourney = GrowthJourneyAnalyzer.analyze(events())

    fun recordIds(): Map<Long, String> = array("journey_events").let { rows ->
        buildMap {
            for (index in 0 until rows.length()) rows.optJSONObject(index)?.let { row ->
                val id = row.optLong("source_record_id")
                row.nullable("client_record_id")?.let { if (id > 0) put(id, it) }
            }
        }
    }

    fun capture(profile: UserProfile, reason: String) {
        val journey = journey()
        val current = snapshots()
        val sources = (journey.keywords.flatMap { it.eventIds } + journey.milestones.flatMap { it.eventIds }
            + if (reason == "画像更新") events().map { it.id } else emptyList()).distinct()
        writeSnapshots(ProfileHistory.append(current, ProfileSnapshot(System.currentTimeMillis(), profile, journey.keywords, journey.stage, sources, reason)))
    }

    fun reconcile(events: List<GrowthEvent>, recordIds: Map<Long, String> = emptyMap()): UserProfile? {
        val previous = array("journey_events")
        val clients = mutableMapOf<Long, String>()
        for (index in 0 until previous.length()) previous.optJSONObject(index)?.let { row ->
            row.nullable("client_record_id")?.let { clients[row.optLong("source_record_id")] = it }
        }
        clients.putAll(recordIds)
        val pending = context.getSharedPreferences("duck_records", Context.MODE_PRIVATE)
            .getStringSet("pending_deletions", emptySet()).orEmpty()
        val valid = events.filterNot { clients[it.sourceRecordId] in pending }
        val validIds = valid.map { it.id }.toSet()
        val currentById = valid.associateBy { it.id }
        val removed = this.events().filter { old ->
            val updated = currentById[old.id]
            old.id !in validIds || updated == null || updated.fact != old.fact || updated.sensitivity == "high"
                || (old.confidence ?: 0.0) >= 0.75 && (updated.confidence ?: 0.0) < 0.75
        }.map { it.id }.toSet()
        val history = snapshots()
        val retained = ProfileHistory.retain(history, removed)
        val restored = if (history.lastOrNull() != retained.lastOrNull()) retained.lastOrNull()?.profile ?: UserProfile() else null
        val rows = JSONArray()
        valid.forEach { event -> rows.put(eventJson(event).put("client_record_id", clients[event.sourceRecordId])) }
        val editor = preferences.edit().putString("journey_events", rows.toString())
        if (restored != null) editor.putString("profile_json", restored.toJson().toString())
        editor.apply()
        writeSnapshots(retained)
        return restored
    }

    fun removeRecord(clientRecordId: String) {
        val rows = array("journey_events")
        val kept = (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.takeUnless {
            it.optString("client_record_id") == clientRecordId
        }?.let(::parseEvent) }
        reconcile(kept)
    }

    fun export(): JSONObject = JSONObject()
        .put("current_profile", preferences.getString("profile_json", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
        .put("snapshots", array("profile_history"))
        .put("journey_stage", journey().stage)
        .put("stage_evidence", JSONArray().apply { journey().milestones.forEach {
            put(JSONObject().put("stage", it.stage).put("date", it.date).put("event_ids", JSONArray(it.eventIds)))
        } })

    private fun writeSnapshots(rows: List<ProfileSnapshot>) {
        val output = JSONArray()
        rows.forEach { row -> output.put(JSONObject()
            .put("created_at_ms", row.createdAt).put("profile", row.profile.toJson()).put("stage", row.stage)
            .put("reason", row.reason).put("source_event_ids", JSONArray(row.sourceEventIds))
            .put("keywords", JSONArray().apply { row.keywords.forEach {
                put(JSONObject().put("label", it.label).put("event_ids", JSONArray(it.eventIds)))
            } })) }
        preferences.edit().putString("profile_history", output.toString()).apply()
    }

    companion object {
        fun parseEvent(row: JSONObject): GrowthEvent? = runCatching {
            fun id(key: String) = row.optLong(key).takeIf { it > 0 }
            fun text(key: String) = row.optString(key).takeIf { it.isNotBlank() && it != "null" }
            GrowthEvent(row.getLong("id"), row.getString("fact"), text("own_effort"), text("support_received"),
                id("source_id") ?: id("source_user_message_id"), id("source_feedback_id"), id("source_record_id"), text("sensitivity"),
                row.optJSONArray("people")?.let { people -> (0 until people.length()).map { people.getString(it) } }.orEmpty(),
                row.getString("created_at"), text("attempt"), if (row.isNull("confidence")) null else row.optDouble("confidence"), text("source_type"))
        }.getOrNull()

        private fun eventJson(event: GrowthEvent) = JSONObject()
            .put("id", event.id).put("fact", event.fact).put("own_effort", event.ownEffort).put("attempt", event.attempt)
            .put("support_received", event.supportReceived).put("source_id", event.sourceId)
            .put("source_feedback_id", event.sourceFeedbackId).put("source_record_id", event.sourceRecordId)
            .put("sensitivity", event.sensitivity).put("confidence", event.confidence).put("source_type", event.sourceType)
            .put("people", JSONArray(event.people)).put("created_at", event.createdAt)
    }
}
