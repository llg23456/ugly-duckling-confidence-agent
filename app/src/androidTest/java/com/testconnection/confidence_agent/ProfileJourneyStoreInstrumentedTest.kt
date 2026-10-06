package com.testconnection.confidence_agent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.testconnection.confidence_agent.data.model.*
import com.testconnection.confidence_agent.data.preferences.*
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProfileJourneyStoreInstrumentedTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("advisor-test-$name", mode)
    }
    private val introduction = UserProfile(currentContext = ProfileValue("准备考研", 1.0))
    private fun event(id: Long) = GrowthEvent(id, "完成第${id}天的复习", if (id == 4L) "主动运动并休息" else "完成一次复习",
        null, null, null, id, "low", emptyList(), "2026-09-0${id}T12:00:00+08:00", confidence = 1.0, sourceType = "text")

    @Before fun clearIsolatedPreferences() {
        listOf("duck_onboarding", "duck_records").forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun persistsSnapshotsAndExportsThemWithoutDuplicatingRefreshes() {
        val profile = OnboardingStore(context)
        profile.saveProfile(introduction, complete = true)
        val store = ProfileJourneyStore(context)
        store.reconcile((1L..4L).map(::event))
        val changed = introduction.copy(mainChallenge = ProfileValue("愿意具体提问", 1.0))
        profile.saveProfile(changed, complete = true, reason = "画像更新")
        profile.saveProfile(changed, complete = true, reason = "画像更新")
        val reopened = ProfileJourneyStore(context)
        assertEquals(2, reopened.snapshots().size)
        assertEquals(changed, reopened.snapshots().last().profile)
        assertEquals(2, reopened.journey().stage)
        assertEquals(2, reopened.export().getJSONArray("snapshots").length())
        profile.reset()
        assertTrue(profile.shouldShow())
        assertEquals(2, reopened.snapshots().size)
    }

    @Test fun pendingOfflineDeletionRemovesDerivedHistoryAndCannotResurrectFromServer() {
        val profile = OnboardingStore(context)
        profile.saveProfile(introduction, complete = true)
        val events = (1L..4L).map(::event)
        val store = ProfileJourneyStore(context)
        val records = LocalRecordRepository(context)
        events.forEach { records.save(RecordDraft("record-${it.id}", RecordMode.TEXT, it.fact)) }
        val mapping = events.associate { it.id to "record-${it.id}" }
        store.reconcile(events, mapping)
        assertEquals(mapping, ProfileJourneyStore(context).recordIds())
        profile.saveProfile(introduction.copy(mainChallenge = ProfileValue("学会照顾生活", 1.0)), true, "画像更新")
        records.delete("record-3")
        assertEquals(introduction, profile.loadProfile())
        assertFalse(store.events().any { it.id == 3L })
        assertFalse(store.recordIds().containsKey(3L))
        assertEquals(1, store.journey().stage)
        assertEquals(1, store.snapshots().size)
        store.reconcile(events, mapping)
        assertFalse(store.events().any { it.id == 3L })
        assertTrue(store.snapshots().all { 3L !in it.sourceEventIds })
    }

    @Test fun revisedSourceInvalidatesOldProfileAndBehaviourTags() {
        val profile = OnboardingStore(context)
        profile.saveProfile(introduction, true)
        val original = event(1).copy(fact = "联系了师姐", ownEffort = "主动联系师姐")
        val store = ProfileJourneyStore(context)
        store.reconcile(listOf(original))
        profile.saveProfile(introduction.copy(importantSupporters = ProfileValue("目标院校师姐", 1.0)), true, "画像更新")
        store.reconcile(listOf(original.copy(fact = "我没有联系师姐", ownEffort = null)))
        assertEquals(introduction, profile.loadProfile())
        assertTrue(store.journey().keywords.isEmpty())
        assertEquals(1, store.snapshots().size)
    }
}
