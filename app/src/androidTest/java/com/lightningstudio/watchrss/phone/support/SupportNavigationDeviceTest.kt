package com.lightningstudio.watchrss.phone.support

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lightningstudio.watchrss.phone.SupportScreen
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only navigation checks. No replacement of the phone owner's login or consent. */
@RunWith(AndroidJUnit4::class)
class SupportNavigationDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun showAnswer(target: String, answer: String) {
        val sessions = MutableStateFlow<PhoneAccountSession?>(null)
        lateinit var vm: SupportViewModel
        rule.runOnUiThread {
            vm = SupportViewModel(rule.activity.application, sessions,
                SupportClient(AccountEnvironment(backendBaseUrl = "http://127.0.0.1:1", supabaseAnonKey = "test"), sessions))
        }
        rule.setContent {
            WatchRssPhoneTheme {
                SupportScreen(SupportState(user = "navigation-fixture", loading = false, accepted = true,
                    messages = listOf(SupportMessage("fixture", "在哪里？", answer,
                        status = "ok", actions = """[{"kind":"navigation","target":"$target"}]"""))),
                    vm, {}, {}, {})
            }
        }
        rule.onNodeWithText("[S1]", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("support_action_$target").performScrollTo().performClick()
    }

    private fun awaitText(text: String) {
        rule.waitUntil(20000) {
            runCatching { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    @Test fun fontButtonOpensActualFontLibraryAndMarkdownIsRendered() {
        showAnswer("fonts", "请到 **首页 → 我的 → 设置 → 字体库**。[S1]")
        awaitText("字体库")
        rule.onNodeWithText("字体库").assertIsDisplayed()
        rule.onNodeWithText("导入字体", substring = true).assertIsDisplayed()
    }

    @Test fun notesButtonOpensExistingNotebook() {
        showAnswer("notes", "从首页打开 **备忘录**。[S1]")
        awaitText("笔记")
        rule.onNodeWithText("笔记", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("新建笔记").assertIsDisplayed()
    }

    @Test fun orderButtonScrollsToTheOrderSection() {
        showAnswer("orders", "在账号页查看 **订单与退款**。[S1]")
        awaitText("订单与退款")
        rule.onNodeWithText("订单与退款").assertIsDisplayed()
    }

    @Test fun securityButtonScrollsToSecurityControls() {
        showAnswer("security", "打开 **密码与账号安全**。[S1]")
        awaitText("密码与账号安全")
        rule.onNodeWithText("密码与账号安全").assertIsDisplayed()
    }

    @Test fun addRssButtonOpensInputWithoutSubscribing() {
        showAnswer("add_rss", "在内容页点 **添加 RSS**，填写地址后再确认。[S1]")
        awaitText("添加 RSS 源")
        rule.onNodeWithText("添加 RSS 源").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
    }

    @Test fun cloudButtonOpensCloudPane() {
        showAnswer("cloud_sync", "云备份在账号的 **云同步** 页面。[S1]")
        awaitText("云同步")
        rule.onNodeWithText("云同步").assertIsDisplayed()
        rule.onNodeWithText("会员云空间").assertIsDisplayed()
    }

    @Test fun presetButtonOpensPresetManager() {
        showAnswer("presets", "到 **阅读器与预设** 编辑样式。[S1]")
        awaitText("阅读器与预设")
        rule.onNodeWithText("阅读器与预设").assertIsDisplayed()
    }

    @Test fun importsButtonSelectsHomeImportTab() {
        showAnswer("imports", "到 **导入** 页面选择文件。[S1]")
        awaitText("添加内容")
        rule.onNodeWithTag("imports_url_input").assertIsDisplayed()
        rule.onNodeWithTag("imports_file").assertIsDisplayed()
    }

    @Test fun favoritesButtonSelectsHomeFavoritesChannel() {
        showAnswer("favorites", "从首页打开 **收藏**。[S1]")
        awaitText("收藏")
        // Large screens show both the channel title and sidebar label.
        rule.onAllNodesWithText("收藏").onLast().assertIsDisplayed()
    }
}
