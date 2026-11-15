package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Headers completing on IO do not mean that a response body has arrived yet. */
class CloudResponseBodyThreadTest {
    private val session = PhoneAccountSession("owner", "***", "account-token", "", Long.MAX_VALUE)

    @Test fun aUiCallerNeverReadsTheDeferredJsonBodyOnItsOwnThread() = runBlocking {
        val reads = AtomicInteger()
        val payload = "{\"value\":\"${"delayed-body-".repeat(4096)}\"}"
        val body = object : ResponseBody() {
            private val source = object : ForwardingSource(Buffer().writeUtf8(payload)) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    check(!Thread.currentThread().name.startsWith("cloud-ui-test")) { "socket body read on UI" }
                    reads.incrementAndGet()
                    // Deliver several small chunks, like a body arriving after its headers.
                    return super.read(sink, minOf(byteCount, 173))
                }
            }.buffer()
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
        val client = PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"),
            http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body).build()
            }.build())
        Executors.newSingleThreadExecutor { Thread(it, "cloud-ui-test") }.asCoroutineDispatcher().use { ui ->
            withContext(ui) {
                val result = client.v2Get(session, "/functions/v1/cloud/approvals")
                assertEquals("delayed-body-".repeat(4096), result.getString("value"))
                assertTrue("the caller resumes on its UI dispatcher", Thread.currentThread().name.startsWith("cloud-ui-test"))
            }
        }
        assertTrue("the deferred body was consumed in multiple reads", reads.get() > 100)
    }

    @Test fun aUiCallerAlsoClosesAnUnreadSuccessBodyOnIo() = runBlocking {
        val closes = AtomicInteger()
        val body = object : ResponseBody() {
            private val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
                override fun close() {
                    check(!Thread.currentThread().name.startsWith("cloud-ui-test")) { "socket discard on UI" }
                    closes.incrementAndGet()
                    super.close()
                }
            }.buffer()
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
        val client = PhoneCloudClient(AccountEnvironment(backendBaseUrl = "https://test.invalid"),
            http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body).build()
            }.build())
        Executors.newSingleThreadExecutor { Thread(it, "cloud-ui-test") }.asCoroutineDispatcher().use { ui ->
            withContext(ui) {
                client.registerDevice(session, "device", "fixture", "spki", 1, expectedEpoch = "epoch")
                assertTrue(Thread.currentThread().name.startsWith("cloud-ui-test"))
            }
        }
        assertEquals(1, closes.get())
    }
}
