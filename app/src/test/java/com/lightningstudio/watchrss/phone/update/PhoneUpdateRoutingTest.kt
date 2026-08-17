package com.lightningstudio.watchrss.phone.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUpdateRoutingTest {
    @Test
    fun unavailableMarketAlwaysFallsBackToAnnouncement() {
        assertFalse(
            shouldOfferStoreUpdate(
                marketAvailable = false,
                storeVersionCode = 99,
                currentVersionCode = 32
            )
        )
    }

    @Test
    fun availableMarketOffersOnlyNewerStoreVersion() {
        assertTrue(shouldOfferStoreUpdate(true, storeVersionCode = 33, currentVersionCode = 32))
        assertFalse(shouldOfferStoreUpdate(true, storeVersionCode = 32, currentVersionCode = 32))
        assertFalse(shouldOfferStoreUpdate(true, storeVersionCode = null, currentVersionCode = 32))
    }
}
