package com.testconnection.confidence_agent

import com.testconnection.confidence_agent.data.model.CommunityRecordImport
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.ui.AppDestination
import com.testconnection.confidence_agent.ui.screens.MoodBand
import com.testconnection.confidence_agent.ui.screens.classifyMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityFeatureTest {
    @Test fun legacyWidgetDestinationsStayStableAfterCommunityTabIsAdded() {
        assertEquals(AppDestination.HOME, AppDestination.fromLegacyCode(0))
        assertEquals(AppDestination.GROWTH, AppDestination.fromLegacyCode(1))
        assertEquals(AppDestination.RECORD, AppDestination.fromLegacyCode(2))
        assertEquals(AppDestination.PROFILE, AppDestination.fromLegacyCode(3))
        assertEquals(AppDestination.COMMUNITY, AppDestination.fromLegacyCode(4))
        assertEquals(AppDestination.HOME, AppDestination.fromLegacyCode(99))
    }

    @Test fun importsTextAndVoiceAsEditableTextWithoutAudio() {
        val text = CommunityRecordImport.from(RecordDraft("text", RecordMode.TEXT, "今天完成了复习"))
        assertEquals("今天完成了复习", text?.content)
        assertNull(text?.sourcePhotoPath)

        val voice = CommunityRecordImport.from(RecordDraft("voice", RecordMode.VOICE, "这是语音转写", audioPath = "private.wav"))
        assertEquals("这是语音转写", voice?.content)
        assertNull(voice?.sourcePhotoPath)
    }

    @Test fun photoUsesCommentThenDescriptionAndDraftsCannotPublish() {
        val photo = CommunityRecordImport.from(RecordDraft(
            id = "photo", mode = RecordMode.PHOTO, text = "", photoPath = "photo.jpg",
            photoComment = "傍晚去公园走了走", aiDescription = "公园里的树",
        ))
        assertEquals("傍晚去公园走了走", photo?.content)
        assertEquals("photo.jpg", photo?.sourcePhotoPath)
        assertTrue(photo?.title?.contains("公园") == true)

        assertNull(CommunityRecordImport.from(RecordDraft("draft", RecordMode.TEXT, "尚未保存", status = "draft")))
    }

    @Test fun weeklyMoodPrefersExplicitFeelingAndOtherwiseUsesDailyText() {
        assertEquals(MoodBand.TENSE, classifyMood("紧张", "我愿意开始准备"))
        assertEquals(MoodBand.LOW, classifyMood("", "做题错了很多，一度想放弃"))
        assertEquals(MoodBand.BRIGHT, classifyMood("", "和同学运动后轻松了，也愿意继续调整"))
        assertEquals(MoodBand.STEADY, classifyMood("", "今天整理了书桌和课程表"))
    }
}
