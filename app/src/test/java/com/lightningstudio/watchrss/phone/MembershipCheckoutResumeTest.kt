package com.lightningstudio.watchrss.phone

import com.lightningstudio.watchrss.phone.account.AppAccessProductInfo
import com.lightningstudio.watchrss.phone.account.AppPaymentOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MembershipCheckoutResumeTest {
    private val oldOrder = AppPaymentOrder(
        product = AppAccessProductInfo(
            productId = "watchrss_cloud_year",
            productName = "云会员365天",
            priceFen = 8800,
            deviceCapacity = 0
        ),
        orderId = "old-order",
        merchantOrderId = "0001",
        amountFen = 8800,
        status = "pending",
        paymentUrl = null,
        provider = "xunhupay",
        qrPayload = "old-qr"
    )

    @Test
    fun expiredOrderWaitsForServerThenUsesReplacement() = runTest {
        val checked = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<AppPaymentOrder>()
        val replacement = oldOrder.copy(orderId = "new-order", qrPayload = "new-qr")
        var creates = 0
        val resumed = async {
            loadMembershipCheckout(
                oldOrder.orderId,
                createOrder = {
                    creates++
                    replacement
                },
                readOrder = {
                    assertEquals(oldOrder.orderId, it)
                    checked.complete(Unit)
                    reply.await()
                },
                recoverOrderQr = { error("Closed order cannot recover a QR") }
            )
        }
        checked.await()
        assertFalse(resumed.isCompleted)
        assertEquals(0, creates)
        reply.complete(oldOrder.copy(status = "closed", qrPayload = null))

        assertEquals(replacement, resumed.await())
        assertEquals(1, creates)
    }

    @Test
    fun verifiedPendingAndAlreadyPaidOrdersUseServerReply() = runTest {
        for (reply in listOf(
            oldOrder.copy(qrPayload = "server-qr"),
            oldOrder.copy(status = "paid", qrPayload = null)
        )) {
            val resumed = loadMembershipCheckout(
                oldOrder.orderId,
                createOrder = { error("Existing order must be used") },
                readOrder = { reply },
                recoverOrderQr = { error("Server already confirmed the state") }
            )
            assertEquals(reply, resumed)
        }
    }

    @Test
    fun missingPendingQrUsesRecoveryAndReplacesOrderIfItExpiresDuringRecovery() = runTest {
        val replacement = oldOrder.copy(orderId = "new-order", qrPayload = "new-qr")
        for (recovered in listOf(
            oldOrder.copy(qrPayload = "recovered-qr"),
            oldOrder.copy(status = "closed", qrPayload = null)
        )) {
            val calls = mutableListOf<String>()
            val resumed = loadMembershipCheckout(
                oldOrder.orderId,
                createOrder = {
                    calls.add("create")
                    replacement
                },
                readOrder = {
                    calls.add("read")
                    oldOrder.copy(qrPayload = null)
                },
                recoverOrderQr = {
                    assertEquals(oldOrder.orderId, it)
                    calls.add("recover")
                    recovered
                }
            )
            if (recovered.status == "closed") {
                assertEquals(replacement, resumed)
                assertEquals(listOf("read", "recover", "create"), calls)
            } else {
                assertEquals(recovered, resumed)
                assertEquals(listOf("read", "recover"), calls)
            }
        }
    }
}
