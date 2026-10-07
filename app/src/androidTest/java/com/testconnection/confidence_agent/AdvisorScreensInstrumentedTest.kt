package com.testconnection.confidence_agent

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.testconnection.confidence_agent.data.model.*
import com.testconnection.confidence_agent.data.preferences.*
import com.testconnection.confidence_agent.data.remote.GrowthEvent
import com.testconnection.confidence_agent.data.remote.ReviewApiClient
import com.testconnection.confidence_agent.data.repository.LocalRecordRepository
import com.testconnection.confidence_agent.data.repository.DataManagementRepository
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*

/** Runs against the separate test installation, never the user's installed app. */
class AdvisorScreensInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var activity: MainActivity? = null

    @Before fun prepareIsolatedInstallation() {
        Assume.assumeTrue(context.packageName.endsWith(".p0test"))
        listOf("duck_onboarding", "duck_records", "duck_device", "review_overview_cache").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @After fun closeActivity() {
        instrumentation.runOnMainSync { activity?.finish() }
        if (context.packageName.endsWith(".p0test")) ServerEndpoint.save(context, BuildConfig.API_BASE_URL)
    }

    private fun launchProfile() {
        // MIUI restricts background launches from the test process; shell launch is permitted.
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/${MainActivity::class.java.name} --ei target_tab 3").use {
            FileInputStream(it.fileDescriptor).readBytes()
        }
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("记忆中心").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollTo(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun awaitScrollTo(text: String, timeout: Long = 15_000): SemanticsNodeInteraction {
        compose.waitUntil(timeout) { runCatching { scrollTo(text); true }.getOrDefault(false) }
        return compose.onNodeWithText(text)
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        SystemClock.sleep(500) // wait for native dialog and SurfaceView transitions before capture
        val directory = File(context.filesDir, "advisor-acceptance").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun offlineMemoryShowsChangesAndOpensCachedOriginal() {
        ServerEndpoint.save(context, "http://127.0.0.1:8788") // closed port: no server or personal data
        val initial = UserProfile(currentContext = ProfileValue("准备考研", 1.0))
        val profiles = OnboardingStore(context)
        profiles.saveProfile(initial, true)
        val events = (1L..4L).map { id ->
            GrowthEvent(id, "完成第${id}天的复习", if (id == 4L) "主动运动并休息" else "完成一次复习",
                null, null, null, id, "low", emptyList(), "2026-09-0${id}T12:00:00+08:00", confidence = 1.0, sourceType = "text")
        }
        val records = LocalRecordRepository(context)
        events.forEach { records.save(RecordDraft("screen-${it.id}", RecordMode.TEXT, it.fact)) }
        val store = ProfileJourneyStore(context)
        store.reconcile(events, events.associate { it.id to "screen-${it.id}" })
        profiles.saveProfile(initial.copy(mainChallenge = ProfileValue("学会照顾生活", 1.0)), true, "画像更新")
        launchProfile()
        compose.onNodeWithText("记忆中心").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("现在的关键词").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("成长").assertIsDisplayed()
        compose.onNodeWithText("照顾自己").assertIsDisplayed()
        screenshot("memory-offline")
        compose.onNodeWithText("照顾自己").performClick()
        compose.onNodeWithText("完成第4天的复习").assertIsDisplayed()
        compose.onNodeWithText("查看原始记录").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("编辑").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("完成第4天的复习").assertIsDisplayed()
        compose.onNodeWithText("编辑").assertExists()
        screenshot("cached-original")
        compose.onNodeWithText("收好").performClick()
        scrollTo("查看完整画像 ▾").performClick()
        scrollTo("学会照顾生活").assertIsDisplayed()
        scrollTo("查看画像历史 ›").performClick()
        compose.onNodeWithText("画像更新记录").assertIsDisplayed()
        screenshot("profile-history")
    }

    @Test fun supportCircleSavesSeniorThroughRealApi() {
        ServerEndpoint.save(context, BuildConfig.API_BASE_URL)
        OnboardingStore(context).skip()
        launchProfile()
        compose.onNodeWithText("支持圈").performClick()
        compose.onNodeWithText("添加一个人").performClick()
        compose.onNodeWithText("称呼").performTextInput("测试师姐")
        compose.onNodeWithText("与你的关系").performTextInput("目标院校师姐")
        compose.onNodeWithText("师兄师姐").performClick()
        screenshot("senior-editor")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("目标院校师姐 · 师兄师姐").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("目标院校师姐 · 师兄师姐").assertIsDisplayed()
        screenshot("senior-saved")
    }

    @Test fun startAndTryImagesFollowDistinctActionDays() {
        ServerEndpoint.save(context, "http://127.0.0.1:8788")
        val profile = UserProfile(currentContext = ProfileValue("准备考研", 1.0))
        OnboardingStore(context).saveProfile(profile, true)
        val store = ProfileJourneyStore(context)
        for (count in 1..2) {
            store.reconcile((1L..count.toLong()).map { id ->
                GrowthEvent(id, "完成第${id}天的复习", "完成一次复习", null, null, null, id,
                    "low", emptyList(), "2026-09-0${id}T12:00:00+08:00", confidence = 1.0, sourceType = "text")
            })
            launchProfile()
            compose.onNodeWithText("记忆中心").performClick()
            compose.onNodeWithContentDescription(if (count == 1) "起步阶段的小鸭" else "尝试阶段的小鸭").assertIsDisplayed()
            screenshot(if (count == 1) "memory-start" else "memory-try")
            instrumentation.runOnMainSync { activity?.finish() }
            activity = null
        }
    }

    @Test fun exportedHistoryAndAllDataDeletionFollowAppLifecycle() = runBlocking {
        ServerEndpoint.save(context, BuildConfig.API_BASE_URL)
        val profiles = OnboardingStore(context)
        profiles.saveProfile(UserProfile(currentContext = ProfileValue("准备考研", 1.0)), true)
        val deviceId = DeviceIdStore(context).get()
        ReviewApiClient().createDemoData(deviceId)
        val file = DataManagementRepository(context).exportJson(deviceId)
        val exported = JSONObject(file.readText()).getJSONObject("memory_and_growth").getJSONObject("local_profile_history")
        Assert.assertEquals(1, exported.getJSONArray("snapshots").length())
        launchProfile()
        compose.onNodeWithText("数据导出").performClick()
        compose.onNodeWithText("删除全部数据").performClick()
        compose.onNodeWithText("确认全部删除").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("第一次见面").fetchSemanticsNodes().isNotEmpty() }
        Assert.assertNull(profiles.loadProfile())
        Assert.assertTrue(ProfileJourneyStore(context).snapshots().isEmpty())
        Assert.assertTrue(ProfileJourneyStore(context).events().isEmpty())
        Assert.assertTrue(LocalRecordRepository(context).load().isEmpty())
        screenshot("after-delete-all")
    }

    @Test fun demoStoryBuildsPlayableSilentVideo() = runBlocking {
        ServerEndpoint.save(context, BuildConfig.API_BASE_URL)
        OnboardingStore(context).skip()
        ReviewApiClient().createDemoData(DeviceIdStore(context).get())
        launchProfile()
        compose.onNodeWithText("成长").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("考研演示故事 · 示例记录").fetchSemanticsNodes().isNotEmpty() }
        screenshot("growth-day")
        scrollTo("展开完整回望 ▾").performClick()
        screenshot("growth-day-expanded")
        scrollTo("周").performClick()
        scrollTo("本周报告")
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("本周报告") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("本周报告").performClick()
        scrollTo("一键生成与分享").performClick()
        scrollTo("补充搜索").performTextInput("考研 学习 运动 休息 师姐 父母")
        instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
        scrollTo("全选结果").performClick()
        scrollTo("用已选片段生成故事脚本").assertIsDisplayed().assertIsEnabled().performClick()
        screenshot("script-request")
        awaitScrollTo("无配音").performClick()
        compose.onNodeWithText("无配音").assertIsSelected()
        scrollTo("生成并预览成长小片").performClick()
        awaitScrollTo("小片已保存在本机", 60_000).assertIsDisplayed()
        fun videos(view: View): List<VideoView> = when (view) {
            is VideoView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { videos(view.getChildAt(it)) }
            else -> emptyList()
        }
        compose.waitUntil(10_000) {
            var playing = false
            instrumentation.runOnMainSync { playing = activity?.window?.decorView?.let(::videos)?.any { it.isPlaying && it.currentPosition >= 500 } == true }
            playing
        }
        screenshot("video-preview")
        Assert.assertTrue(File(context.filesDir, "generated_videos").walkTopDown().any { it.extension == "mp4" && it.length() > 1000 })
    }
}
