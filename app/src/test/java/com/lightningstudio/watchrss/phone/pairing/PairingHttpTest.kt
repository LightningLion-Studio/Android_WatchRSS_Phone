package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.cloud.*
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PairingHttpTest {
    private val d=PairingTestData
    private val session=PhoneAccountSession(d.user,"mask","phone-login","",Long.MAX_VALUE)
    @Test fun relayUsesConfiguredBackendAndSeparateCapabilitiesWithAppAccessRefreshAndNoRedirects() = runBlocking {
        val requests=mutableListOf<Request>();var appToken="old";var refreshed=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            requests+=chain.request()
            val status=if(requests.size==1)403 else 200
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
                .body((if(status==403)"{\"error\":\"device_session_expired\"}"else"{\"claimed\":true}").toResponseBody("application/json".toMediaType())).build()
        }.build()
        val transport=PairingRelayHttp(AccountEnvironment(backendBaseUrl="https://test.invalid/test",supabaseAnonKey="anon"),{session},{appToken},{_,user->assertEquals(d.user,user);refreshed++;appToken="new";true},http)
        val body="{\"claimToken\":\"${"a".repeat(64)}\",\"phoneToken\":\"${"b".repeat(64)}\"}"
        transport.call("POST","/functions/v1/watch-pairing/sessions/${d.qr().sessionId}/claim",body,"b".repeat(64))
        assertEquals(1,refreshed);assertEquals(2,requests.size)
        for(request in requests) {
            assertEquals("test.invalid",request.url.host);assertTrue(request.url.encodedPath.startsWith("/test/functions/v1/watch-pairing/"))
            assertEquals("Bearer phone-login",request.header("authorization"));assertEquals("anon",request.header("apikey"));assertEquals("b".repeat(64),request.header("x-watchrss-pairing-token"))
            assertFalse(request.url.toString().contains("phone-login"));val buffer=okio.Buffer();request.body!!.writeTo(buffer);assertEquals(body,buffer.readUtf8())
        }
        assertEquals("Bearer old",requests.first().header("x-watchrss-device-authorization"));assertEquals("Bearer new",requests.last().header("x-watchrss-device-authorization"))
        assertThrows(IllegalArgumentException::class.java){runBlocking{transport.call("POST","https://evil.invalid/claim","{}",null)}}
        assertEquals(2,requests.size)
    }
    @Test fun redirectsAreNotFollowedAndCloudAttestationOnlyUpdatesAnExistingSelfRegisteredWatch() = runBlocking {
        var calls=0
        val redirect=OkHttpClient.Builder().addInterceptor { chain ->calls++;Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("redirect")
            .header("location","https://evil.invalid").body("{}".toResponseBody()).build() }.build()
        val relay=PairingRelayHttp(AccountEnvironment(backendBaseUrl="https://test.invalid"),{session},{null},{_,_->false},redirect)
        assertThrows(PairingHttpException::class.java){runBlocking{relay.call("POST","/functions/v1/watch-pairing/sessions/${d.qr().sessionId}/close","{}","a".repeat(64))}}
        assertEquals(1,calls)
        var writes=0
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor {chain->
            writes++;assertEquals(d.epoch,chain.request().header("x-watchrss-library-epoch"))
            val buffer=okio.Buffer();chain.request().body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8())
            assertEquals(setOf("deviceId","platform","publicKeySpki","keyVersion","attestation","attestationKeyVersion"),body.keys().asSequence().toSet())
            assertEquals(d.device,body.getString("deviceId"));assertEquals("watch",body.getString("platform"));assertEquals(64,body.getString("attestation").length)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body("{}".toResponseBody()).build()
        }.build())
        client.useEpoch(session,d.epoch);val watch=d.bootstrap().devices.single();val grant=d.grant()
        client.attestPairingWatch(session,watch,grant.attestation,1,d.epoch)
        assertThrows(IllegalArgumentException::class.java){runBlocking{client.attestPairingWatch(session,watch.copy(watchSelfRegisteredAt=null),grant.attestation,1,d.epoch)}}
        assertEquals(1,writes)
    }
    @Test fun bootstrapPreservesWatchSelfRegistrationConfirmationField() = runBlocking {
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor {chain->
            val body=JSONObject().put("libraryEpoch",d.epoch).put("member",JSONObject()).put("keyEnvelopes",JSONArray())
                .put("devices",JSONArray().put(JSONObject().put("deviceId",d.device).put("platform","watch").put("watchSelfRegisteredAt","confirmed")))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body.toString().toResponseBody()).build()
        }.build())
        assertEquals("confirmed",client.bootstrap(session).devices.single().watchSelfRegisteredAt)
    }
    @Test fun cancellationClosesRealCallWhileWaitingForHeadersOrForStalledBody()=runBlocking {
        for(bodyStarted in listOf(false,true)) {
            val server=ServerSocket(0);val failed=CountDownLatch(1);val received=CountDownLatch(1)
            val accepted=AtomicReference<Socket>();val executor=Executors.newSingleThreadExecutor()
            val peer=executor.submit<Boolean> {
                server.accept().use {socket->
                    accepted.set(socket);socket.soTimeout=2000
                    val input=socket.getInputStream();val header=StringBuilder()
                    while(!header.endsWith("\r\n\r\n")) {val value=input.read();check(value>=0);header.append(value.toChar())}
                    repeat(2){check(input.read()>=0)} // The complete two-byte {} request body.
                    if(bodyStarted) {socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 16\r\n\r\n".toByteArray());socket.getOutputStream().flush()}
                    received.countDown()
                    input.read()==-1 // Cancellation must actually close the socket, not only the coroutine.
                }
            }
            val client=OkHttpClient.Builder().eventListener(object:EventListener(){
                override fun callFailed(call:Call,ioe:java.io.IOException){failed.countDown()}
            }).build()
            try {
                val transport=PairingRelayHttp(AccountEnvironment(backendBaseUrl="http://127.0.0.1:${server.localPort}"),{session},{null},{_,_->false},client)
                val job=async {transport.call("POST","/functions/v1/watch-pairing/sessions/${d.qr().sessionId}/claim","{}","cap",30_000)}
                assertTrue(withContext(Dispatchers.IO){received.await(2,TimeUnit.SECONDS)})
                job.cancel();withTimeout(1000){job.join();while(failed.count>0)delay(10)}
                assertTrue(withContext(Dispatchers.IO){peer.get(1,TimeUnit.SECONDS)});assertTrue(job.isCancelled)
            }finally {accepted.get()?.close();server.close();executor.shutdownNow();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
        }
    }
    @Test fun responsePathsAreBoundedToNegotiatedMaximumAndNeverAllowExtraQueryOrOtherOrigins()=runBlocking {
        var calls=0
        val client=OkHttpClient.Builder().addInterceptor {chain->calls++;Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body("{}".toResponseBody()).build()}.build()
        val transport=PairingRelayHttp(AccountEnvironment(backendBaseUrl="https://test.invalid"),{session},{null},{_,_->false},client)
        val prefix="/functions/v1/watch-pairing/sessions/${d.qr().sessionId}/response?seq="
        for(seq in listOf("0","15","16","99","100","254","255"))transport.call("GET",prefix+seq,null,"cap")
        assertEquals(7,calls)
        for(seq in listOf("256","-1","01","1&other=x","1/extra","1#fragment","9999999")) {
            assertThrows(IllegalArgumentException::class.java){runBlocking{transport.call("GET",prefix+seq,null,"cap")}}
        };assertEquals(7,calls)
    }
}
