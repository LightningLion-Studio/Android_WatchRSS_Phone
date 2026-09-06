package com.lightningstudio.watchrss.phone.support

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightningstudio.watchrss.phone.SupportScreen
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the local support_smoke_server and adb reverse tcp:18088 tcp:18088.
 * Uses an isolated in-memory identity; never replaces the phone owner's saved login.
 */
@RunWith(AndroidJUnit4::class)
class SupportDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun consentStreamingSourcesKeyboardAndAccountIsolation() {
        val sessions = MutableStateFlow<PhoneAccountSession?>(PhoneAccountSession(
            "00000000-0000-4000-8000-000000000034", "客服测试", "wrp_support_local_device_fixture", "", System.currentTimeMillis() + 3600000
        ))
        val client = SupportClient(AccountEnvironment(backendBaseUrl = "http://127.0.0.1:18088", supabaseAnonKey = "test"), sessions)
        lateinit var vm: SupportViewModel
        var declined = false
        rule.runOnUiThread { vm = SupportViewModel(rule.activity.application, sessions, client) }
        rule.setContent {
            WatchRssPhoneTheme {
                val state by vm.state.collectAsState()
                SupportScreen(state, vm, { declined = true }, {}, {})
            }
        }
        rule.waitUntil(30000) { !vm.state.value.loading && !vm.state.value.busy }
        assertNull(vm.state.value.error)
        rule.onNodeWithTag("support_accept").assertIsDisplayed()
        rule.onNodeWithTag("support_input").assertDoesNotExist()
        rule.onNodeWithText("不同意，返回").performClick()
        assertTrue(declined)
        assertFalse(vm.state.value.accepted)
        rule.onNodeWithTag("support_accept").performClick()
        rule.waitUntil(30000) { vm.state.value.accepted && !vm.state.value.busy }
        rule.onNodeWithTag("support_input").performClick().performTextInput("Android蓝牙同步连接失败应该怎么检查？请查询知识库。")
        rule.onNodeWithTag("support_send").assertIsDisplayed().performClick()
        rule.waitUntil(120000) { !vm.state.value.busy && vm.state.value.messages.isNotEmpty() }
        assertNull(vm.state.value.error)
        assertEquals("ok", vm.state.value.messages.last().status)
        assertTrue(vm.state.value.messages.last().answer.isNotBlank())
        assertTrue(requiresLogConsent(vm.state.value.messages.last().actions))
        rule.onNodeWithTag("support_log_decline").performScrollTo().performClick()
        assertEquals("declined", vm.state.value.messages.last().logState)
        rule.onNodeWithTag("support_input").performClick().performTextInput("我想给阅读器导入字体文件，具体在哪里操作？")
        rule.onNodeWithTag("support_send").performClick()
        rule.waitUntil(120000) { !vm.state.value.busy && vm.state.value.messages.size == 2 }
        assertNull(vm.state.value.error)
        assertEquals("ok", vm.state.value.messages.last().status)
        assertTrue(vm.state.value.messages.last().answer.contains("字体库"))
        assertFalse(vm.state.value.messages.last().answer.contains("[S"))
        rule.onNodeWithTag("support_action_fonts").performScrollTo().performClick()
        rule.waitUntil(20000) {
            runCatching { rule.onAllNodesWithText("字体库").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        rule.onNodeWithText("导入字体", substring = true).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        rule.waitUntil(20000) { rule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty() }
        androidx.test.espresso.Espresso.pressBack()
        rule.waitUntil(20000) { rule.onAllNodesWithTag("support_input").fetchSemanticsNodes().isNotEmpty() }
        // Log out without changing the device owner's account store.
        rule.runOnUiThread { sessions.value = null }
        rule.waitUntil(10000) { vm.state.value.user == null }
        rule.onNodeWithText("请先登录后使用 AI 客服").assertIsDisplayed()
        assertTrue(vm.state.value.messages.isEmpty())
        rule.onNodeWithTag("support_input").assertDoesNotExist()
        client.cancel()
    }
}
