package com.lightningstudio.watchrss.phone.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccessModelsTest {
    @Test
    fun `access summary preserves permanent capacity counters`() {
        val summary = JSONObject(
            """{"purchaseCount":2,"capacity":6,"occupied":5,"deviceStatus":"revoked","revokeReason":"capacity_eviction"}"""
        ).toAccessSummary()
        assertEquals(2, summary.purchaseCount)
        assertEquals(6, summary.capacity)
        assertEquals(5, summary.occupied)
        assertEquals("revoked", summary.deviceStatus)
        assertEquals("capacity_eviction", summary.revokeReason)
        assertEquals("none", summary.accessMode)
        assertFalse(summary.trialEligible)
    }

    @Test
    fun `access summary preserves server controlled trial fields`() {
        val summary = JSONObject(
            """{"purchaseCount":0,"capacity":1,"occupied":1,"deviceStatus":"authorized","accessMode":"trial","trialEligible":false,"trialStartedAt":1760000000000,"trialExpiresAt":1760259200000}"""
        ).toAccessSummary()

        assertEquals("trial", summary.accessMode)
        assertFalse(summary.trialEligible)
        assertEquals(1760000000000L, summary.trialStartedAtMillis)
        assertEquals(1760259200000L, summary.trialExpiresAtMillis)
    }

    @Test
    fun `trial eligibility is explicit and defaults closed`() {
        assertFalse(JSONObject("{}").toAccessSummary().trialEligible)
        assertTrue(JSONObject("""{"trialEligible":true}""").toAccessSummary().trialEligible)
    }

    @Test
    fun `payment order keeps integer fen`() {
        val order = JSONObject(
            """{"orderId":"id","merchantOrderId":"merchant","amountFen":600,"status":"pending","paymentUrl":"https://example.test/pay"}"""
        ).toPaymentOrder()
        assertEquals(600, order.amountFen)
        assertEquals("pending", order.status)
    }

    @Test
    fun `payment order preserves refund window and eligibility`() {
        val order = JSONObject(
            """{"orderId":"id","merchantOrderId":"merchant","amountFen":600,"status":"paid","paidAt":1760000000000,"refundEligibleUntil":1760604800000,"refundable":true}"""
        ).toPaymentOrder()

        assertEquals(1760000000000L, order.paidAtMillis)
        assertEquals(1760604800000L, order.refundEligibleUntilMillis)
        assertTrue(order.refundable)
        assertNull(order.refundedAtMillis)
    }

    @Test
    fun `legacy payment response fails closed for refund eligibility`() {
        val order = JSONObject(
            """{"orderId":"id","merchantOrderId":"merchant","amountFen":600,"status":"paid"}"""
        ).toPaymentOrder()

        assertFalse(order.refundable)
        assertNull(order.refundEligibleUntilMillis)
    }

    @Test
    fun `pending payment order is restored only for its owner`() {
        val json = JSONObject(
            """{"userId":"user-a","orderId":"id","merchantOrderId":"merchant","amountFen":600,"status":"pending"}"""
        )

        assertEquals("id", json.toPaymentOrderForUser("user-a")?.orderId)
        assertNull(json.toPaymentOrderForUser("user-b"))
    }

    @Test
    fun `legacy pending payment order without owner is discarded`() {
        val json = JSONObject(
            """{"orderId":"id","merchantOrderId":"merchant","amountFen":600,"status":"pending"}"""
        )

        assertNull(json.toPaymentOrderForUser("user-a"))
    }
}
