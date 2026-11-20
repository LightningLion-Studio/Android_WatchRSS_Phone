package com.lightningstudio.watchrss.phone

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lightningstudio.watchrss.phone.account.WeChatHandoff
import com.lightningstudio.watchrss.phone.account.WeChatHandoffUnavailable
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WeChatPurchaseLinkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val baseUrl = "https://wxmpurl.cn/kck25HzrRpc"
    private val fixtureToken = "A_-".repeat(14) + "A"

    @Test
    fun opensTheCloudMembershipUrlWithEncodedCq() = runBlocking {
        val activity = compose.activity

        assertTrue(activity.openPurchaseInWeChat { WeChatHandoff(fixtureToken, Long.MAX_VALUE) })

        val intent = shadowOf(activity).nextStartedActivity
        val uri = requireNotNull(intent.data)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(setOf("cq"), uri.queryParameterNames)
        val encodedQuery = requireNotNull(uri.encodedQuery)
        assertTrue(encodedQuery == "cq=t%3D$fixtureToken")
        assertTrue(uri.getQueryParameter("cq") == "t=$fixtureToken")
        assertTrue(encodedQuery.substringAfter("cq=").length <= 256)
        assertNull(intent.extras)
    }

    @Test
    fun opensTheBaseUrlWhenSignedOut() = runBlocking {
        val activity = compose.activity

        assertTrue(activity.openPurchaseInWeChat())

        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(baseUrl, intent.dataString)
        assertNull(intent.extras)
    }

    @Test
    fun fallsBackWithoutAnErrorWhenIssuanceFails() = runBlocking {
        val activity = compose.activity
        assertTrue(activity.openPurchaseInWeChat { throw WeChatHandoffUnavailable() })
        assertEquals(baseUrl, shadowOf(activity).nextStartedActivity.dataString)
    }

    @Test
    fun doesNotSendAnExpiredHandoff() = runBlocking {
        val activity = compose.activity
        assertTrue(activity.openPurchaseInWeChat { WeChatHandoff(fixtureToken, 1L) })
        assertEquals(baseUrl, shadowOf(activity).nextStartedActivity.dataString)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelsIssuanceAndOpensTheBaseUrlAtThreeSeconds() = runBlocking {
        val activity = compose.activity
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        var cancelled = false
        val opened = scope.async {
            activity.openPurchaseInWeChat {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }

        scheduler.runCurrent()
        scheduler.advanceTimeBy(2_999)
        scheduler.runCurrent()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(cancelled)

        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(opened.isCompleted)
        assertTrue(opened.await())
        assertTrue(cancelled)
        assertEquals(baseUrl, shadowOf(activity).nextStartedActivity.dataString)
    }

    @Test
    fun cancellationOfThePurchaseDoesNotOpenTheBrowser() {
        val activity = compose.activity
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val opened = scope.async { activity.openPurchaseInWeChat { awaitCancellation() } }

        scheduler.runCurrent()
        opened.cancel()
        scheduler.runCurrent()
        assertTrue(opened.isCancelled)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun reportsFallbackWhenNoAppCanOpenTheUrl() = runBlocking {
        val context = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException()
            }
        }

        assertFalse(context.openPurchaseInWeChat())
    }

    @Test fun fallbackOnlyShowsTheManualInstructionsAndDismisses() {
        var dismissed = 0
        compose.setContent {
            WatchRssPhoneTheme {
                WeChatPurchaseFallbackDialog(onDismiss = { dismissed++ })
            }
        }

        compose.onNodeWithText("请打开微信，搜索小程序「腕上RSS」，登录后购买云同步会员。").assertExists()
        assertTrue(compose.onAllNodesWithText("打开支付链接").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("知道了").performClick()
        assertEquals(1, dismissed)
    }
}
