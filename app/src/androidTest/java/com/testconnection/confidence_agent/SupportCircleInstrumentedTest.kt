package com.testconnection.confidence_agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.testconnection.confidence_agent.data.preferences.OnboardingStore
import com.testconnection.confidence_agent.data.preferences.ServerEndpoint
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.*

/** Reproduces overlapping IDs from two API tables without accessing personal data. */
class SupportCircleInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val peopleRequests = AtomicInteger()
    private val feedbackRequests = AtomicInteger()
    private var server: ServerSocket? = null
    private var activity: MainActivity? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Before fun launchWithLocalBackend() {
        Assume.assumeTrue(context.packageName.endsWith(".p0test"))
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        executor.execute {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5_000
                        val reader = connection.getInputStream().bufferedReader()
                        val path = reader.readLine().orEmpty().split(' ').getOrElse(1) { "/" }
                        while (!reader.readLine().isNullOrEmpty()) { /* Read HTTP headers. */ }
                        val body = when {
                            path.startsWith("/api/v1/support-people?") -> {
                                peopleRequests.incrementAndGet()
                                """{"people":[{"id":2,"name":"测试支持者","relationship":"同学","kind":"classmate","scenarios":["学习"]}]}"""
                            }
                            path.startsWith("/api/v1/support/feedback?") -> {
                                feedbackRequests.incrementAndGet()
                                """{"feedback":[{"id":2,"supporter_name":"测试反馈对象","outcome":"helped","own_effort":"主动请教","support_received":"一起讨论","created_at":"2026-10-17T08:00:00Z"}]}"""
                            }
                            else -> "{}"
                        }.toByteArray(Charsets.UTF_8)
                        connection.getOutputStream().use { output ->
                            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            output.write(body)
                        }
                    }
                } catch (error: SocketException) {
                    if (!socket.isClosed) throw error
                }
            }
        }
        OnboardingStore(context).skip()
        ServerEndpoint.save(context, "http://127.0.0.1:${socket.localPort}")
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/${MainActivity::class.java.name} --ei target_tab 3",
        ).use { FileInputStream(it.fileDescriptor).readBytes() }
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().first()
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("支持圈").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun close() {
        instrumentation.runOnMainSync { activity?.finish() }
        server?.close()
        executor.shutdownNow()
        if (context.packageName.endsWith(".p0test")) ServerEndpoint.save(context, BuildConfig.API_BASE_URL)
    }

    @Test fun overlappingPersonAndFeedbackIdsRenderAfterReload() {
        repeat(2) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("支持圈"))
            compose.onNodeWithText("支持圈").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("1 位可以联系的人").fetchSemanticsNodes().isNotEmpty() &&
                    feedbackRequests.get() > it
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("测试支持者"))
            compose.onNodeWithText("测试支持者").assertIsDisplayed()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("测试反馈对象"))
            compose.onNodeWithText("测试反馈对象").assertIsDisplayed()
            compose.onNodeWithText("得到了帮助").assertIsDisplayed()
            compose.onNodeWithText("‹ 返回").performClick()
            compose.waitForIdle()
        }
        Assert.assertEquals(2, peopleRequests.get())
        Assert.assertEquals(2, feedbackRequests.get())
        Assert.assertFalse(requireNotNull(activity).isFinishing)
    }
}
