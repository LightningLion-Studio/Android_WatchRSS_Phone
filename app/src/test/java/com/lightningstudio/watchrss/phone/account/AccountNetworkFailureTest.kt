package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.connection.bluetooth.BluetoothSyncRecovery
import com.lightningstudio.watchrss.phone.connection.bluetooth.isRetryableBluetoothDisconnect
import com.lightningstudio.watchrss.phone.viewmodel.syncFailureMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.*

class AccountNetworkFailureTest {
    @Test fun accountTransportFailuresHaveNetworkPromptAndNoBluetoothRetry() = runBlocking<Unit> {
        for (cause in listOf(UnknownHostException(), SocketTimeoutException(), NoRouteToHostException(), ConnectException(), IOException())) {
            val failure = runCatching { accountSyncFailureResult(cause).requireReady() }.exceptionOrNull()!!
            assertTrue(failure is PhoneAccountNetworkRequired)
            assertEquals("需要联网完成账号验证", syncFailureMessage(failure, "账号授权同步中"))
            var reconnects = 0
            val recovery = BluetoothSyncRecovery({ fail("network failure must not delay") }, { reconnects++ }, { _, _, _ -> fail("network failure must not report reconnect") })
            assertSame(failure, runCatching { recovery.run<Unit> { throw failure } }.exceptionOrNull())
            assertEquals(0, reconnects)
            assertFalse(isRetryableBluetoothDisconnect(IOException("wrapper", failure)))
        }
    }

    @Test fun rawDnsAndRoutingCausesAreNotBluetoothDisconnects() {
        for (cause in listOf(UnknownHostException(), NoRouteToHostException(), ConnectException())) {
            assertFalse(isRetryableBluetoothDisconnect(cause))
            assertFalse(isRetryableBluetoothDisconnect(IOException("wrapper", cause)))
        }
    }

    @Test fun rfcommReadTimeoutAndEofRetainTheirRetryBudget() = runBlocking<Unit> {
        for (failure in listOf(SocketTimeoutException("RFCOMM read deadline"), EOFException("RFCOMM EOF"))) {
            var attempts = 0
            var reconnects = 0
            val retries = mutableListOf<Int>()
            val recovery = BluetoothSyncRecovery({}, { reconnects++ }, { n, _, _ -> retries += n })
            assertEquals("completed", recovery.run { if (attempts++ == 0) throw failure; "completed" })
            assertEquals(listOf(1), retries)
            assertEquals(1, reconnects)
        }
    }

    @Test fun serverAndBackoffFailuresKeepTheirExistingMeaning() {
        for (failure in listOf(PhoneAccountHttpException(503, "{}"), DeviceSessionRefreshBackoffException(1000))) {
            val classified = runCatching { accountSyncFailureResult(failure).requireReady() }.exceptionOrNull()
            assertTrue(classified is PhoneAccountTemporarilyUnavailable)
            assertFalse(classified is PhoneAccountNetworkRequired)
            assertFalse(isAccountNetworkFailure(IOException("wrapper", failure)))
        }
    }
}
