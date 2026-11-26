package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CloudDeviceSessionConcurrencyTest {
    private val session = PhoneAccountSession("owner", "***", "account-token", "", Long.MAX_VALUE)

    private suspend fun refusesUnchangedRetryToken(next: String?) {
        val token = AtomicReference<String?>("old-device-token")
        val requests = AtomicInteger()
        val client = PhoneCloudClient(
            AccountEnvironment(backendBaseUrl = "https://test.invalid"),
            deviceAccessToken = token::get,
            http = OkHttpClient.Builder().addInterceptor { chain ->
                requests.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(403).message("fixture")
                    .body("{\"error\":\"device_session_revoked\"}".toResponseBody("application/json".toMediaType())).build()
            }.build(),
            refreshDeviceSession = { _, _ -> token.set(next); true }
        )
        assertTrue(runCatching { client.v2Get(session, "/functions/v1/cloud/approvals") }
            .exceptionOrNull() is CloudDeviceSessionRefreshException)
        assertEquals("never replay the revoked token after a nominally successful refresh", 1, requests.get())
    }

    @Test fun successfulRefreshWithoutNewTokenMustNotSendRevokedTokenAgain() = runBlocking {
        refusesUnchangedRetryToken("old-device-token")
    }

    @Test fun clearedTokenMustNotRetainAuthorizationOnTheMutableRequestBuilder() = runBlocking {
        refusesUnchangedRetryToken(null)
    }

    @Test fun concurrentCloudClientsShareOneRefreshAndNeverSendARotatedSession() = runBlocking {
        val cached = AtomicReference(AppAuthorization("old", Long.MAX_VALUE, "lease", Long.MAX_VALUE, "", AppAccessSummary()))
        val refreshes = AtomicInteger(); val calls = AtomicInteger(); val firstWave = AtomicInteger()
        val peak = AtomicInteger(); val active = AtomicInteger(); val ready = CompletableDeferred<Unit>()
        val gate = DeviceSessionRefreshCoordinator(
            load = cached::get, save = cached::set, owner = { "owner" },
            refresh = { current -> refreshes.incrementAndGet(); delay(20); current.copy(deviceAccessToken = "new") },
            elapsedMillis = { 0 }, wallMillis = { 0 }
        )
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val token = chain.request().header("x-watchrss-device-authorization")
            assertEquals("a request cannot race a rotation", "Bearer ${cached.get().deviceAccessToken}", token)
            val reject = token == "Bearer old"
            if (reject) {
                val n = active.incrementAndGet(); peak.accumulateAndGet(n, ::maxOf)
                if (firstWave.incrementAndGet() == 24) ready.complete(Unit)
                runBlocking { withTimeout(10_000) { ready.await() } }
                active.decrementAndGet()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (reject) 403 else 200).message("fixture")
                .body((if (reject) "{\"error\":\"device_session_revoked\"}" else "{}")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun client() = PhoneCloudClient(
            AccountEnvironment(backendBaseUrl = "https://test.invalid"),
            deviceAccessToken = { cached.get().deviceAccessToken }, http = http,
            refreshDeviceSession = { rejected, owner -> gate.refresh(owner, rejected); true },
            deviceSessionRequest = object : CloudDeviceSessionRequest {
                override suspend fun <T> withDeviceSession(expectedUserId: String, request: suspend (String?) -> T): T =
                    gate.withSession(expectedUserId, request)
            }
        )
        val clients = listOf(client(), client())
        withTimeout(15_000) {
            (0 until 24).map { n -> async(Dispatchers.IO) {
                clients[n % 2].v2Get(session, "/functions/v1/cloud/approvals")
            } }.awaitAll()
        }
        assertEquals(1, refreshes.get()); assertEquals(48, calls.get()); assertEquals(24, peak.get())
        assertEquals("new", cached.get().deviceAccessToken)
    }

    @Test fun secondForbiddenResponseIsNeverRetriedAgain() = runBlocking {
        val token = AtomicReference("old"); var calls = 0; var refreshes = 0
        val client = PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"),
            deviceAccessToken = token::get,
            http = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("fixture")
                    .body("{\"error\":\"device_session_revoked\"}".toResponseBody("application/json".toMediaType())).build()
            }.build(), refreshDeviceSession = { _, _ -> refreshes++; token.set("new"); true })
        assertTrue(runCatching { client.v2Get(session, "/functions/v1/cloud/approvals") }.exceptionOrNull() is CloudDeviceSessionRefreshException)
        assertEquals(2, calls); assertEquals(1, refreshes)
    }
}
