package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [35])
class AccountHttpResponseTest {
    private val session = PhoneAccountSession("owner", "masked", "synthetic-account", "", Long.MAX_VALUE)
    private var status = 200
    private var closed = false
    private var sourceCreationFailure = false
    private var normalBody = false
    private var networkFailure = false
    private val readFailure = SocketTimeoutException("synthetic response body timeout")
    private val client = PhoneAccountClient(
        AccountEnvironment(backendBaseUrl = "https://response.invalid", supabaseAnonKey = "fixture"),
        LicenseDeviceIdentity(RuntimeEnvironment.getApplication()),
        httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (networkFailure) throw UnknownHostException("synthetic DNS failure")
            val body = if (normalBody) "{\"deviceStatus\":\"authorized\"}".toResponseBody() else object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = 100L
                override fun source(): okio.BufferedSource {
                    if (sourceCreationFailure) throw readFailure
                    return object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long = throw readFailure
                        override fun timeout() = Timeout.NONE
                        override fun close() { closed = true }
                    }.buffer()
                }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .header("Retry-After", "11").body(body).build()
        }.build()
    )

    @Test fun bodyTimeoutAfterAnyHttpStatusPreservesStatusAndRetryAfter() = runBlocking<Unit> {
        for (code in listOf(200, 206, 302, 304, 400, 401, 403, 409, 429, 503)) {
            status = code; closed = false
            val failure = runCatching { client.securityStatus(session) }.exceptionOrNull()!!
            val http = failure.findAccountHttpException()!!
            assertEquals(code, http.statusCode)
            assertEquals(11000L, http.retryAfterMillis)
            assertSame(readFailure, http.cause)
            assertFalse(isAccountNetworkFailure(failure))
            assertTrue("Close broken body for HTTP $code", closed)
        }
    }

    @Test fun failureOpeningTheBodySourceAlsoPreservesSuccessfulHttpStatus() = runBlocking<Unit> {
        sourceCreationFailure = true
        val failure = runCatching { client.securityStatus(session) }.exceptionOrNull()!!
        assertEquals(200, failure.findAccountHttpException()?.statusCode)
        assertFalse(isAccountNetworkFailure(failure))
    }

    @Test fun knownServerResponseWinsOverAnOuterNetworkBackoffWrapper() {
        val http = PhoneAccountHttpException(503, "", 11000).apply { initCause(readFailure) }
        val backoff = DeviceSessionRefreshBackoffException(1000, true).apply { initCause(http) }
        assertFalse(isAccountNetworkFailure(IOException("outer IO", backoff)))
        val classified = accountSyncFailureResult(http) as WatchAccountRefreshResult.TemporaryFailure
        assertEquals(11000L, classified.retryAfterMillis)
        val failure = runCatching { classified.requireReady() }.exceptionOrNull()
        assertTrue(failure is PhoneAccountTemporarilyUnavailable)
        assertFalse(failure is PhoneAccountNetworkRequired)
    }

    @Test fun noResponseDnsFailureRetainsNetworkClassification() = runBlocking<Unit> {
        networkFailure = true
        val failure = runCatching { client.securityStatus(session) }.exceptionOrNull()!!
        assertNull(failure.findAccountHttpException())
        assertTrue(isAccountNetworkFailure(failure))
    }

    @Test fun successfulResponseParsingRetainsBaselineBehavior() = runBlocking<Unit> {
        normalBody = true
        assertEquals("authorized", client.appAccessStatus(session).deviceStatus)
    }
}
