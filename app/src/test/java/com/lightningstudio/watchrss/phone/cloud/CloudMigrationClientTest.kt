package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudMigrationClientTest {
    private val session=PhoneAccountSession("synthetic-owner","***","access","refresh",Long.MAX_VALUE)
    private val epoch="11111111-1111-4111-8111-111111111111"
    private val generation="22222222-2222-4222-8222-222222222222"
    @Test fun migrationControlWritesUseTheSameIdentityEpochAndGenerationFences()=runBlocking {
        val requests=mutableListOf<Request>()
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://synthetic.invalid"),http=OkHttpClient.Builder().addInterceptor{chain ->
            val request=chain.request();requests+=request
            Response.Builder().request(request).code(200).message("fixture").protocol(Protocol.HTTP_1_1).body("{}".toResponseBody()).build()
        }.build())
        client.currentDeviceId="synthetic-phone";client.useEpoch(session,epoch);client.useContentGeneration(session,generation)
        var valid=true
        val api=PhoneCloudMigrationApi(client,{session},{check(valid)})
        api.post("/cloud/format-migrations/plans",JSONObject().put("v",1))
        assertEquals(epoch,requests.single().header("x-watchrss-library-epoch"))
        assertEquals(generation,requests.single().header("x-watchrss-content-generation"))
        assertEquals("synthetic-phone",requests.single().header("x-watchrss-cloud-device-id"))
        valid=false
        assertTrue(runCatching{api.post("/cloud/format-migrations/plans",JSONObject())}.isFailure)
        assertEquals(1,requests.size)
        assertTrue(isCloudContentWrite("POST","/functions/v1/cloud/format-migrations/11111111-1111-4111-8111-111111111111/cancel"))
    }
    @Test fun migrationResponsesAreBoundedEvenWhenServerIgnoresAdvertisedLength()=runBlocking {
        val requests=java.util.concurrent.atomic.AtomicInteger()
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://synthetic.invalid"),http=OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).code(200).message("fixture").protocol(Protocol.HTTP_1_1).body(ByteArray(65536).toResponseBody()).build()
        }.build(),downloadBackoff={})
        val api=PhoneCloudMigrationApi(client,{session},{})
        assertTrue(runCatching{api.download("https://synthetic.invalid/candidate",4,"00".repeat(32))}.isFailure)
        assertTrue(requests.get() in 1..4)
        var consumed=0;var closed=false
        val stream=object:java.io.InputStream() {
            override fun read():Int {consumed++;return 7}
            override fun close(){closed=true}
        }
        assertThrows(CloudDownloadIntegrityException::class.java){readMigrationBody(stream,4)}
        assertEquals(5,consumed);assertTrue(closed)
        assertArrayEquals(byteArrayOf(1,2),readMigrationBody(byteArrayOf(1,2).inputStream(),2))
    }
    @Test fun internalBodyRetriesCannotBypassTheCumulativeMigrationByteBudget()=runBlocking {
        val requests=java.util.concurrent.atomic.AtomicInteger()
        val size=4_194_304L
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://synthetic.invalid"),http=OkHttpClient.Builder().addInterceptor {chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).code(200).message("fixture").protocol(Protocol.HTTP_1_1)
                .body(ByteArray(size.toInt()).toResponseBody()).build()
        }.build(),downloadBackoff={})
        val api=PhoneCloudMigrationApi(client,{session},{})
        val budget=CloudMigrationRunBudget{0};budget.reserve(size)
        val failure=runCatching{budget.within{api.download("https://synthetic.invalid/candidate",size,"00".repeat(32))}}.exceptionOrNull()
        assertNotNull(failure);assertEquals("migration_client_run_budget",failure!!.message)
        assertEquals(2,requests.get());assertEquals(8_388_608L,budget.bytes)
    }
    @Test fun realHttpStallCannotOutliveTheRemainingMigrationRunBudget()=runBlocking {
        for(control in listOf(false,true)) {
            val server=okhttp3.mockwebserver.MockWebServer()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));server.start()
            try {
                val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl=server.url("/").toString().trimEnd('/')),downloadBackoff={})
                client.useEpoch(session,epoch);client.useContentGeneration(session,generation)
                val api=PhoneCloudMigrationApi(client,{session},{})
                val budget=CloudMigrationRunBudget(activeMillis=250,now={System.nanoTime()/1_000_000})
                val start=System.nanoTime()
                val result=runCatching { budget.within {
                    if(control)api.get("/cloud/format-migrations/33333333-3333-4333-8333-333333333333")
                    else api.download(server.url("/candidate").toString(),4,"00".repeat(32))
                } }
                assertTrue(result.isFailure)
                assertTrue("stalled HTTP exceeded deadline",(System.nanoTime()-start)/1_000_000<2_000)
                assertEquals(1,server.requestCount)
            } finally {server.shutdown()}
        }
    }
    @Test fun candidateAndPublicationRoutesAreAuthenticatedAndOnlyMissingBatchMapsToNull()=runBlocking {
        val job="33333333-3333-4333-8333-333333333333";val batch="44444444-4444-4444-8444-444444444444";val sha="ab".repeat(32)
        var missing="migration_publication_not_found";val paths=mutableListOf<String>()
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://synthetic.invalid"),http=OkHttpClient.Builder().addInterceptor {chain ->
            val req=chain.request();paths+=req.url.encodedPath;assertNotNull(req.header("authorization"));assertNull(req.header("x-watchrss-r48-write-v1"))
            assertEquals(epoch,req.header("x-watchrss-library-epoch"));assertEquals(generation,req.header("x-watchrss-content-generation"))
            val candidate=req.url.encodedPath.contains("/chunks/")
            val body=if(candidate)JSONObject().put("jobId",job).put("planHash","12".repeat(32)).put("sha256",sha).put("sizeBytes",3).put("downloadRef","https://synthetic.invalid/blob").put("expiresAt",1) else JSONObject().put("error",missing)
            Response.Builder().request(req).code(if(candidate)200 else 404).message("fixture").protocol(Protocol.HTTP_1_1).body(body.toString().toResponseBody()).build()
        }.build())
        client.useEpoch(session,epoch);client.useContentGeneration(session,generation)
        val api=PhoneCloudMigrationApi(client,{session},{})
        assertEquals(sha,api.candidateDownload(job,sha).getString("sha256"));assertNull(api.publicationProof(job,batch))
        missing="migration_not_found";assertTrue(runCatching{api.publicationProof(job,batch)}.isFailure)
        assertEquals("/functions/v1/cloud/format-migrations/$job/chunks/$sha",paths.first())
    }
    @Test fun currentAdmissionCoversUploadRefreshPutCompleteAndRecordsAndClosesOnKill()=runBlocking {
        val base="https://synthetic.invalid";val rid="55555555-5555-4555-8555-555555555555"
        val snapshot=CloudSnapshotCodec().create(ByteArray(32),1,"phone",1,emptyList())
        val target=JSONObject().put("kind","manifest").put("sha256",CloudSnapshotCodec.sha256(snapshot.encryptedManifest)).put("sizeBytes",snapshot.encryptedManifest.size)
            .put("objectPath","synthetic/manifest").put("reservationId",rid).put("uploadUrl","$base/functions/v1/cloud/uploads/$rid").put("transport","epoch-proxy").put("uploadMethod","PUT")
        var enabled=true;val writes=mutableListOf<String>()
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl=base),http=OkHttpClient.Builder().addInterceptor {chain ->
            val req=chain.request();val path=req.url.encodedPath
            val body=when {
                path.endsWith("/format-capabilities") -> {assertNull(req.header("x-watchrss-r48-write-v1"));JSONObject().put("protocol","watchrss-content-evolution").put("version",1).put("phaseA",JSONObject().put("cloudFormatR48WriteV1",enabled))}
                req.method=="GET" -> {assertNull(req.header("x-watchrss-r48-write-v1"));JSONObject()}
                else -> {
                    assertEquals("1",req.header("x-watchrss-r48-write-v1"));assertEquals(epoch,req.header("x-watchrss-library-epoch"));assertEquals(generation,req.header("x-watchrss-content-generation"));writes+="${req.method} $path"
                    when {
                        path.endsWith("/reserve") -> JSONObject().put("snapshotId",snapshot.manifest.snapshotId).put("reservedBytes",snapshot.encryptedManifest.size).put("missingObjects",org.json.JSONArray().put(target))
                        path.endsWith("/refresh") -> JSONObject(target.toString()).put("alreadyUploaded",false)
                        else -> JSONObject()
                    }
                }
            }
            Response.Builder().request(req).code(200).message("fixture").protocol(Protocol.HTTP_1_1).body(body.toString().toResponseBody()).build()
        }.build())
        client.useEpoch(session,epoch);client.useContentGeneration(session,generation);client.allowR48Writes={true}
        val reserve=client.reserveSnapshot(session,snapshot,30,epoch,JSONObject())
        assertTrue(reserve.missingObjects.single().requiresR48Write)
        val fresh=client.refreshUpload(session,reserve.missingObjects.single(),epoch);assertTrue(fresh.requiresR48Write)
        client.uploadCiphertext(session,fresh,snapshot.encryptedManifest,epoch);client.completeSnapshot(session,snapshot,epoch,JSONObject())
        client.postRecords(session,org.json.JSONArray().put(JSONObject().put("recordId","synthetic")),epoch)
        assertEquals(5,writes.size)
        enabled=false
        val blocked=runCatching{client.refreshUpload(session,fresh,epoch)}.exceptionOrNull()
        assertTrue(blocked is CloudHttpException);assertEquals(503,(blocked as CloudHttpException).status)
        assertEquals(5,writes.size)
        PhoneCloudMigrationApi(client,{session},{}).get("/cloud/format-migrations/33333333-3333-4333-8333-333333333333")
        assertEquals(5,writes.size)
    }
    @Test fun ordinaryReserveAndCompleteKeepTheExactMigrationBinding()=runBlocking {
        val bodies=mutableListOf<Pair<String,JSONObject>>()
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://synthetic.invalid"),http=OkHttpClient.Builder().addInterceptor{chain ->
            val request=chain.request()
            if(request.url.encodedPath.endsWith("/format-capabilities"))return@addInterceptor Response.Builder().request(request).code(200).message("fixture").protocol(Protocol.HTTP_1_1).body(JSONObject().put("protocol","watchrss-content-evolution").put("version",1).put("phaseA",JSONObject().put("cloudFormatR48WriteV1",true)).toString().toResponseBody()).build()
            assertEquals("1",request.header("x-watchrss-r48-write-v1"))
            val buffer=okio.Buffer();request.body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8());bodies+=request.url.encodedPath to body
            val reply=if(request.url.encodedPath.endsWith("/reserve"))JSONObject().put("snapshotId",body.getString("snapshotId")).put("reservedBytes",0).put("missingObjects",org.json.JSONArray())else JSONObject()
            Response.Builder().request(request).code(200).message("fixture").protocol(Protocol.HTTP_1_1).body(reply.toString().toResponseBody()).build()
        }.build())
        client.useEpoch(session,epoch);client.useContentGeneration(session,generation);client.allowR48Writes={true}
        val snapshot=CloudSnapshotCodec().create(ByteArray(32),1,"synthetic-phone",1,emptyList())
        val binding=JSONObject().put("jobId","33333333-3333-4333-8333-333333333333").put("planHash","00".repeat(32)).put("batchId","44444444-4444-4444-8444-444444444444").put("receiptSha256","11".repeat(32)).put("outputCiphertextSha256s",org.json.JSONArray().put("22".repeat(32))).put("validation",JSONObject().put("mode","full").put("verifiedObjects",1).put("totalObjects",1))
        client.reserveSnapshot(session,snapshot,null,epoch,binding)
        client.completeSnapshot(session,snapshot,epoch,binding)
        assertEquals(2,bodies.size)
        bodies.forEach{(_,body)->assertEquals(binding.toString(),body.getJSONObject("migrationContext").toString())}
    }
}
