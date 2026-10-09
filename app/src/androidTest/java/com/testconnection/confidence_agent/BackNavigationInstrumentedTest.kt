package com.testconnection.confidence_agent

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.testconnection.confidence_agent.data.preferences.OnboardingStore
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.io.FileInputStream
import org.junit.*

/** Navigation checks use a separate installation and never edit the user's records. */
class BackNavigationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var activity: MainActivity? = null

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).readBytes()
        }
    }

    @Before fun launch() {
        Assume.assumeTrue(context.packageName.endsWith(".p0test"))
        OnboardingStore(context).skip()
        ServerEndpoint.save(context, "http://127.0.0.1:8788")
        shell("am start -W -n ${context.packageName}/${MainActivity::class.java.name} --ei target_tab 0")
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().first()
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("首页") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun close() {
        instrumentation.runOnMainSync { activity?.finish() }
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())
    private fun back() {
        compose.waitForIdle()
        shell("input keyevent 4")
        compose.waitForIdle()
    }
    private fun openProfileEntry(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Test fun everyOtherMainTabReturnsHomeWithoutExiting() {
        listOf("成长", "社区", "记录", "我的").forEach {
            tab(it).performClick()
            tab(it).assertIsSelected()
            back()
            tab("首页").assertIsSelected()
            Assert.assertFalse(requireNotNull(activity).isFinishing)
        }
        back() // Returning from a tab must not count as the first home back.
        Assert.assertFalse(requireNotNull(activity).isFinishing)
    }

    @Test fun homeRequiresTwoBacksWithinTwoSecondsAndResetsAfterNavigation() {
        back()
        Assert.assertFalse(requireNotNull(activity).isFinishing)
        tab("我的").performClick()
        tab("我的").assertIsSelected()
        back()
        back()
        Assert.assertFalse(requireNotNull(activity).isFinishing)
        SystemClock.sleep(2_100)
        back()
        Assert.assertFalse(requireNotNull(activity).isFinishing)
        shell("input keyevent 4")
        compose.waitUntil(5_000) { requireNotNull(activity).isFinishing }
    }

    @Test fun profileChildrenAndRestartOnboardingReturnToTheirParent() {
        tab("我的").performClick()
        listOf("数据导出", "支持圈", "记忆中心").forEach {
            openProfileEntry(it)
            back()
            tab("我的").assertIsSelected()
        }
        openProfileEntry("记忆中心")
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("重新认识").assertIsEnabled(); true }.getOrDefault(false)
        }
        compose.onNodeWithText("重新认识").performClick()
        compose.onNodeWithText("暂时跳过").assertExists()
        back()
        compose.onNodeWithText("重新认识").assertExists()
        Assert.assertFalse(OnboardingStore(context).shouldShow())
        back()
        tab("我的").assertIsSelected()
    }

    @Test fun communityComposerAndGrowthRecordReturnToTheirParent() {
        tab("社区").performClick()
        compose.onNodeWithText("发布", substring = false).performClick()
        compose.onNodeWithText("发布分享").assertExists()
        back()
        tab("社区").assertIsSelected()
        back()
        tab("首页").assertIsSelected()
        tab("成长").performClick()
        compose.onNodeWithText("日", substring = false).performClick()
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("＋ 补充这一天的记录"))
                true
            }.getOrDefault(false)
        }
        compose.onNodeWithText("＋ 补充这一天的记录").performClick()
        tab("记录").assertIsSelected()
        back()
        tab("成长").assertIsSelected()
        back()
        tab("首页").assertIsSelected()
    }
}
