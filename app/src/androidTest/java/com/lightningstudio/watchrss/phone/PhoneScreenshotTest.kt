// AndroidX Test 的 screenshot API 位于 androidx.test.runner.screenshot 包下；
// 该类在 runner 1.7.0 中标记为 @Deprecated，但仍是用户要求的 AndroidX Test
// 截图入口，且在当前设备上可稳定工作。替代方案（Espresso captureToBitmap）
// 在 Android 15+ 上同样依赖 InputManager 反射，保留此 API 更稳妥。
@file:Suppress("DEPRECATION")
package com.lightningstudio.watchrss.phone

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.screenshot.Screenshot
import com.karumi.shot.ScreenshotTest
import com.lightningstudio.watchrss.phone.test.RealDataTestHelper
import com.lightningstudio.watchrss.phone.test.ScreenshotTestPermissions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneScreenshotTest : ScreenshotTest {

    private val composeTestRule = createAndroidComposeRule<HomeActivity>()
    private val permissionRule = ScreenshotTestPermissions.grantRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(permissionRule)
        .around(composeTestRule)

    /**
     * 覆盖 Shot 默认实现，跳过 [androidx.test.espresso.Espresso.onIdle]。
     *
     * Espresso 3.6.x 在 Android 15 (API 35) 上反射调用已被移除的
     * `InputManager.getInstance()` 会抛出 [NoSuchMethodException]，
     * 导致 Shot 截图前的等待逻辑崩溃。仅使用 instrumentation 的 idle
     * 同步已足够让 Compose 完成布局与动画。
     */
    override fun waitForAnimationsToFinish() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * 使用 AndroidX Test 的 [Screenshot] API 捕获当前 Activity 窗口。
     *
     * 选择 `capture(view)` 只截取 App 自身窗口，避免状态栏/导航栏图标
     * 在不同模拟器/不同时间产生像素漂移；得到的结果与旧版手动
     * `decorView.draw(canvas)` 等价，但由 AndroidX Test 统一处理。
     */
    private fun captureActivityBitmap(): Bitmap {
        val activity = composeTestRule.activity
        return Screenshot.capture(activity.window.decorView).bitmap
    }

    @Before
    fun setUp() {
        // 锁定竖屏，避免截图方向不一致
        composeTestRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // 使用真实数据库准备数据
        RealDataTestHelper.seedPopulatedLibrary()
        composeTestRule.waitForIdle()
    }

    @Test
    fun dashboard_populated() {
        composeTestRule.onNodeWithTag("dashboard_sync_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tile_content").assertIsDisplayed()
        // 必须在 @Test 方法体内直接调用 compareScreenshot，
        // 这样 Shot 的 TestNameDetector 才能通过栈迹识别测试名。
        compareScreenshot(captureActivityBitmap(), "dashboard_populated")
    }

    @Test
    fun dashboard_empty() {
        RealDataTestHelper.seedEmptyLibrary()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("dashboard_sync_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tile_content").assertIsDisplayed()
        compareScreenshot(captureActivityBitmap(), "dashboard_empty")
    }

    @Test
    fun imports_page() {
        composeTestRule.onNodeWithTag("nav_imports").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("imports_url_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("imports_rss").assertIsDisplayed()
        composeTestRule.onNodeWithTag("imports_article").assertIsDisplayed()
        compareScreenshot(captureActivityBitmap(), "imports_page")
    }

    @Test
    fun imports_url_typed() {
        composeTestRule.onNodeWithTag("nav_imports").performClick()
        composeTestRule.onNodeWithTag("imports_url_input")
            .performTextInput("https://example.com/article")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("imports_article").assertIsDisplayed()
        compareScreenshot(captureActivityBitmap(), "imports_url_typed")
    }

    @Test
    fun content_page() {
        composeTestRule.onNodeWithTag("nav_rss").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("channel_row").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithTag("channel_row")[0].assertIsDisplayed()
        compareScreenshot(captureActivityBitmap(), "content_page")
    }

    @Test
    fun content_channel_opened() {
        composeTestRule.onNodeWithTag("nav_rss").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("channel_row").fetchSemanticsNodes().size >= 4
        }
        // 真实 RSS 源频道在固定虚拟频道之后，索引约为 3
        composeTestRule.onAllNodesWithTag("channel_row")[3].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("article_row").fetchSemanticsNodes().isNotEmpty()
        }

        // waitUntil 已确认 article_row 存在；截图本身即是断言。
        compareScreenshot(captureActivityBitmap(), "content_channel_opened")
    }

    @Test
    fun content_empty_channel() {
        RealDataTestHelper.seedEmptyLibrary()
        composeTestRule.onNodeWithTag("nav_rss").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("channel_row").fetchSemanticsNodes().isNotEmpty()
        }
        // 点击“独立文章”频道，展开后才会显示 channel_empty
        composeTestRule.onAllNodesWithTag("channel_row")[2].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag("channel_empty").fetchSemanticsNodes().isNotEmpty()
        }

        // waitUntil 已确认 channel_empty 存在；截图本身即是断言。
        compareScreenshot(captureActivityBitmap(), "content_empty_channel")
    }

    @Test
    fun imports_with_recent() {
        // 当前 @Before 已经 seedPopulatedLibrary，其中包含独立文章，
        // 会出现在“最近导入”列表中。
        composeTestRule.onNodeWithTag("nav_imports").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag("recent_import_item")[0].assertIsDisplayed()
        compareScreenshot(captureActivityBitmap(), "imports_with_recent")
    }
}
