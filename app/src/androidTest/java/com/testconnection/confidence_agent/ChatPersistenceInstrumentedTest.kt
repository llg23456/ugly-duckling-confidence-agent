package com.testconnection.confidence_agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.testconnection.confidence_agent.data.repository.ChatRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatPersistenceInstrumentedTest {
    @Test
    fun confirmedMessagesRemainAfterRepositoryRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstRepository = ChatRepository(context)
        firstRepository.syncHistory()
        val first = firstRepository.send("我今天试着练习开场。")
        val second = firstRepository.send("我刚才说自己做了什么？")
        assertTrue(first.userMessageId != null)
        assertTrue(second.assistantMessageId != null)

        val reopenedRepository = ChatRepository(context)
        val cached = withTimeout(5_000) { reopenedRepository.observeHistory().first { it.size >= 4 } }
        assertEquals("我今天试着练习开场。", cached[0].text)
        assertEquals("我刚才说自己做了什么？", cached[2].text)
        assertFalse(cached[3].fromUser)

        reopenedRepository.syncHistory()
        val synced = withTimeout(5_000) { reopenedRepository.observeHistory().first { it.size >= 4 } }
        assertEquals(cached.map { it.id }, synced.map { it.id })
    }
}
