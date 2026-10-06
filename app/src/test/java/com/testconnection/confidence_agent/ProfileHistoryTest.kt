package com.testconnection.confidence_agent

import com.testconnection.confidence_agent.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ProfileHistoryTest {
    private val initial = UserProfile(currentContext = ProfileValue("准备考研", 1.0))
    private fun snapshot(time: Long, stage: Int, sources: List<Long>, reason: String = "生活变化") =
        ProfileSnapshot(time, initial, listOf(GrowthKeyword("愿意尝试", sources)), stage, sources, reason)

    @Test fun repeatedRefreshDoesNotCreateHistoryNoise() {
        val first = snapshot(1, 1, listOf(1, 2))
        val result = ProfileHistory.append(listOf(first), snapshot(2, 1, listOf(1, 2, 3)))
        assertEquals(listOf(first), result)
    }

    @Test fun journeySnapshotsInheritProfileSourcesAndDeletionRemovesDerivedHistory() {
        val original = snapshot(1, 1, listOf(1, 2, 99), "画像更新")
        val updated = ProfileHistory.append(listOf(original), snapshot(2, 2, listOf(3, 4)))
        assertTrue(updated.last().sourceEventIds.contains(99L))
        assertTrue(ProfileHistory.retain(updated, setOf(99L)).isEmpty())
    }

    @Test fun deletionPreservesInitialIntroductionAndUnrelatedHistory() {
        val introduction = ProfileSnapshot(1, initial, emptyList(), 0, emptyList(), "认识你")
        val rows = listOf(introduction, snapshot(2, 1, listOf(7)), snapshot(3, 2, listOf(8)))
        assertEquals(listOf(introduction, rows.last()), ProfileHistory.retain(rows, setOf(7)))
        assertEquals(listOf(introduction), ProfileHistory.retain(rows, setOf(7, 8)))
    }
}
