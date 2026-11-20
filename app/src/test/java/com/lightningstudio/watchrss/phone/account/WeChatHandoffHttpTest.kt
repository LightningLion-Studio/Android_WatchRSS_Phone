package com.lightningstudio.watchrss.phone.account

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WeChatHandoffHttpTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = PhoneAccountSession("owner", "masked", "fixture-bearer", "fixture-refresh", Long.MAX_VALUE)
    private val fixtureToken = "A_-".repeat(14) + "A"

    private fun environment(server: MockWebServer) =
        AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/'), supabaseAnonKey = "fixture-key")

    private fun client(server: MockWebServer, http: OkHttpClient = OkHttpClient()) =
        PhoneAccountClient(environment(server), LicenseDeviceIdentity(context), httpClient = http)

    private fun issued(expiresAt: Long = System.currentTimeMillis() + 60_000) =
        MockResponse().setBody(JSONObject().put("token", fixtureToken).put("expiresAt", expiresAt).toString())

    private suspend fun expectUnavailable(block: suspend () -> Unit): WeChatHandoffUnavailable {
        try {
            block()
        } catch (failure: WeChatHandoffUnavailable) {
            return failure
        }
        throw AssertionError("Expected a sanitized handoff failure")
    }

    @Test
    fun postsAuthenticatedIssueRequestAndParsesTheResult() = runBlocking {
        MockWebServer().use { server ->
            val expiresAt = System.currentTimeMillis() + 60_000
            server.enqueue(issued(expiresAt))

            val handoff = client(server).createWeChatHandoff(session)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/functions/v1/app/wechat-handoff/issue", request.path)
            assertTrue(request.getHeader("Authorization") == "Bearer " + session.accessToken)
            assertEquals("fixture-key", request.getHeader("apikey"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            assertEquals(0, JSONObject(request.body.readUtf8()).length())
            assertTrue(handoff.token == fixtureToken)
            assertEquals(expiresAt, handoff.expiresAtMillis)
            assertFalse(handoff.toString().contains(fixtureToken))
        }
    }

    @Test
    fun rejectsRedirectsAndUnsafeResponsesWithoutRetryOrDisclosure() = runBlocking {
        MockWebServer().use { server ->
            val transport = client(server)
            val responses = listOf(
                MockResponse().setResponseCode(429).setBody(fixtureToken),
                MockResponse().setResponseCode(307).setHeader("Location", "/must-not-follow"),
                MockResponse().setBody("broken $fixtureToken"),
                MockResponse().setBody(JSONObject().put("token", fixtureToken + "&extra=1").put("expiresAt", 7L).toString()),
                MockResponse().setBody(JSONObject().put("token", fixtureToken).put("expiresAt", "bad").toString()),
                MockResponse().setBody("z".repeat(4_097))
            )
            for (response in responses) {
                server.enqueue(response)
                val failure = expectUnavailable { transport.createWeChatHandoff(session) }
                assertTrue(generateSequence<Throwable>(failure) { it.cause }
                    .all { it is WeChatHandoffUnavailable && !it.toString().contains(fixtureToken) })
            }
            assertEquals(responses.size, server.requestCount)
        }
    }

    @Test
    fun cancelsTheHttpCallWhenTheCoroutineIsCancelled() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelled.countDown()
                }
            }).build()
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                client(server, http).createWeChatHandoff(session)
            }
            try {
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                job.cancelAndJoin()
                assertTrue(cancelled.await(1, TimeUnit.SECONDS))
                assertEquals(1, server.requestCount)
            } finally {
                job.cancelAndJoin()
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun checksTheAccountBeforeIssuingAndWhenTheResponseReturns() = runBlocking {
        MockWebServer().use { server ->
            val releaseResponse = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!releaseResponse.await(2, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    return issued()
                }
            }
            val store = EncryptedAccountSessionStore(context)
            ReflectionHelpers.setField(store, "prefsRef", context.getSharedPreferences("handoff-session", Context.MODE_PRIVATE))
            val repository = PhoneAccountRepository(
                environment(server), store, PhoneInstallationIdentity(context), client(server), "phone"
            )
            expectUnavailable { repository.createWeChatHandoff(session.userId) }
            store.save(session)
            expectUnavailable { repository.createWeChatHandoff("another-owner") }
            assertEquals(0, server.requestCount)

            val result = async(start = CoroutineStart.UNDISPATCHED) {
                expectUnavailable { repository.createWeChatHandoff(session.userId) }
            }
            try {
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                store.save(session.copy(userId = "another-owner", accessToken = "fixture-other-bearer"))
            } finally {
                releaseResponse.countDown()
            }
            result.await()
            assertEquals(1, server.requestCount)
        }
    }
}
