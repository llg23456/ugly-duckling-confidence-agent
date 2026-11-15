package com.testconnection.confidence_agent

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Scaffold
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.testconnection.confidence_agent.data.model.RecordDraft
import com.testconnection.confidence_agent.data.model.RecordMode
import com.testconnection.confidence_agent.data.preferences.DeviceIdStore
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import com.testconnection.confidence_agent.data.remote.CommunityApiClient
import com.testconnection.confidence_agent.data.remote.ReviewApiClient
import com.testconnection.confidence_agent.data.repository.*
import com.testconnection.confidence_agent.ui.screens.CommunityScreen
import com.testconnection.confidence_agent.ui.theme.ConfidenceAgentTheme
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*

/** Talks to an isolated real FastAPI database via adb reverse. Never runs in the user's cloud app. */
class CloudCommunityInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var api: CommunityApiClient
    private var host: ComponentActivity? = null
    private var backendUrl = "http://127.0.0.1:8769"
    private fun phase(value: String) { instrumentation.sendStatus(2, android.os.Bundle().apply { putString("phase", value) }) }

    @Before fun setup() = runBlocking {
        Assume.assumeTrue(context.packageName.endsWith(".local"))
        phase("setup-start")
        backendUrl = InstrumentationRegistry.getArguments().getString("community_test_url") ?: backendUrl
        ServerEndpoint.save(context, backendUrl)
        context.getSharedPreferences("duck_device", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        api = CommunityApiClient(DeviceIdStore(context).get())
        phase("delete-isolated-data")
        ReviewApiClient().deleteAllData(DeviceIdStore(context).get())
        phase("delete-completed")
        CloudCommunityStore(context).clearAll()
        CommunityPostStore(context).clearAll()
        LocalRecordRepository(context).clearAll()
        phase("setup-completed")
    }
    @After fun cleanup() = runBlocking {
        if (context.packageName.endsWith(".local") && ::api.isInitialized) {
            instrumentation.runOnMainSync { host?.finish() }
            ServerEndpoint.save(context, backendUrl)
            ReviewApiClient().deleteAllData(DeviceIdStore(context).get())
            CloudCommunityStore(context).clearAll()
        }
    }
    private fun show() {
        phase("launch-empty-host")
        instrumentation.uiAutomation.executeShellCommand("am start -W -n ${context.packageName}/com.testconnection.confidence_agent.CommunityTestActivity")
            .use { FileInputStream(it.fileDescriptor).readBytes() }
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (host == null && android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>().firstOrNull { it.javaClass.simpleName == "CommunityTestActivity" }
            }
            if (host == null) android.os.SystemClock.sleep(100)
        }
        checkNotNull(host) { "Empty community test host did not resume" }
        instrumentation.runOnMainSync {
            host!!.setContent { ConfidenceAgentTheme { Scaffold { padding -> CommunityScreen(padding, "PRIVATE-NAME-MUST-NOT-BE-PUBLIC") } } }
        }
        phase("host-content-ready")
        waitText("写分享")
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("写分享") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitText(text: String) = compose.waitUntil(25_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun scroll(text: String) { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text)) }
    private fun configure() = runBlocking { api.request("PATCH", "/profile", JSONObject().put("nickname", "验收小鸭").put("avatar", 2)) }
    private fun screenshot(name: String) {
        val file = File(context.getExternalFilesDir(null), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun profileDraftPublishCommentAndDeleteUseRealBackend() {
        show(); phase("open-profile"); compose.onNodeWithText("社区名片").performClick()
        phase("type-nickname")
        compose.onNode(hasSetTextAction() and hasText("昵称", substring = true)).performTextReplacement("验收小鸭")
        phase("save-profile"); compose.onNodeWithText("保存名片").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("设置社区名片").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("写分享") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        phase("open-composer"); compose.onNodeWithText("写分享").performClick()
        phase("type-title"); compose.onNode(hasSetTextAction() and hasText("标题", substring = true)).performTextInput("真机社区小步")
        compose.onNode(hasSetTextAction() and hasText("想分享的经历", substring = true)).performTextInput("今天请教了师姐，也出去走了走。")
        scroll("‹ 返回社区"); compose.onNodeWithText("‹ 返回社区").performClick()
        waitText("一起成长"); compose.onNodeWithText("写分享").performClick()
        compose.onNodeWithText("今天请教了师姐，也出去走了走。").assertExists()
        scroll("检查并发布"); screenshot("community-composer")
        compose.onNodeWithText("检查并发布").performClick(); compose.onNodeWithText("确认发布").performClick()
        waitText("分享详情"); waitText("友善地说一句（最多500字）")
        compose.onNode(hasSetTextAction() and hasText("友善地说一句", substring = true)).performTextInput("慢慢来，也照顾好自己")
        scroll("发送评论"); compose.onNodeWithText("发送评论").performClick(); waitText("慢慢来，也照顾好自己")
        runBlocking {
            val posts = api.request("GET", "/posts", query = mapOf("mine" to "true")).optJSONArray("posts").objects()
            Assert.assertEquals(1, posts.size); Assert.assertEquals(1, posts[0].optInt("comment_count"))
            Assert.assertFalse(posts[0].toString().contains("PRIVATE-NAME"))
        }
        scroll("真机社区小步"); screenshot("community-detail")
        compose.onAllNodesWithText("操作").onFirst().performClick()
        compose.onNodeWithText("删除我的内容").performClick(); compose.onNodeWithText("确认删除").performClick()
        waitText("一起成长")
        runBlocking { Assert.assertEquals(0, api.request("GET", "/posts", query=mapOf("mine" to "true")).getJSONArray("posts").length()) }
    }

    @Test fun privateRecordIsEditableAndDoesNotUploadBeforeConfirmation() {
        configure()
        LocalRecordRepository(context).save(RecordDraft("private-id", RecordMode.TEXT, "私人经历：今天和老师讨论了计划"))
        show(); compose.onNodeWithText("写分享").performClick(); scroll("从我的记录导入")
        compose.onNodeWithText("从我的记录导入").performClick()
        compose.onNodeWithText("私人经历：今天和老师讨论了计划").performClick()
        compose.onNode(hasSetTextAction() and hasText("想分享的经历", substring = true)).performTextReplacement("只分享：讨论后有了新方向")
        runBlocking { Assert.assertEquals(0, api.request("GET", "/posts", query = mapOf("mine" to "true")).getJSONArray("posts").length()) }
        Assert.assertEquals("只分享：讨论后有了新方向", CloudCommunityStore(context).draft().content)
    }

    @Test fun oldPostsStayLocalUntilExplicitRepublish() {
        configure()
        CommunityPostStore(context).publish(com.testconnection.confidence_agent.data.model.CommunityDraft(title="本机旧故事",content="只存在本机的分享"), "旧名字")
        show(); compose.onNodeWithText("本机旧帖").performClick(); waitText("本机旧故事")
        compose.onNodeWithText("替换草稿并编辑").performClick(); waitText("写一篇分享")
        runBlocking { Assert.assertEquals(0, api.request("GET", "/posts", query = mapOf("mine" to "true")).getJSONArray("posts").length()) }
        Assert.assertEquals(1, CommunityPostStore(context).loadOwnPosts().size)
    }

    @Test fun pendingDraftRemainsLockedAndDoesNotAutoSubmit() {
        configure()
        val pending = CloudCommunityDraft(title="待确认发布",content="连接中断后保留",pending=true)
        CloudCommunityStore(context).save(pending)
        show(); compose.onNodeWithText("写分享").performClick()
        compose.onNodeWithText("标题 5/50").assertIsNotEnabled()
        scroll("重试原发布"); compose.onNodeWithText("重试原发布").assertExists()
        runBlocking { Assert.assertEquals(0, api.request("GET", "/posts", query=mapOf("mine" to "true")).getJSONArray("posts").length()) }
    }

    @Test fun offlineCacheAndDraftRemainUsableWithoutAutomaticPublish() {
        configure()
        val post = runBlocking { api.request("POST", "/posts", JSONObject().put("client_id", java.util.UUID.randomUUID().toString())
            .put("title", "缓存中的小步").put("content", "上一次加载的内容")) }
        CloudCommunityStore(context).cache(listOf(post))
        ServerEndpoint.save(context, "http://127.0.0.1:1")
        show(); waitText("缓存中的小步")
        compose.onNodeWithText("写分享").performClick()
        compose.onNode(hasSetTextAction() and hasText("标题",substring=true)).performTextInput("断网时写草稿")
        Assert.assertEquals("断网时写草稿", CloudCommunityStore(context).draft().title)
        screenshot("community-offline-draft")
    }

    @Test fun selectedPhotoRecordUploadsOnlyAfterConfirmation() {
        configure()
        val photo = File(context.filesDir, "community-acceptance-photo.jpg")
        Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(android.graphics.Color.GREEN)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }; bitmap.recycle()
        }
        LocalRecordRepository(context).save(RecordDraft("private-photo-id",RecordMode.PHOTO,"",photoPath=photo.absolutePath,photoComment="一张户外照片"))
        show(); compose.onNodeWithText("写分享").performClick(); scroll("从我的记录导入")
        compose.onNodeWithText("从我的记录导入").performClick(); compose.onNodeWithText("一张户外照片").performClick()
        scroll("检查并发布"); compose.onNodeWithText("检查并发布").performClick(); compose.onNodeWithText("确认发布").performClick()
        waitText("分享详情"); waitText("友善地说一句（最多500字）")
        runBlocking {
            val post = api.request("GET", "/posts",query=mapOf("mine" to "true")).getJSONArray("posts").getJSONObject(0)
            Assert.assertEquals(1,post.getJSONArray("media_ids").length())
            Assert.assertFalse(post.toString().contains("private-photo-id"))
        }
        scroll("一张户外照片"); screenshot("community-photo-detail")
        photo.delete()
    }
}
