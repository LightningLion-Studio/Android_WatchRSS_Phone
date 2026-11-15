package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WatchAccountSyncAccessTest {
    @Test fun refreshNetworkFailureRequiresLogin()=runBlocking<Unit>{
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{throw java.io.IOException("refresh unavailable")}) {
            throw PhoneAccountHttpException(403,"""{"error":"device_session_expired"}""")
        }}.exceptionOrNull()
        assertTrue(failure is PhoneLoginRequired)
    }
    @Test fun userCancellationDuringRefreshIsPreserved()=runBlocking<Unit>{
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{throw kotlinx.coroutines.CancellationException("cancelled")}) {
            throw PhoneAccountHttpException(403,"""{"error":"device_session_expired"}""")
        }}.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
    }
    @Test fun invalidDeviceSessionRequiresLoginWithoutInventingAnotherRefreshPath()=runBlocking<Unit>{
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{error("must not refresh invalid device")}) {
            throw PhoneAccountHttpException(403,"""{"error":"device_session_invalid"}""")
        }}.exceptionOrNull()
        assertTrue(failure is PhoneLoginRequired)
    }

    @Test fun validCredentialsIssueOnceWithoutRefreshing()=runBlocking<Unit>{
        var builds=0
        assertEquals("request",withWatchAccountDeviceRefresh({"old"},{error("must not refresh")}) { builds++;"request" })
        assertEquals(1,builds)
    }
    @Test fun expiredDeviceCredentialUsesExistingRefreshThenReissuesOnce()=runBlocking<Unit>{
        var builds=0;var refreshes=0
        val result=withWatchAccountDeviceRefresh({"old"},{assertEquals("old",it);refreshes++;true}) {
            if(++builds==1)throw PhoneAccountHttpException(403,"""{"error":"device_session_expired"}""")
            "request"
        }
        assertEquals("request",result);assertEquals(2,builds);assertEquals(1,refreshes)
    }
    @Test fun failedDeviceRefreshRequiresLoginAndDoesNotReissue()=runBlocking<Unit>{
        var builds=0
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{false}) { builds++;throw PhoneAccountHttpException(403,"""{"error":"device_session_expired"}""") }}.exceptionOrNull()
        assertTrue(failure is PhoneLoginRequired);assertEquals(1,builds)
    }
    @Test fun rejectedRefreshedCredentialRequiresLoginWithoutAnotherRefresh()=runBlocking<Unit>{
        var builds=0;var refreshes=0
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{refreshes++;true}) { builds++;throw PhoneAccountHttpException(403,"""{"error":"device_session_revoked"}""") }}.exceptionOrNull()
        assertTrue(failure is PhoneLoginRequired);assertEquals(2,builds);assertEquals(1,refreshes)
    }
    @Test fun expiredAccountTokenRequiresLoginWithoutDeviceRefresh()=runBlocking<Unit>{
        val failure=runCatching{withWatchAccountDeviceRefresh({"old"},{error("must not refresh account token")}) { throw PhoneAccountHttpException(401,"{}") }}.exceptionOrNull()
        assertTrue(failure is PhoneLoginRequired);assertEquals("登录已失效，请重新登录",failure!!.message)
    }
    @Test fun unrelatedServerFailureIsPreserved()=runBlocking<Unit>{
        val error=PhoneAccountHttpException(409,"""{"error":"device_owned_by_another_account"}""")
        assertSame(error,runCatching{withWatchAccountDeviceRefresh({"old"},{error("must not refresh")}) {throw error}}.exceptionOrNull())
    }
}
