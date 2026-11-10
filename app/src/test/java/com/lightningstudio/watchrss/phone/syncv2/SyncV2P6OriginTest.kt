package com.lightningstudio.watchrss.phone.syncv2

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncV2P6OriginTest {
    private fun rejected(block:()->Unit) {
        try { block();fail("accepted invalid origin") } catch(e:IllegalStateException) { assertEquals("isolated_backend_required",e.message) }
    }
    @Test fun exactAuthenticatedHttpsOriginAndPath() {
        val backend="https://sync.example.invalid:8443".toHttpUrl()
        val endpoint=SyncV2Origin.endpoint(backend.toString())
        assertEquals("/functions/v1/sync/v2/",endpoint.encodedPath)
        SyncV2Origin.validate(endpoint,backend)
        for(url in listOf("http://sync.example.invalid:8443","https://other.invalid:8443","https://sync.example.invalid",
            "https://user@sync.example.invalid:8443","https://sync.example.invalid:8443/?q=1","https://sync.example.invalid:8443/#x")) {
            rejected{SyncV2Origin.validate(url.toHttpUrl().newBuilder().encodedPath(SyncV2Origin.path).build(),backend)}
        }
        rejected{SyncV2Origin.validate(endpoint.newBuilder().encodedPath("/functions/v1/sync/v2/other/").build(),backend)}
        rejected{SyncV2Origin.validate(endpoint,null)}
    }
    @Test fun debugLoopbackUsesExactIpv6Equality() {
        for(host in listOf("localhost","127.0.0.1","[::1]","[0:0:0:0:0:0:0:1]"))
            SyncV2Origin.endpoint("http://$host:18048")
        for(host in listOf("[::10]","[0:0:0:0:0:0:0:10]","[::ffff:127.0.0.1]","127.0.0.2","localhost.evil.invalid"))
            rejected{SyncV2Origin.endpoint("http://$host:18048")}
    }
    @Test fun realHttpRedirectDoesNotReachTarget() {
        MockWebServer().use { server ->
            server.start()
            val signer=SyncV2Signing.fromPrivate(ByteArray(31)+byteArrayOf(1),"aa".repeat(16),"bb".repeat(16))
            val algorithm=org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.2.1"),org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.10045.3.1.7"))
            val spki=org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(algorithm,SyncV2Codec.unhex(signer.publicHex,65)).encoded
            val bootstrap=JSONObject().put("algorithm","ES256").put("receiptKeyId","cd".repeat(16)).put("receiptSigningPublicKey",SyncV2Codec.b64(spki))
            val trust=SyncV2ServiceTrust.fromBootstrap("12".repeat(16),bootstrap,null){}
            val s=SyncV2CloudClient.Session("12121212-1212-1212-1212-121212121212","old","epoch","gen",SyncV2Fixtures.identity().scopeJson,"aa".repeat(16),"synthetic","synthetic")
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/redirect-target")))
            val client=SyncV2CloudClient(server.url(SyncV2Origin.path),{s},trust,true)
            try {client.capabilities();fail("redirect accepted")} catch(_:Exception) {}
            assertEquals(1,server.requestCount)
            assertEquals(SyncV2Origin.path+"capabilities",server.takeRequest().requestUrl!!.encodedPath)
        }
    }
}
