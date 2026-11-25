package com.lightningstudio.watchrss.phone.account

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MembershipPaymentHttpTest {
    @Test
    fun purchaseSendsSkuAndReadsQrPayloadFromOrder() = runBlocking {
        MockWebServer().use { server ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val environment = AccountEnvironment(
                backendBaseUrl = server.url("/").toString().trimEnd('/'),
                supabaseAnonKey = "synthetic-key"
            )
            val client = PhoneAccountClient(environment, LicenseDeviceIdentity(context))
            val session = PhoneAccountSession("owner", "masked", "fixture-bearer", "fixture-refresh", Long.MAX_VALUE)
            val qr = "weixin://wxpay/bizpayurl?pr=synthetic-test"
            val response = JSONObject()
                .put("orderId", "synthetic-order")
                .put("provider", "xunhupay")
                .put("product", JSONObject().put("productId", "watchrss_cloud_year"))
                .put("amountFen", 8800)
                .put("status", "pending")
                .put("paymentUrl", JSONObject.NULL)
                .put("qrPayload", qr)
            server.enqueue(MockResponse().setBody(response.toString()))

            val order = client.createMembershipPaymentOrder(session, "watchrss_cloud_year", "synthetic-key")
            val create = server.takeRequest()
            assertEquals("POST", create.method)
            assertEquals("/functions/v1/payments/xunhupay/membership-orders", create.path)
            assertEquals("Bearer fixture-bearer", create.getHeader("Authorization"))
            val body = JSONObject(create.body.readUtf8())
            assertEquals(setOf("productId", "idempotencyKey"), body.keys().asSequence().toSet())
            assertEquals("watchrss_cloud_year", body.getString("productId"))
            assertTrue(order.isXunhuMembership)
            assertEquals(8800, order.amountFen)
            assertEquals(qr, order.qrPayload)
            assertNull(order.paymentUrl)

            server.enqueue(MockResponse().setBody(response.toString()))
            assertEquals(qr, client.resumeMembershipPaymentOrder(session, order.orderId).qrPayload)
            assertEquals(
                "/functions/v1/payments/xunhupay/membership-orders/synthetic-order/resume",
                server.takeRequest().path
            )

            val legacy = JSONObject()
                .put("orderId", "device")
                .put("provider", "xunhupay")
                .put("paymentUrl", "https://example.invalid/checkout")
                .toPaymentOrder()
            assertFalse(legacy.isXunhuMembership)
            assertEquals("https://example.invalid/checkout", legacy.paymentUrl)
            assertNull(legacy.qrPayload)
        }
    }
}
