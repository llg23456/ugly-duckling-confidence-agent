package com.testconnection.confidence_agent

import com.testconnection.confidence_agent.data.remote.parseStressAlertMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StressAlertFeatureTest {
    private fun payload(followUp: Map<String, Any?>? = null) = mapOf<String, Any?>(
        "id" to 12L,
        "level" to "high",
        "status" to "confirmed",
        "title" to "最近的压力似乎持续了一阵子",
        "prompt" to "它有没有开始影响你的睡眠、专注或日常节奏？",
        "trend" to listOf(
            mapOf("date" to "2026-10-10", "level" to "steady", "signal_count" to 0),
            mapOf("date" to "2026-10-11", "level" to "watch", "signal_count" to 1),
            mapOf("date" to "2026-10-12", "level" to "high", "signal_count" to 1),
        ),
        "factors" to listOf("3个日期出现压力信号", "2天较高"),
        "evidence" to listOf(mapOf(
            "event_id" to 8L,
            "date" to "2026-10-12",
            "excerpt" to "晚上睡不好，也很难集中注意力。",
            "source_type" to "记录",
            "source_message_id" to null,
            "source_record_id" to 5L,
            "level" to "high",
        )),
        "self_check" to mapOf("impact" to "some", "coping" to "strained"),
        "support" to mapOf(
            "supporter_name" to "妈妈",
            "reason" to "已经在支持圈里",
            "editable_message" to "我最近压力有点大，想和你说说。",
            "small_step" to "先写下一件最压着你的事。",
            "lighter_option" to "也可以先问对方什么时候方便。",
        ),
        "support_copy" to "这不是诊断。",
        "professional_support_hint" to "必要时联系专业支持。",
        "follow_up" to followUp,
        "created_at" to "2026-10-12T08:00:00Z",
    )

    @Test
    fun parsesStressDetailAndLevelMapping() {
        val alert = parseStressAlertMap(payload())
        assertEquals(12L, alert.id)
        assertEquals("high", alert.level)
        assertEquals(3, alert.trend.size)
        assertEquals(1, alert.trend.last().signalCount)
        assertEquals(5L, alert.evidence.single().sourceRecordId)
        assertNull(alert.evidence.single().sourceMessageId)
        assertEquals("some", alert.selfCheckImpact)
        assertEquals("strained", alert.selfCheckCoping)
        assertEquals("妈妈", alert.support.supporterName)
        assertNull(alert.followUp)
    }

    @Test
    fun parsesScheduledAndReadyFollowUp() {
        val waiting = parseStressAlertMap(payload(mapOf(
            "id" to 30L,
            "prompt" to "今天感觉怎么样？",
            "ready" to false,
        )))
        assertEquals(30L, waiting.followUp?.id)
        assertFalse(waiting.followUp?.ready ?: true)

        val ready = parseStressAlertMap(payload(mapOf(
            "id" to 30L,
            "prompt" to "今天感觉怎么样？",
            "ready" to true,
        )))
        assertTrue(ready.followUp?.ready == true)
    }
}
