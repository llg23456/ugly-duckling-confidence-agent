package com.testconnection.confidence_agent.data.model

data class ProfileSnapshot(
    val createdAt: Long,
    val profile: UserProfile,
    val keywords: List<GrowthKeyword>,
    val stage: Int,
    val sourceEventIds: List<Long>,
    val reason: String,
)

object ProfileHistory {
    fun retain(history: List<ProfileSnapshot>, invalidatedIds: Set<Long>): List<ProfileSnapshot> =
        history.filterNot { row -> row.sourceEventIds.any(invalidatedIds::contains) }

    fun append(history: List<ProfileSnapshot>, snapshot: ProfileSnapshot): List<ProfileSnapshot> {
        val latest = history.lastOrNull()
        val sameProfile = latest?.profile?.displayItems() == snapshot.profile.displayItems()
        if (sameProfile && latest?.keywords?.map { it.label } == snapshot.keywords.map { it.label }
            && latest.stage == snapshot.stage) return history
        // Later journey-only snapshots must retain the provenance of the copied profile.
        val sources = (snapshot.sourceEventIds + if (sameProfile) latest?.sourceEventIds.orEmpty() else emptyList()).distinct()
        return (history + snapshot.copy(sourceEventIds = sources)).takeLast(36)
    }
}
