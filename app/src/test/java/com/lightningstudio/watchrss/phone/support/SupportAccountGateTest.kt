package com.lightningstudio.watchrss.phone.support

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Support uses its account bearer, independently of device authorization and watch data gates. */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class,manifest=Config.NONE,sdk=[35])
class SupportAccountGateTest {
    private val session=PhoneAccountSession("support-owner","synthetic","synthetic-support-token","",Long.MAX_VALUE)
    @Test fun validAccountCanReachSupportWithoutDeviceLeaseOrWatch()=runBlocking<Unit> {
        val server=MockWebServer();server.start()
        val client=SupportClient(AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic"),MutableStateFlow(session))
        try {
            server.enqueue(MockResponse().setBody("{\"version\":\"synthetic\"}"))
            assertEquals("synthetic",client.json("support-owner","/agreement").getString("version"))
            val request=server.takeRequest()
            assertEquals("/functions/v1/support/agreement",request.path)
            assertEquals("Bearer synthetic-support-token",request.getHeader("authorization"))
            assertEquals(1,server.requestCount)
        } finally {client.cancel();server.shutdown()}
    }
    @Test fun pausedAccountBlocksCredentialsButKeepsHumanAndLoginDestinations()=runBlocking<Unit> {
        val server=MockWebServer();server.start()
        val client=SupportClient(AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic"),MutableStateFlow(session.copy(authenticationPaused=true)))
        try {
            assertNull(client.identity())
            assertEquals("support_login_required",(runCatching{client.json("support-owner","/agreement")}.exceptionOrNull() as SupportFailure).code)
            assertEquals(0,server.requestCount)
            val context=RuntimeEnvironment.getApplication()
            assertEquals("com.lightningstudio.watchrss.phone.ContactDeveloperActivity",SupportDestination.CONTACT.createIntent(context).component!!.className)
            assertEquals("com.lightningstudio.watchrss.phone.AccountActivity",SupportDestination.ACCOUNT.createIntent(context).component!!.className)
        } finally {client.cancel();server.shutdown()}
    }
    @Test fun temporarySupportFailureDoesNotBecomeInvalidAccount()=runBlocking<Unit> {
        val server=MockWebServer();server.start()
        val client=SupportClient(AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/'),supabaseAnonKey="synthetic"),MutableStateFlow(session))
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":\"support_disabled\"}"))
            assertEquals("support_disabled",(runCatching{client.json("support-owner","/agreement")}.exceptionOrNull() as SupportFailure).code)
            assertEquals("support-owner",client.identity())
        } finally {client.cancel();server.shutdown()}
    }
}
