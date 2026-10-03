package com.testconnection.confidence_agent

import com.testconnection.confidence_agent.data.model.GrowthJourneyAnalyzer
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GrowthJourneyAnalyzerTest {
    private fun event(id: Long, date: String, effort: String? = "完成一次复习", support: String? = null) =
        GrowthEvent(id, effort ?: "我很担心考试", effort, support, null, null, id, "low", emptyList(), "${date}T12:00:00+08:00",
            confidence = 1.0, sourceType = "text")

    @Test fun stagesRequireDifferentDaysAndBroaderBehaviour() {
        val first = event(1, "2026-09-01")
        assertEquals(0, GrowthJourneyAnalyzer.analyze(listOf(first, first.copy(id = 2))).stage)
        val actions = (1..4).map { event(it.toLong(), "2026-09-0$it") }
        assertEquals(1, GrowthJourneyAnalyzer.analyze(actions).stage)
        val journey = GrowthJourneyAnalyzer.analyze(actions + event(5, "2026-09-05", "主动联系目标院校师姐", "师姐分享复习经验"))
        assertEquals(2, journey.stage)
        assertEquals(listOf(0, 1, 2), journey.milestones.map { it.stage })
        assertEquals("2026-09-05", journey.milestones.last().date)
        assertTrue(journey.keywords.any { it.label == "主动求助" && 5L in it.eventIds })
    }

    @Test fun scoresAndIntentionsDoNotBecomeAchievements() {
        val plans = (1..4).map { event(it.toLong(), "2026-09-0$it", "准备联系师姐", "希望得到帮助") }
        assertEquals(0, GrowthJourneyAnalyzer.analyze(plans).stage)
        assertTrue(GrowthJourneyAnalyzer.analyze(plans).keywords.isEmpty())
        assertEquals(0, GrowthJourneyAnalyzer.analyze(plans.map { it.copy(ownEffort = null, fact = "我考了高分") }).stage)
        assertTrue(GrowthJourneyAnalyzer.analyze(listOf(event(9, "2026-09-05", "没有去运动"))).keywords.isEmpty())
        assertTrue(GrowthJourneyAnalyzer.analyze(listOf(event(10, "2026-09-05", "我今天想联系师姐", "师姐没有回复"))).keywords.isEmpty())
        assertTrue(GrowthJourneyAnalyzer.analyze(listOf(event(11, "2026-09-05", null).copy(fact = "我明天试着散步"))).keywords.isEmpty())
    }

    @Test fun ignoresSensitiveUncertainAndFutureEvents() {
        val good = event(1, "2026-09-01")
        val ignored = listOf(good.copy(id = 2, sensitivity = "high"), good.copy(id = 3, confidence = 0.6),
            good.copy(id = 4, createdAt = "2030-01-01T12:00:00+08:00"), good.copy(id = 5, createdAt = "invalid"))
        assertEquals(0, GrowthJourneyAnalyzer.analyze(ignored).stage)
        assertTrue(GrowthJourneyAnalyzer.analyze(ignored).keywords.isEmpty())
    }

    @Test fun demoStoriesCannotAdvancePersonalGrowth() {
        val demo = (1..4).map { event(it.toLong(), "2026-09-0$it", "主动联系师姐并安排运动").copy(sourceType = "demo") }
        val journey = GrowthJourneyAnalyzer.analyze(demo)
        assertEquals(0, journey.stage)
        assertTrue(journey.keywords.isEmpty())
        assertTrue(journey.milestones.isEmpty())
        assertEquals(0, GrowthJourneyAnalyzer.analyze(demo + event(5, "2026-09-05")).stage)
    }

    @Test fun inactivityKeepsStageAndDeletedEvidenceIsRecomputed() {
        val actions = (1..4).map { event(it.toLong(), "2026-09-0$it", if (it == 4) "主动安排运动和休息" else "完成一次复习") }
        assertEquals(2, GrowthJourneyAnalyzer.analyze(actions, LocalDate.of(2026, 9, 6)).stage)
        assertEquals(2, GrowthJourneyAnalyzer.analyze(actions, LocalDate.of(2027, 9, 6)).stage)
        val afterDeletion = GrowthJourneyAnalyzer.analyze(actions.dropLast(1))
        assertEquals(1, afterDeletion.stage)
        assertFalse(afterDeletion.keywords.any { 4L in it.eventIds })
    }
}
