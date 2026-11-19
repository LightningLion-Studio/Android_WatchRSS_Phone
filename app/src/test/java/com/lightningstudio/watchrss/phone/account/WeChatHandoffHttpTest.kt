package com.lightningstudio.watchrss.phone.account

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeChatHandoffHttpTest {
    private val session = PhoneAccountSession("owner", "masked", "fixture-bearer", "fixture-refresh", Long.MAX_VALUE)
    private val secret = buildString { repeat(32) { append('y') } }

    private fun client(server: MockWebServer): PhoneAccountClient = PhoneAccountClient(
        AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/'), supabaseAnonKey = "fixture-key"),
        LicenseDeviceIdentity(ApplicationProvider.getApplicationContext()),
        httpClient = OkHttpClient()
    )

    @Test fun postsAuthenticatedPurchaseHandoffAndParsesTheResponse() = runBlocking {
        MockWebServer().use { server ->
            val path = "pages/cloud-purchase/cloud-purchase?ticket=" + secret
            server.enqueue(MockResponse().setBody(JSONObject().put("ticket", secret).put("expiresAt", Long.MAX_VALUE)
                .put("miniPath", path).toString()))
            val handoff = client(server).createWeChatHandoff(session)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/functions/v1/app/wechat-handoff", request.path)
            assertTrue(request.getHeader("Authorization") == "Bearer " + session.accessToken)
            assertEquals("purchase", JSONObject(request.body.readUtf8()).getString("purpose"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            assertTrue(handoff.miniPath == path)
        }
    }

    @Test fun failureBodyAndRedirectAreNotExposedOrRetried() = runBlocking {
        MockWebServer().use { server ->
            val transport = client(server)
            for (response in listOf(
                MockResponse().setResponseCode(429).setBody(secret),
                MockResponse().setResponseCode(307).setHeader("Location", "/must-not-follow"),
                MockResponse().setBody("broken " + secret)
            )) {
                server.enqueue(response)
                try {
                    transport.createWeChatHandoff(session)
                    fail("Must return a safe handoff error")
                } catch (failure: WeChatHandoffUnavailable) {
                    assertFalse(failure.toString().contains(secret))
                    // Coroutine stack recovery can wrap the sanitized exception in a copy.
                    assertTrue(generateSequence<Throwable>(failure) { it.cause }
                        .all { it is WeChatHandoffUnavailable && !it.toString().contains(secret) })
                }
            }
            assertEquals(3, server.requestCount)
        }
    }
}
