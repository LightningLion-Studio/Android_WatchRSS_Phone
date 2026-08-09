package com.lightningstudio.watchrss.phone.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccessReconciliationPolicyTest {
    private val cached = AppAccessSummary(
        purchaseCount = 1,
        capacity = 3,
        occupied = 1,
        deviceStatus = "authorized"
    )

    @Test
    fun validLeaseAllowsOfflineAccessWithoutAnAccountSession() {
        assertEquals(
            AppAccessState.Authorized(cached, offline = true),
            initialAppAccessState(cached, cachedLeaseValid = true, hasUsableSession = false)
        )
        assertEquals(
            AppAccessState.LoggedOut,
            initialAppAccessState(cached, cachedLeaseValid = false, hasUsableSession = false)
        )
        assertNull(
            initialAppAccessState(cached, cachedLeaseValid = false, hasUsableSession = true)
        )
    }

    @Test
    fun missingOrUnknownServerStatusKeepsTheValidOfflineLease() {
        listOf(
            null,
            AppAccessSummary(deviceStatus = "unknown")
        ).forEach { server ->
            val decision = validLeaseRefreshDecision(cached, server)
            assertFalse(decision.clearCache)
            assertEquals(AppAccessState.Authorized(cached, offline = true), decision.state)
        }
    }

    @Test
    fun authorizedServerStatusKeepsTheLeaseAndUsesFreshSummary() {
        val server = cached.copy(occupied = 2)
        val decision = validLeaseRefreshDecision(cached, server)

        assertFalse(decision.clearCache)
        assertEquals(AppAccessState.Authorized(server, offline = true), decision.state)
    }

    @Test
    fun authoritativeNegativeStatusesClearTheLease() {
        val revoked = AppAccessSummary(deviceStatus = "revoked")
        val unclaimed = AppAccessSummary(deviceStatus = "unclaimed")
        val purchaseRequired = AppAccessSummary(deviceStatus = "purchase_required")

        val revokedDecision = validLeaseRefreshDecision(cached, revoked)
        val unclaimedDecision = validLeaseRefreshDecision(cached, unclaimed)
        val purchaseDecision = validLeaseRefreshDecision(cached, purchaseRequired)

        assertTrue(revokedDecision.clearCache)
        assertEquals(AppAccessState.Revoked(revoked), revokedDecision.state)
        assertTrue(unclaimedDecision.clearCache)
        assertEquals(AppAccessState.ReauthenticationRequired(unclaimed), unclaimedDecision.state)
        assertTrue(purchaseDecision.clearCache)
        assertEquals(AppAccessState.PurchaseRequired(purchaseRequired), purchaseDecision.state)
    }
}
