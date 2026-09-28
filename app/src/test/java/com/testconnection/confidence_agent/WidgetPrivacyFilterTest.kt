package com.testconnection.confidence_agent

import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.widget.isSafeForWidget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPrivacyFilterTest {
    private fun event(fact: String, sensitivity: String? = "low", people: List<String> = emptyList()) = GrowthEvent(
        id = 1, fact = fact, ownEffort = null, supportReceived = null,
        sourceId = null, sourceFeedbackId = null, sourceRecordId = null,
        sensitivity = sensitivity, people = people, createdAt = "2026-09-24T10:00:00",
    )

    @Test fun onlyLowSensitivityUnlinkedEventsReachPublicWidget() {
        assertTrue(isSafeForWidget(event("今天尝试说出了自己的想法")))
        assertFalse(isSafeForWidget(event("今天尝试说出了自己的想法", sensitivity = null)))
        assertFalse(isSafeForWidget(event("今天尝试说出了自己的想法", sensitivity = "high")))
        assertFalse(isSafeForWidget(event("今天和朋友聊了聊", people = listOf("朋友"))))
    }

    @Test fun obviousPrivateDetailsAreRejected() {
        assertFalse(isSafeForWidget(event("我的电话是13800138000")))
        assertFalse(isSafeForWidget(event("给我写信 test@example.com")))
        assertFalse(isSafeForWidget(event("想聊聊病情")))
    }
}
