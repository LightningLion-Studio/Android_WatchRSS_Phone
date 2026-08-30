package com.lightningstudio.watchrss.phone.connection.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSyncSessionReplayDecisionTest {
    @Test
    fun libraryExchange_isNotSafeForTransparentReplay() {
        assertFalse(
            isSessionOperationSafeForTransparentReplay(
                BluetoothSyncProtocol.ACTION_SYNC_LIBRARY
            )
        )
    }

    @Test
    fun ordinarySessionAction_remainsSafeForTransparentReplay() {
        assertTrue(
            isSessionOperationSafeForTransparentReplay(
                BluetoothSyncProtocol.ACTION_SYNC_ACCOUNT
            )
        )
    }
}
