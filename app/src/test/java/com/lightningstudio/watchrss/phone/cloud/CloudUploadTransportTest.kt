package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.platform.Jdk9Platform
import okhttp3.internal.platform.Platform
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.security.KeyStore
import java.security.Security
import java.security.Provider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class CloudUploadTransportTest {
    private lateinit var previousPlatform: Platform
    private lateinit var previousEcProvider: Provider
    private var previousEcPosition=0
    @Before fun isolateJvmAlpn() {
        // Robolectric installs SSL providers in the shared test JVM. This wire
        // fixture needs actual JDK ALPN, then restores OkHttp's previous platform.
        previousPlatform = Platform.get()
        previousEcProvider = requireNotNull(Security.getProvider("SunEC"))
        previousEcPosition = Security.getProviders().indexOfFirst { it.name=="SunEC" }+1
        // SunJSSE's NamedGroup initialization otherwise picks Robolectric's
        // Conscrypt ECParameters, which cannot initialize JDK binary curves.
        Security.removeProvider("SunEC")
        Security.insertProviderAt(previousEcProvider,1)
        Platform.resetForTests(Jdk9Platform())
    }
    @After fun restorePlatform() {
        Platform.resetForTests(previousPlatform)
        Security.removeProvider("SunEC")
        Security.insertProviderAt(previousEcProvider,previousEcPosition)
    }
    private val session = PhoneAccountSession("owner", "***", "access-private", "", Long.MAX_VALUE)
    private val epoch = "epoch-private"
    private val generation = "22222222-2222-4222-8222-222222222222"
    private val lease = "11111111-1111-4111-8111-111111111111"
    private val bytes = ByteArray(256 * 1024) { (it % 251).toByte() }

    private inner class Fixture(http2: Boolean = true) : Closeable {
        val server = MockWebServer()
        val protocols = CopyOnWriteArrayList<Protocol>()
        val events = CopyOnWriteArrayList<String>()
        val http: OkHttpClient
        val client: PhoneCloudClient
        init {
            val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val password = "test-only".toCharArray()
            val store = KeyStore.getInstance("JKS").apply {
                load(null)
                setKeyEntry("server", cert.keyPair.private, password, arrayOf(cert.certificate))
                setCertificateEntry("trusted", cert.certificate)
            }
            val keys = KeyManagerFactory.getInstance("SunX509", "SunJSSE").apply { init(store, password) }
            val trusts = TrustManagerFactory.getInstance("PKIX", "SunJSSE").apply { init(store) }
            val trust = trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
            val tls = SSLContext.getInstance("TLS", "SunJSSE").apply { init(keys.keyManagers, arrayOf(trust), null) }
            server.useHttps(tls.socketFactory, false)
            server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            server.start()
            http = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
                .protocols(if (http2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(Protocol.HTTP_1_1))
                .callTimeout(10, TimeUnit.SECONDS)
                .addNetworkInterceptor { chain -> chain.proceed(chain.request()).also { protocols += it.protocol } }
                .build()
            client = PhoneCloudClient(AccountEnvironment(backendBaseUrl = server.url("/").toString().trimEnd('/')),
                deviceAccessToken = { "device-private" }, http = http).also {
                it.useEpoch(session, epoch)
                it.useContentGeneration(session, generation)
                it.currentDeviceId = "phone"
                it.onUploadTransportEvent = events::add
            }
        }
        val target get() = CloudUploadObject("chunk", CloudSnapshotCodec.sha256(bytes), bytes.size.toLong(), "private/object",
            server.url("/functions/v1/cloud/uploads/$lease").toString(), reservationId = lease, transport = "epoch-proxy")
        fun reply(code: Int = 200, body: String = "{}") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        suspend fun control() = client.v2Get(session, "/functions/v1/cloud/snapshots/heads")
        suspend fun upload() = client.uploadCiphertext(session, target, bytes, epoch)
        override fun close() {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    @Test fun idleHttp2UploadUsesHttp1OnNextExistingRetryWithoutChangingControlOrFences() = runBlocking {
        Fixture().use { f ->
            f.reply(); f.control()
            f.reply(408, """{"error":"cloud_request_timeout","detail":"upload_body_idle_timeout"}""")
            try { f.upload(); fail("idle timeout swallowed") } catch (e: CloudHttpException) { assertEquals(408, e.status) }
            assertEquals("client must not add an unbounded internal retry", 2, f.server.requestCount)
            f.reply(); f.upload()
            f.reply(); f.control()
            assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_2, Protocol.HTTP_1_1, Protocol.HTTP_2), f.protocols)
            val requests = List(4) { requireNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)) }
            requests.filter { it.method == "PUT" }.forEach {
                assertArrayEquals(bytes, it.body.readByteArray())
                assertEquals("Bearer access-private", it.getHeader("authorization"))
                assertEquals("Bearer device-private", it.getHeader("x-watchrss-device-authorization"))
                assertEquals(epoch, it.getHeader("x-watchrss-library-epoch"))
                assertEquals(generation, it.getHeader("x-watchrss-content-generation"))
                assertEquals("phone", it.getHeader("x-watchrss-cloud-device-id"))
            }
            assertTrue(f.events.any { "fallback-http1" in it })
            assertTrue(f.events.any { "body-sent:bytes=${bytes.size}" in it })
            assertTrue(f.events.any { "connection:h2" in it })
            assertTrue(f.events.any { "connection:http/1.1" in it })
            listOf("access-private", "device-private", epoch, lease, "private/object", f.target.sha256).forEach { secret ->
                assertFalse("diagnostics leaked protected values", f.events.any { secret in it })
            }
        }
    }

    @Test fun serverValidationAndProcessingFailuresDoNotChangeTransportOrRetryThem() = runBlocking {
        listOf(
            403 to """{"error":"not_allowed"}""",
            409 to """{"error":"cloud_content_rebuild_required"}""",
            426 to """{"error":"client_upgrade_required"}""",
            408 to """{"error":"cloud_request_timeout","detail":"upload_processing_timeout"}""",
            408 to """{"error":"cloud_request_timeout","detail":"upload_body_total_timeout"}""",
            503 to """{"error":"upstream_unavailable"}"""
        ).forEach { (status, body) -> Fixture().use { f ->
            f.reply(status, body)
            try { f.upload(); fail("server error swallowed") } catch (e: CloudHttpException) { assertEquals(status, e.status) }
            assertEquals(1, f.server.requestCount)
            f.reply(); f.upload()
            assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_2), f.protocols)
            assertFalse(f.events.any { "fallback-http1" in it })
        } }
    }

    @Test fun anHttp1IdleFailureIsNotMisidentifiedAsHttp2() = runBlocking {
        Fixture(http2 = false).use { f ->
            f.reply(408, """{"error":"cloud_request_timeout","detail":"upload_body_idle_timeout"}""")
            try { f.upload(); fail("idle timeout swallowed") } catch (e: CloudHttpException) { assertEquals(408, e.status) }
            f.reply(); f.upload()
            assertEquals(listOf(Protocol.HTTP_1_1, Protocol.HTTP_1_1), f.protocols)
            assertFalse(f.events.any { "fallback-http1" in it })
        }
    }

    @Test fun brokenDiagnosticStorageCannotFailOrDuplicateAnUpload() = runBlocking {
        Fixture().use { f ->
            f.client.onUploadTransportEvent = { error("diagnostic sink unavailable") }
            f.reply(); f.upload()
            assertEquals(1, f.server.requestCount)
            assertArrayEquals(bytes, requireNotNull(f.server.takeRequest(2, TimeUnit.SECONDS)).body.readByteArray())
            assertEquals(listOf(Protocol.HTTP_2), f.protocols)
        }
    }

    @Test fun defaultOkHttpReplays408ButTheUploadPathLeavesItsBudgetToTheService() = runBlocking {
        Fixture().use { f ->
            val error = """{"error":"cloud_request_timeout","detail":"upload_body_idle_timeout"}"""
            f.reply(408, error); f.reply(408, error)
            f.http.newCall(Request.Builder().url(f.target.signedUrl).put(bytes.toRequestBody()).build()).execute().use {
                assertEquals(408, it.code)
            }
            assertEquals("baseline has an invisible second PUT", 2, f.server.requestCount)
            f.reply(408, error); f.reply(408, error)
            try { f.upload(); fail("idle timeout swallowed") } catch (e: CloudHttpException) { assertEquals(408, e.status) }
            assertEquals("one service attempt must be exactly one PUT", 3, f.server.requestCount)
        }
    }
}
