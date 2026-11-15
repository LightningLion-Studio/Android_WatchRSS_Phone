package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WatchAccountSyncAccessTest {
    private fun expired()=PhoneAccountHttpException(403,"""{"error":"device_session_expired"}""")
    @Test fun refreshNetworkFailurePreservesAccountAndRequiresLaterRetry()=runBlocking<Unit>{
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{throw IOException("refresh unavailable")}) {throw expired()}}.exceptionOrNull()
        assertTrue(failure is PhoneAccountTemporarilyUnavailable);assertFalse(failure is PhoneLoginRequired)
    }
    @Test fun userCancellationDuringRefreshIsPreserved()=runBlocking<Unit>{
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{throw kotlinx.coroutines.CancellationException("cancelled")}) {throw expired()}}.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
    }
    @Test fun invalidDeviceSessionRequiresDeviceVerificationNotAccountLogin()=runBlocking<Unit>{
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{error("no invalid-device refresh")}) {throw PhoneAccountHttpException(403,"""{"error":"device_session_invalid"}""")}}.exceptionOrNull()
        assertTrue(failure is PhoneDeviceVerificationRequired)
    }
    @Test fun validCredentialsIssueOnceWithoutRefreshing()=runBlocking<Unit>{
        var builds=0
        assertEquals("request",withWatchAccountDeviceRefresh({"old"},{error("must not refresh")}) {builds++;"request"})
        assertEquals(1,builds)
    }
    @Test fun expiredDeviceCredentialUsesExistingRefreshThenReissuesOnce()=runBlocking<Unit>{
        var builds=0;var refreshes=0
        assertEquals("request",withWatchAccountDeviceRefresh({"old"},{refreshes++;WatchAccountRefreshResult.Ready}) {
            if(++builds==1)throw expired();"request"
        });assertEquals(2,builds);assertEquals(1,refreshes)
    }
    @Test fun nonRecoverableDeviceIdentityDoesNotReissueOrRequireAccountLogin()=runBlocking<Unit>{
        var builds=0
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{WatchAccountRefreshResult.DeviceVerificationRequired()}) {builds++;throw expired()}}.exceptionOrNull()
        assertTrue(failure is PhoneDeviceVerificationRequired);assertEquals(1,builds)
    }
    @Test fun rejectedRefreshedCredentialRequiresDeviceVerificationWithoutAnotherRefresh()=runBlocking<Unit>{
        var builds=0;var refreshes=0
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{refreshes++;WatchAccountRefreshResult.Ready}) {builds++;throw expired()}}.exceptionOrNull()
        assertTrue(failure is PhoneDeviceVerificationRequired);assertEquals(2,builds);assertEquals(1,refreshes)
    }
    @Test fun expiredAccountTokenRequiresLoginWithoutDeviceRefresh()=runBlocking<Unit>{
        assertTrue(runCatching{withWatchAccountDeviceRefresh({"old"},{error("must not refresh")}) {throw PhoneAccountHttpException(401,"{}")}}.exceptionOrNull() is PhoneLoginRequired)
    }
    @Test fun unrelatedServerConflictIsPreserved()=runBlocking<Unit>{
        val error=PhoneAccountHttpException(409,"""{"error":"device_owned_by_another_account"}""")
        assertSame(error,runCatching{withWatchAccountDeviceRefresh({"old"},{error("must not refresh")}) {throw error}}.exceptionOrNull())
    }
    @Test fun server5xxRateLimitAndSharedBackoffAreTemporary()=runBlocking<Unit>{
        for(error in listOf(PhoneAccountHttpException(503,"{}"),PhoneAccountHttpException(429,"""{"retryAfterMillis":1000}"""),IOException("shared refresh backoff"))) {
            val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{throw error}) {throw expired()}}.exceptionOrNull()
            assertTrue(failure.toString(),failure is PhoneAccountTemporarilyUnavailable)
        }
    }
    @Test fun onlyTypedAccountAuthenticationFailureRequiresLogin()=runBlocking<Unit>{
        assertTrue(runCatching{withWatchAccountDeviceRefresh({"old"},{WatchAccountRefreshResult.AccountUnrecoverable()}) {throw expired()}}.exceptionOrNull() is PhoneLoginRequired)
    }
    @Test fun retiredCloud426DoesNotRequireLoginOrRefresh() = runBlocking<Unit> {
        var refreshes = 0
        val retired = PhoneAccountHttpException(426, """{"error":"upgrade_required"}""")
        val error = runCatching {
            withWatchAccountDeviceRefresh({"device"}, { refreshes++; WatchAccountRefreshResult.Ready }) { throw retired }
        }.exceptionOrNull()
        assertSame(retired, error)
        assertEquals(0, refreshes)
    }

}
