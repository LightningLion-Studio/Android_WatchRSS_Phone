package com.lightningstudio.watchrss.phone.account

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lightningstudio.watchrss.phone.refundConsequenceText
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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

    @Test
    fun sharedOrderRequestsOptIntoMembershipRefundsAndDescribeTheirEffects() = runBlocking {
        MockWebServer().use { server ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val environment = AccountEnvironment(
                backendBaseUrl = server.url("/").toString().trimEnd('/'),
                supabaseAnonKey = "synthetic-key"
            )
            val client = PhoneAccountClient(environment, LicenseDeviceIdentity(context))
            val session = PhoneAccountSession("owner", "masked", "fixture-bearer", "", Long.MAX_VALUE)
            val membership = JSONObject()
                .put("orderId", "membership")
                .put("provider", "xunhupay")
                .put("product", JSONObject().put("productId", "watchrss_cloud_month"))
                .put("status", "paid")
                .put("refundable", true)
            val device = JSONObject()
                .put("orderId", "device")
                .put("product", JSONObject().put("productId", "watchrss_phone_device_authorization"))
            server.enqueue(MockResponse().setBody(JSONObject().put("orders", JSONArray(listOf(membership, device))).toString()))
            server.enqueue(MockResponse().setBody(membership.toString()))
            server.enqueue(MockResponse().setBody(JSONObject(membership.toString()).put("status", "refunded").toString()))

            val orders = client.paymentOrders(session)
            assertTrue(orders[0].refundable)
            assertEquals("退款成功后，只回收本订单剩余的云会员时长。退款不可撤销。", refundConsequenceText(orders[0]))
            assertTrue(refundConsequenceText(orders[1]).contains("3 台手机容量"))
            assertTrue(client.paymentOrder(session, "membership").refundable)
            assertEquals("refunded", client.refundPaymentOrder(session, "membership", "fixture-verification", "refund-key").status)

            listOf("/functions/v1/payments/orders", "/functions/v1/payments/orders/membership",
                "/functions/v1/payments/orders/membership/refund").forEach { path ->
                val request = server.takeRequest()
                assertEquals(path, request.path)
                assertEquals("1", request.getHeader("X-WatchRSS-Xunhu-Membership-Refund"))
            }
        }
    }

    @Test
    fun onlyConfirmedFailureSuppliesANewRefundRequestKey() = runBlocking {
        MockWebServer().use { server ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val client = PhoneAccountClient(
                AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/'), supabaseAnonKey = "synthetic-key"),
                LicenseDeviceIdentity(context)
            )
            val session = PhoneAccountSession("owner", "masked", "fixture-bearer", "", Long.MAX_VALUE)
            val order = JSONObject()
                .put("orderId", "membership")
                .put("provider", "xunhupay")
                .put("product", JSONObject().put("productId", "watchrss_cloud_month"))
            val attempts = listOf(
                Triple("paid", null, "refund-membership"),
                Triple("refund_failed", "refund-retry", "refund-retry"),
                Triple("refund_failed", "refund-retry", "refund-retry"),
                Triple("refund_pending", "refund-retry", "refund-retry")
            )
            for ((status, key, expected) in attempts) {
                order.put("status", status).put("refundRequestKey", key)
                server.enqueue(MockResponse().setBody(order.toString()))
                client.refundPaymentOrder(session, order.toPaymentOrder(), "fixture-verification")
                assertEquals(expected, JSONObject(server.takeRequest().body.readUtf8()).getString("idempotencyKey"))
            }
        }
    }
}
