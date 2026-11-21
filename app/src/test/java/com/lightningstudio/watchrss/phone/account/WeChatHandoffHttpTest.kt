package com.lightningstudio.watchrss.phone.account

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.lightningstudio.watchrss.phone.WeChatPurchaseResult
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import com.lightningstudio.watchrss.phone.network.CloudHttpDeadline
import com.lightningstudio.watchrss.phone.openPurchaseInWeChat
import com.lightningstudio.watchrss.phone.purchaseTestAppAccessStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
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

    private fun sessionStore() = EncryptedAccountSessionStore(context).also {
        ReflectionHelpers.setField(it, "prefsRef", context.getSharedPreferences("handoff-session", Context.MODE_PRIVATE))
    }

    private fun repository(
        server: MockWebServer,
        store: EncryptedAccountSessionStore,
        http: OkHttpClient = OkHttpClient()
    ) = PhoneAccountRepository(environment(server), store, PhoneInstallationIdentity(context), client(server, http), "phone")

    @Test
    fun missingOrMismatchedSessionDoesNotIssue() = runBlocking {
        MockWebServer().use { server ->
            val store = sessionStore()
            val repository = repository(server, store)
            assertNull(repository.captureWeChatPurchaseSession(session, authenticated = true))
            store.save(session)
            assertNull(repository.captureWeChatPurchaseSession(session.copy(userId = "another-owner"), authenticated = true))
            assertNull(repository.captureWeChatPurchaseSession(session.copy(accessToken = "new-bearer"), authenticated = true))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun accountChangeDuringIssuanceCancelsThePurchase() = runBlocking {
        assertSessionChangeCancelsPurchase(session.copy(userId = "another-owner"))
    }

    @Test
    fun sameAccountTokenChangeDuringIssuanceCancelsThePurchase() = runBlocking {
        assertSessionChangeCancelsPurchase(session.copy(accessToken = "new-bearer"))
    }

    private suspend fun assertSessionChangeCancelsPurchase(replacement: PhoneAccountSession) =
        coroutineScope {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val store = sessionStore()
                store.save(session)
                val repository = repository(server, store)
                val purchase = requireNotNull(repository.captureWeChatPurchaseSession(session, authenticated = true))
                val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                val activity = controller.get()
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    activity.openPurchaseInWeChat(
                        withCurrentSession = { action -> repository.withCurrentWeChatPurchaseSession(purchase, action) }
                    ) { repository.createWeChatHandoff(purchase) }
                }
                try {
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    store.save(replacement)
                    result.join()
                    assertTrue(result.isCancelled)
                    assertNull(shadowOf(activity).nextStartedActivity)
                    assertEquals(1, server.requestCount)
                } finally {
                    result.cancelAndJoin()
                    controller.pause().stop().destroy()
                }
            }
        }

    @Test
    fun accountChangeAfterIssuanceValidationBlocksTheIntent() = runBlocking {
        assertChangeAfterIssuanceBlocksIntent(restoreOriginal = false)
    }

    @Test
    fun switchingAwayAndBackAfterIssuanceStillBlocksTheIntent() = runBlocking {
        assertChangeAfterIssuanceBlocksIntent(restoreOriginal = true)
    }

    private suspend fun assertChangeAfterIssuanceBlocksIntent(restoreOriginal: Boolean) {
        MockWebServer().use { server ->
            server.enqueue(issued())
            val store = sessionStore()
            store.save(session)
            val repository = repository(server, store)
            val purchase = requireNotNull(repository.captureWeChatPurchaseSession(session, authenticated = true))
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            try {
                val result = activity.openPurchaseInWeChat(
                    withCurrentSession = { action -> repository.withCurrentWeChatPurchaseSession(purchase, action) }
                ) {
                    val handoff = repository.createWeChatHandoff(purchase)
                    store.save(session.copy(userId = "another-owner", accessToken = "another-bearer"))
                    if (restoreOriginal) store.save(session)
                    handoff
                }

                assertEquals(WeChatPurchaseResult.CANCELLED, result)
                assertNull(shadowOf(activity).nextStartedActivity)
                assertEquals(1, server.requestCount)
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    @Test
    fun logoutWaitingForLoginPreservesTheNewAccount() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val store = sessionStore()
            store.save(session)
            val repository = repository(server, store)
            val next = session.copy(userId = "next-owner", accessToken = "next-bearer")
            val keys = CloudKeyManager(context, "", context.getSharedPreferences("handoff-cloud", Context.MODE_PRIVATE))
            val root = ByteArray(32) { it.toByte() }
            keys.saveAccountKey(next.userId, 1, root, true)
            repository.onClearCloudState = { keys.clearAccountKeys(it.userId) }
            val releasedOwners = mutableListOf<String>()
            repository.onReleaseCloudAuthorization = { releasedOwners += it.userId }

            val loginWriting = CompletableDeferred<Unit>()
            val allowLoginWrite = CountDownLatch(1)
            val loginPublished = CompletableDeferred<Unit>()
            val allowLoginFinish = CountDownLatch(1)
            val prefs = context.getSharedPreferences("handoff-session", Context.MODE_PRIVATE)
            ReflectionHelpers.setField(store, "prefsRef", object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun remove(key: String?): SharedPreferences.Editor = apply { editor.remove(key) }

                        override fun commit(): Boolean {
                            if (repository.session.value == session && !loginWriting.isCompleted) {
                                loginWriting.complete(Unit)
                                check(allowLoginWrite.await(5, TimeUnit.SECONDS))
                            } else if (repository.session.value == next && !loginPublished.isCompleted) {
                                loginPublished.complete(Unit)
                                check(allowLoginFinish.await(5, TimeUnit.SECONDS))
                            }
                            return editor.commit()
                        }
                    }
                }
            })

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val accessStore = purchaseTestAppAccessStore(context)
            val coordinator = AppAccessCoordinator(context, repository, LicenseDeviceIdentity(context), scope, accessStore)
            try {
                val login = async(start = CoroutineStart.UNDISPATCHED) {
                    repository.saveCompletedLogin(LoginProgress("login", 1, listOf("sms"), true, next))
                }
                try {
                    withTimeout(5_000) { loginWriting.await() }
                    val logout = async(start = CoroutineStart.UNDISPATCHED) { runCatching { coordinator.logout() } }
                    assertFalse(logout.isCompleted)
                    allowLoginWrite.countDown()
                    withTimeout(5_000) { loginPublished.await() }
                    val nextFence = repository.b2AccountFence
                    val nextAuthorization = AppAuthorization("next-device", Long.MAX_VALUE, "next-lease",
                        Long.MAX_VALUE, "next-release", AppAccessSummary(deviceStatus = "authorized"))
                    val pendingOrder = AppPaymentOrder(orderId = "next-order", merchantOrderId = "next-merchant",
                        amountFen = 100, status = "pending", paymentUrl = null)
                    accessStore.save(nextAuthorization)
                    accessStore.savePendingOrder(pendingOrder, next.userId)
                    allowLoginFinish.countDown()
                    login.await()
                    val result = logout.await()

                    assertEquals(next, repository.session.value)
                    assertTrue(repository.hasUsableSession)
                    nextFence.check()
                    assertTrue(keys.getAccountKey(next.userId, 1)?.contentEquals(root) == true)
                    assertEquals(nextAuthorization, accessStore.load())
                    assertEquals(pendingOrder, accessStore.loadPendingOrder(next.userId))
                    assertTrue(releasedOwners.isEmpty())
                    assertEquals(0, server.requestCount)
                    assertNull(store.pendingRevocation())
                    assertEquals("账号已变更，请重试", result.exceptionOrNull()?.message)
                } finally {
                    allowLoginWrite.countDown()
                    allowLoginFinish.countDown()
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun stalled401BodyClearsOfflineAuthorization() = verifyTruncatedRejection(
        401, MockResponse().setBodyDelay(4, TimeUnit.SECONDS)
    )

    @Test
    fun disconnected403BodyClearsOfflineAuthorization() = verifyTruncatedRejection(
        403, MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
    )

    private fun verifyTruncatedRejection(status: Int, response: MockResponse) = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response.setResponseCode(status).setBody("x".repeat(1024)))
            val store = sessionStore()
            store.save(session)
            lateinit var repository: PhoneAccountRepository
            var blockedWhenBodyFailed: Boolean? = null
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseFailed(call: Call, ioe: IOException) {
                    blockedWhenBodyFailed = repository.offlineAuthorizationBlocked(session.userId)
                }
            }).build()
            repository = repository(server, store, http)
            val grant = JSONObject().put("account", JSONObject()
                .put("userId", session.userId)
                .put("backendBaseUrl", environment(server).backendBaseUrl))
            store.saveBluetoothGrant("watch", grant)
            val originalFence = repository.b2AccountFence
            assertFalse(repository.offlineAuthorizationBlocked(session.userId))
            var revocations = 0
            originalFence.onRevoke { revocations++ }.use {
                val start = System.nanoTime()
                val failure = runCatching {
                    withContext(CloudHttpDeadline.after(1_500)) { repository.appAccessStatus() }
                }.exceptionOrNull()
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                assertTrue("failed body read: $failure", failure is IOException)
                assertTrue("body read took ${elapsedMillis}ms", elapsedMillis < 3_000)
                assertEquals(1, server.requestCount)
                assertEquals("offline authorization at body failure", true, blockedWhenBodyFailed)
                assertTrue(repository.offlineAuthorizationBlocked(session.userId))
                assertNull(store.bluetoothGrant("watch"))
                assertEquals(1, revocations)
                assertNotSame(originalFence, repository.b2AccountFence)
            }
        }
    }

    @Test
    fun logoutEntryInvalidatesThePurchaseBeforeWaitingForTheAccessCoordinator() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val store = sessionStore()
            store.save(session)
            val repository = repository(server, store)
            val purchase = requireNotNull(repository.captureWeChatPurchaseSession(session, authenticated = true))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val coordinator = AppAccessCoordinator(
                context, repository, LicenseDeviceIdentity(context), scope, purchaseTestAppAccessStore(context)
            )
            val coordinatorLock = ReflectionHelpers.getField<Mutex>(coordinator, "operationMutex")
            coordinatorLock.lock()
            val logout = async(start = CoroutineStart.UNDISPATCHED) { coordinator.logout() }
            try {
                assertFalse(logout.isCompleted)
                assertEquals(session, store.session.value)
                assertNull(repository.captureWeChatPurchaseSession(session, authenticated = true))
                assertNull(repository.withCurrentWeChatPurchaseSession(purchase) { true })
                assertEquals(0, server.requestCount)
            } finally {
                coordinatorLock.unlock()
                logout.await()
                scope.cancel()
            }
            assertNull(store.session.value)
            val loggedOutPurchase = requireNotNull(repository.captureWeChatPurchaseSession(null, authenticated = false))
            // One advance for logout entry and one for publishing the cleared session.
            assertEquals(purchase.generation + 2, loggedOutPurchase.generation)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun logoutBeforeCloudReleaseAndWhileRevocationIsPendingBlocksIntentAndTimeoutFallback() = runBlocking {
        MockWebServer().use { server ->
            val returnRevocation = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/functions/v1/account/logout") {
                        check(returnRevocation.await(5, TimeUnit.SECONDS))
                        return MockResponse().setResponseCode(204)
                    }
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
            }
            val store = sessionStore()
            store.save(session)
            val repository = repository(server, store)
            val releaseStarted = CompletableDeferred<Unit>()
            val finishRelease = CompletableDeferred<Unit>()
            repository.onReleaseCloudAuthorization = {
                releaseStarted.complete(Unit)
                finishRelease.await()
            }
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = controller.get()
            val purchase = requireNotNull(repository.captureWeChatPurchaseSession(session, authenticated = true))
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(StandardTestDispatcher(scheduler))

            // Hold two issuers in virtual time to exercise the final guard even if cancellation
            // and the timeout happen in the opposite order from the HTTP watcher.
            fun pendingTimeout() = scope.async {
                activity.openPurchaseInWeChat(
                    withCurrentSession = { action -> repository.withCurrentWeChatPurchaseSession(purchase, action) }
                ) { awaitCancellation() }
            }
            val timeoutDuringLogout = pendingTimeout()
            scheduler.runCurrent()
            scheduler.advanceTimeBy(1)
            val timeoutAfterLogout = pendingTimeout()
            scheduler.runCurrent()

            val issuing = async(start = CoroutineStart.UNDISPATCHED) {
                activity.openPurchaseInWeChat(
                    withCurrentSession = { action -> repository.withCurrentWeChatPurchaseSession(purchase, action) }
                ) { repository.createWeChatHandoff(purchase) }
            }
            var logout: Deferred<Unit>? = null
            try {
                assertEquals("/functions/v1/app/wechat-handoff/issue", server.takeRequest(2, TimeUnit.SECONDS)?.path)
                logout = async(start = CoroutineStart.UNDISPATCHED) { repository.logout() }
                withTimeout(2_000) { releaseStarted.await() }
                issuing.join()
                assertTrue(issuing.isCancelled)
                assertEquals(session, store.session.value)
                assertNull(repository.captureWeChatPurchaseSession(session, authenticated = true))
                assertNull(shadowOf(activity).nextStartedActivity)

                finishRelease.complete(Unit)
                val revocation = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
                assertEquals("/functions/v1/account/logout", revocation?.path)
                assertNull(repository.captureWeChatPurchaseSession(session, authenticated = true))
                assertEquals(session, store.session.value)
                scheduler.advanceTimeBy(2_999)
                scheduler.runCurrent()
                assertEquals(WeChatPurchaseResult.CANCELLED, timeoutDuringLogout.await())
                assertFalse(timeoutAfterLogout.isCompleted)
                assertNull(shadowOf(activity).nextStartedActivity)

                returnRevocation.countDown()
                logout.await()
                assertNull(store.session.value)
                scheduler.advanceTimeBy(1)
                scheduler.runCurrent()
                assertEquals(WeChatPurchaseResult.CANCELLED, timeoutAfterLogout.await())
                assertNull(shadowOf(activity).nextStartedActivity)
            } finally {
                finishRelease.complete(Unit)
                returnRevocation.countDown()
                issuing.cancelAndJoin()
                logout?.cancelAndJoin()
                timeoutDuringLogout.cancel()
                timeoutAfterLogout.cancel()
                scheduler.runCurrent()
                controller.pause().stop().destroy()
            }
        }
    }
}
