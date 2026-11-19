package com.lightningstudio.watchrss.phone

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.tencent.mm.opensdk.modelbiz.WXLaunchMiniProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WeChatPurchaseLinkTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(instrumentedPackages = ["com.tencent.mm.opensdk.modelbiz"])
    fun sdkRequestUsesTheReleaseMiniProgram() {
        val miniPath = "pages/cloud-purchase/cloud-purchase?ticket=" + "x".repeat(32)
        val request = weChatPurchaseRequest("gh_fixture", miniPath)

        assertEquals("gh_fixture", request.userName)
        assertTrue(request.path == miniPath)
        assertEquals(WXLaunchMiniProgram.Req.MINIPTOGRAM_TYPE_RELEASE, request.miniprogramType)
    }

    @Test fun fallbackOnlyShowsTheManualInstructionsAndDismisses() {
        var dismissed = 0
        compose.setContent {
            WatchRssPhoneTheme {
                WeChatPurchaseFallbackDialog(onDismiss = { dismissed++ })
            }
        }

        compose.onNodeWithText("请打开微信，搜索小程序「腕上RSS」，登录后购买云同步权限。").assertExists()
        assertTrue(compose.onAllNodesWithText("打开支付链接").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("知道了").performClick()
        assertEquals(1, dismissed)
    }
}
