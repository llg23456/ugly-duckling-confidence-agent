package com.testconnection.confidence_agent.data.sms

/** Aggregate every segment; an absent receipt is never claimed as successful delivery. */
internal object SmsReceiptState {
    fun status(parts: Int, sent: Map<Int, Boolean>, delivered: Set<Int>): String = when {
        parts <= 0 -> "phone_unknown"
        (0 until parts).all { it in delivered } -> "phone_delivered"
        sent.values.any { !it } -> "phone_failed"
        (0 until parts).all { sent[it] == true } -> "phone_sent"
        else -> "phone_sending"
    }
}
