package com.testconnection.confidence_agent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.testconnection.confidence_agent.data.repository.CloudCommunityDraft
import com.testconnection.confidence_agent.data.repository.CloudCommunityStore
import org.junit.Assert.*
import org.junit.Test

class CloudCommunityStoreInstrumentedTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("community-test-$name", mode)
        override fun getFilesDir() = java.io.File(super.getFilesDir(), "community-test-isolated").apply { mkdirs() }
    }

    @Test fun uncertainPublishSurvivesRecreationWithTheSameRequestAndMediaIds() {
        val first = CloudCommunityStore(context)
        first.clearAll()
        val original = CloudCommunityDraft(title = "我的小步", content = "只分享选中的内容",
            pictures = listOf("local-photo.jpg"), uploaded = listOf("media-id"), pending = true)
        first.save(original)
        val restored = CloudCommunityStore(context).draft()
        assertEquals(original.clientId, restored.clientId)
        assertEquals(original.payload().toString(), restored.payload().toString())
        assertTrue(restored.pending)
        assertFalse(restored.payload().has("source_record_id"))
        assertFalse(restored.payload().has("pictures"))
        first.clearAll()
        assertFalse(CloudCommunityStore(context).draft().pending)
    }
}
