package com.lightningstudio.watchrss.phone

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lightningstudio.watchrss.phone.account.AppAccessProductInfo
import com.lightningstudio.watchrss.phone.account.AppPaymentOrder
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MembershipQrCheckoutTest {
    @get:Rule
    // The app allows this activity before onboarding or account authorization.
    val compose = createAndroidComposeRule<ContactDeveloperActivity>()

    @Test
    fun continuingPaymentHidesCachedQrUntilServerReplacesExpiredOrder() {
        val paid = AtomicBoolean(false)
        val created = AtomicInteger(0)
        val refreshed = AtomicInteger(0)
        val recheck = CompletableDeferred<Unit>()
        val miniProgramLaunch = AtomicInteger(0)
        val order = AppPaymentOrder(
            product = AppAccessProductInfo(
                productId = MembershipScanPlan.Month.productId,
                productName = "云会员31天",
                priceFen = 800,
                deviceCapacity = 0
            ),
            orderId = "synthetic-order",
            merchantOrderId = "SYNTHETIC",
            amountFen = 800,
            status = "pending",
            paymentUrl = null,
            provider = "xunhupay",
            qrPayload = "weixin://wxpay/bizpayurl?pr=synthetic-test"
        )
        val replacement = order.copy(
            orderId = "replacement-order",
            qrPayload = "weixin://wxpay/bizpayurl?pr=synthetic-replacement"
        )
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                WatchRssPhoneTheme {
                    var step by remember { mutableStateOf("membership") }
                    var initialOrder by remember { mutableStateOf<AppPaymentOrder?>(null) }
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("云同步", style = MaterialTheme.typography.headlineSmall)
                            Text(if (refreshed.get() > 0) "云同步会员" else "云同步会员购买")
                            CloudMembershipPurchaseButton(enabled = true) { step = "methods" }
                            if (created.get() > 0) {
                                TextButton(onClick = {
                                    initialOrder = order
                                    step = "checkout"
                                }) { Text("继续支付") }
                            }
                        }
                    }
                    when (step) {
                        "methods" -> MembershipPaymentChoiceDialog(
                            onScan = { step = "plans" },
                            onWeChat = {
                                miniProgramLaunch.incrementAndGet()
                                step = "membership"
                            },
                            onDismiss = { step = "membership" }
                        )
                        "plans" -> MembershipPlanDialog(
                            onSelect = { plan ->
                                assertEquals(MembershipScanPlan.Month, plan)
                                initialOrder = null
                                step = "checkout"
                            },
                            onDismiss = { step = "membership" }
                        )
                        "checkout" -> MembershipQrCheckout(
                            resumeOrderId = initialOrder?.orderId,
                            createOrder = { idempotencyKey ->
                                check(idempotencyKey.isNotBlank())
                                if (created.incrementAndGet() == 1) order else replacement
                            },
                            readOrder = { orderId ->
                                when (orderId) {
                                    order.orderId -> {
                                        if (initialOrder != null) {
                                            recheck.await()
                                            order.copy(status = "closed", qrPayload = null)
                                        } else {
                                            order
                                        }
                                    }
                                    replacement.orderId ->
                                        if (paid.get()) replacement.copy(status = "paid", qrPayload = null)
                                        else replacement
                                    else -> error("Unexpected order lookup")
                                }
                            },
                            recoverOrderQr = { error("Closed order must use a new checkout") },
                            onPaid = { refreshed.incrementAndGet() },
                            onDismiss = { step = "membership" }
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("购买云同步会员").performClick()
        compose.onNodeWithText("跳转微信").assertIsDisplayed()
        compose.onNodeWithText("扫码支付").assertIsDisplayed()
        screenshot("01-payment-choices.png")
        compose.onNodeWithText("跳转微信").performClick()
        assertEquals(1, miniProgramLaunch.get())
        assertEquals(0, created.get())

        compose.onNodeWithText("购买云同步会员").performClick()
        compose.onNodeWithText("扫码支付").performClick()
        compose.onNodeWithText(MembershipScanPlan.Year.label).assertIsDisplayed()
        compose.onNodeWithText(MembershipScanPlan.Month.label).performClick()
        compose.onNodeWithContentDescription("微信付款二维码").assertIsDisplayed()
        compose.onNodeWithText("用微信扫码付款，付款后自动开通").assertIsDisplayed()
        screenshot("02-payment-qr.png")
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("继续支付").performClick()
        compose.onNodeWithText("正在核对订单…").assertIsDisplayed()
        compose.onNodeWithContentDescription("微信付款二维码").assertDoesNotExist()
        assertEquals(1, created.get())
        recheck.complete(Unit)
        compose.waitUntil { created.get() == 2 }
        compose.onNodeWithContentDescription("微信付款二维码").assertIsDisplayed()

        paid.set(true)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("已开通").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("已开通").assertIsDisplayed()
        compose.waitUntil { refreshed.get() == 1 }
        screenshot("03-membership-opened.png")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            instrumentation.targetContext.openFileOutput(name, 0).use { stream ->
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            screenshot.recycle()
        }
    }
}
