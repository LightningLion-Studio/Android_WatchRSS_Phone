package com.lightningstudio.watchrss.phone.account

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    }

    @Test
    fun `payment order keeps integer fen`() {
        val order = JSONObject(
            """{"orderId":"id","merchantOrderId":"merchant","amountFen":600,"status":"pending","paymentUrl":"https://example.test/pay"}"""
        ).toPaymentOrder()
        assertEquals(600, order.amountFen)
        assertEquals("pending", order.status)
    }
}
