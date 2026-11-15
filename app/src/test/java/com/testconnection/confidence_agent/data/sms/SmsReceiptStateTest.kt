package com.testconnection.confidence_agent.data.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsReceiptStateTest {
    @Test fun partialOrMissingResultsAreNotSuccess() {
        assertEquals("phone_sending", SmsReceiptState.status(2, mapOf(0 to true), emptySet()))
        assertEquals("phone_sending", SmsReceiptState.status(1, emptyMap(), emptySet()))
        assertEquals("phone_unknown", SmsReceiptState.status(0, emptyMap(), emptySet()))
    }
    @Test fun oneFailedSegmentFailsTheMessage() {
        assertEquals("phone_failed", SmsReceiptState.status(2, mapOf(0 to true, 1 to false), setOf(0)))
    }
    @Test fun sentIsDistinctFromDelivered() {
        assertEquals("phone_sent", SmsReceiptState.status(2, mapOf(0 to true, 1 to true), setOf(0)))
        assertEquals("phone_delivered", SmsReceiptState.status(2, emptyMap(), setOf(0, 1)))
    }
}
