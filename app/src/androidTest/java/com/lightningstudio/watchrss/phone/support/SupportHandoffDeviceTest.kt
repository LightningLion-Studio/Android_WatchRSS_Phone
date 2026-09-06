package com.lightningstudio.watchrss.phone.support

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightningstudio.watchrss.phone.SupportScreen
import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Uses the isolated HTTP fixture on port 18089; owner account and logs are never used. */
@RunWith(AndroidJUnit4::class)
class SupportHandoffDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var vm: SupportViewModel
    private lateinit var client: SupportClient
    private lateinit var sessions: MutableStateFlow<PhoneAccountSession?>
    private var collected = 0
    private var uploads = 0
    private fun setup() {
        sessions = MutableStateFlow(PhoneAccountSession(UUID.randomUUID().toString(), "fixture", "fixture", "", System.currentTimeMillis() + 3600000))
        client = SupportClient(AccountEnvironment(backendBaseUrl = "http://127.0.0.1:18089", supabaseAnonKey = "test"), sessions)
        rule.runOnUiThread {
            vm = SupportViewModel(rule.activity.application, sessions, client)
            vm.collectLogs = { collected++; "synthetic diagnostic" to "测试日志" }
            vm.uploadLogs = { _, _ -> uploads++; "123456" }
        }
        rule.setContent { WatchRssPhoneTheme { val state by vm.state.collectAsState(); SupportScreen(state, vm, {}, {}, {}) } }
        rule.waitUntil(15000) { vm.state.value.messages.isNotEmpty() && !vm.state.value.busy }
    }
    @After fun cleanup() { if (::sessions.isInitialized) rule.runOnUiThread { sessions.value = null; client.cancel() } }

    @Test fun refusalNeverCollectsOrUploadsAndSurvivesRefresh() {
        setup()
        rule.onNodeWithTag("support_log_decline").performScrollTo().performClick()
        rule.onNodeWithTag("support_log_human").assertExists()
        rule.runOnUiThread { vm.agreeLogs("handoff"); vm.refresh() }
        rule.waitUntil(10000) { !vm.state.value.busy }
        assertEquals("declined", vm.state.value.messages.single().logState)
        assertEquals(0, collected); assertEquals(0, uploads)
    }
    @Test fun uploadFailureRetriesThenCopiesRealResultWithoutDuplicateUpload() {
        setup()
        rule.runOnUiThread { vm.uploadLogs = { _, _ -> uploads++; if (uploads == 1) error("network") else "654321" } }
        rule.onNodeWithTag("support_log_agree").performScrollTo().performClick()
        rule.waitUntil(10000) { vm.state.value.messages.single().logState == "failed" }
        rule.onNodeWithTag("support_log_code").assertDoesNotExist()
        rule.onNodeWithTag("support_log_retry").performScrollTo().performClick()
        rule.waitUntil(10000) { vm.state.value.messages.single().logState == "uploaded" }
        rule.onNodeWithTag("support_log_copy").performScrollTo().performClick()
        rule.runOnUiThread {
            assertEquals("654321", rule.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            vm.agreeLogs("handoff"); vm.refresh()
        }
        rule.waitUntil(10000) { !vm.state.value.busy }
        assertEquals("654321", vm.state.value.messages.single().logCode)
        assertEquals(2, uploads)
    }
    @Test fun logoutCancelsPendingUploadAndClearsChat() {
        setup()
        var canceled = false
        rule.runOnUiThread { vm.uploadLogs = { _, _ -> try { awaitCancellation() } finally { canceled = true } } }
        rule.onNodeWithTag("support_log_agree").performScrollTo().performClick()
        rule.waitUntil(10000) { collected == 1 }
        rule.runOnUiThread { sessions.value = null }
        rule.waitUntil(10000) { canceled && vm.state.value.user == null }
        assertTrue(vm.state.value.messages.isEmpty())
    }
    @Test fun navigationBubbleOpensSameConversationAndCanSend() {
        setup()
        rule.onNodeWithTag("support_action_fonts").performScrollTo().performClick()
        rule.waitUntil(15000) { rule.onAllNodesWithTag("support_bubble").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("导入字体", substring = true).assertIsDisplayed()
        // Android Activity transitions are outside the Compose test clock.
        android.os.SystemClock.sleep(1000)
        val bubble = rule.onNodeWithTag("support_bubble")
        rule.onNodeWithContentDescription("打开客服").assertExists()
        val before = bubble.fetchSemanticsNode().layoutInfo.coordinates.positionInWindow()
        val screen = rule.activity.window.decorView
        assertTrue(before.x > screen.width * .7f)
        assertTrue(before.y > screen.height * .35f && before.y < screen.height * .6f)
        fun dragBy(dx: Float, dy: Float) {
            val node = bubble.fetchSemanticsNode()
            val pos = node.layoutInfo.coordinates.positionInWindow()
            val x = pos.x + node.size.width / 2
            val y = pos.y + node.size.height / 2
            val command = "input swipe ${x.toInt()} ${y.toInt()} ${(x + dx).toInt()} ${(y + dy).toInt()} 600"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        dragBy(-screen.width * .78f, -220f)
        rule.waitUntil(5000) { bubble.fetchSemanticsNode().layoutInfo.coordinates.positionInWindow().x < screen.width * .1f }
        val after = bubble.fetchSemanticsNode().layoutInfo.coordinates.positionInWindow()
        assertTrue("Bubble follows horizontal drag", after.x < before.x - 60)
        assertTrue("Bubble follows vertical drag", after.y < before.y - 60)
        rule.onNodeWithTag("support_overlay").assertDoesNotExist()
        dragBy(screen.width * .78f, 0f)
        rule.waitUntil(5000) { kotlin.math.abs(bubble.fetchSemanticsNode().layoutInfo.coordinates.positionInWindow().x - before.x) < 3f }
        Thread.sleep(250)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(rule.activity.getExternalFilesDir(null), "support-bubble-shadow.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        bubble.performClick()
        rule.onNodeWithTag("support_overlay").assertIsDisplayed()
        rule.onNodeWithText("导入完报错").assertExists()
        rule.onNodeWithTag("support_input").performTextInput("接下来怎么操作")
        rule.onNodeWithTag("support_send").performClick()
        rule.waitUntil(10000) { vm.state.value.messages.size == 2 && !vm.state.value.busy }
        rule.onNodeWithText("悬浮窗中的新回答").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("support_bubble").assertIsDisplayed()
    }
    @Test fun realWatchUploadServiceAcceptsEncryptedSyntheticLog() {
        var result: Result<String>? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch { result = runCatching { SupportLogUploader(rule.activity.application).upload("WatchRSS synthetic integration test; no user data; ${UUID.randomUUID()}") { println("Upload stage: $it") } } }
        try { rule.waitUntil(110000) { result != null } } finally { scope.cancel() }
        val code = result!!.getOrThrow()
        assertTrue(code.matches(Regex("[0-9]{6}")))
        println("Synthetic encrypted log uploaded; service returned a six-digit code.")
    }
}
