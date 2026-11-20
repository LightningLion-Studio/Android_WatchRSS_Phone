package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudReadDeadlineTest {
    @Test fun fix1LegacyReadCancelsTheStalledRecordsBodyAfterBootstrapAndRefresh() = runBlocking {
        MockWebServer().use { server ->
            val sent = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                    if (response.request.url.encodedPath.endsWith("/records") && response.isSuccessful) sent.countDown()
                }

                override fun canceled(call: Call) {
                    if (call.request().url.encodedPath.endsWith("/records")) cancelled.countDown()
                }
            }).build()
            server.enqueue(MockResponse().setHeadersDelay(200, TimeUnit.MILLISECONDS)
                .setBody("""{"libraryEpoch":"test-epoch","member":{},"devices":[],"keyEnvelopes":[]}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"device_session_revoked"}"""))
            server.enqueue(MockResponse().setBodyDelay(4, TimeUnit.SECONDS).setBody("""{"formatVersion":1}"""))
            var token = "expired-test-token"
            val client = PhoneCloudClient(
                AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/')),
                deviceAccessToken = { token }, http = http, refreshDeviceSession = { _, _ ->
                    delay(150)
                    token = "refreshed-test-token"
                    true
                }
            )
            val session = PhoneAccountSession("test-user", "***", "test-session", "", Long.MAX_VALUE)
            val started = System.nanoTime()
            val outcome = readLegacyCloudRecordStatus(timeoutMillis = 1_200) {
                client.bootstrap(session)
                client.recordPage(session, "0")?.optInt("formatVersion") == 1
            }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertEquals(LegacyCloudRecordStatus.UNAVAILABLE, outcome)
            assertEquals(3, server.requestCount)
            assertEquals(0L, sent.count)
            assertTrue("legacy reader took ${elapsed}ms to leave LOADING", elapsed < 2_500)
            assertTrue("the in-flight response body must be cancelled", cancelled.await(1, TimeUnit.SECONDS))
        }
    }
}
