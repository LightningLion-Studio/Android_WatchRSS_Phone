package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudMigrationInitiatorTest {
    private fun fixture(name:String)=JSONObject(requireNotNull(javaClass.getResource("/r48/$name.json")).readText())
    private class MemoryStore:CloudMigrationStore {
        val values=mutableMapOf<String,String>()
        var reject:(JSONObject)->Boolean={false}
        override fun load(scope:String,planId:String)=values["$scope/$planId"]?.let{JSONObject(it)}
        override fun save(scope:String,planId:String,value:JSONObject) {
            check(!reject(value)) { "migration_checkpoint_failed" };values["$scope/$planId"]=value.toString()
        }
        override fun remove(scope:String,planId:String) {values.remove("$scope/$planId")}
    }
    private inner class Fixture {
        val vector=fixture("r48-plan-grant")
        val certVector=fixture("r48-worker-cert")
        val receiptVector=fixture("r48-receipt")
        val originalPlan=vector.getJSONObject("plan")
        val source=CloudObjectDescriptor.fromJson(vector.getJSONObject("grantPlaintext").getJSONArray("inputObjects").getJSONObject(0).getJSONObject("descriptor"))
        val semantic=vector.getJSONObject("grantPlaintext").getJSONArray("invariants").getJSONObject(0).getString("semanticSha256")
        val input=CloudMigrationInput(originalPlan.getJSONObject("inputScope").getJSONArray("snapshots").getJSONObject(0).getString("snapshotId"),originalPlan.getJSONObject("inputScope").getJSONArray("snapshots").getJSONObject(0).getString("manifestSha256"),source,semantic,"private-article-body",setOf("article-txt"),true)
        var current=CloudMigrationContext("fixture-scope",originalPlan.getString("userId"),originalPlan.getString("installationId"),originalPlan.getString("libraryEpoch"),originalPlan.getString("contentGeneration"))
        var now=originalPlan.getLong("createdAt")+1
        val draft=CloudMigrationPlanBuilder.create(current.userId,current.installationId,current.epoch,current.generation,originalPlan.getJSONObject("transform"),listOf(input),originalPlan.getLong("createdAt"),planId=originalPlan.getString("planId"))
        val planId=draft.plan.getString("planId")
        val jobId=vector.getJSONObject("grantPlaintext").getString("jobId")
        val store=MemoryStore()
        val bodies=mutableListOf<Pair<String,JSONObject>>()
        val pin=CloudMigrationCrypto.b64(certVector.getJSONObject("pins").getString("current"),32)
        val trust=CloudWorkerTrust(pin,CloudMigrationCrypto.b64(certVector.getJSONObject("pins").getString("next"),32),emptySet(),setOf(certVector.getJSONObject("certificate").getString("workerBuildSha256")),setOf(CloudMigrationCrypto.transformKey(originalPlan.getJSONObject("transform"))))
        var state="planned"
        var loseGrantResponse=false
        var disabled=false
        var clearDuringAuthorize=false
        var revision=0L
        var authorizedAad:JSONObject?=null
        var result:JSONObject?=null
        val resultBlobs=mutableMapOf<String,ByteArray>()
        var published:EncryptedCloudSnapshot?=null
        var serverProof:JSONObject?=null
        var loseCompleteResponse=false
        var loseAcceptResponse=false
        var cancelBeforeComplete=false
        var cancelConflicts=0
        var blockReceiptDownload=false
        val downloadStarted=CompletableDeferred<Unit>()
        val releaseDownload=CompletableDeferred<Unit>()
        var publishCalls=0
        var retryCalls=0
        var obsoleteRetry=false
        var obsoletePublish=false
        var failBeforeComplete=false
        val events=mutableListOf<CloudMigrationEvent>()
        fun runSyntheticWorker(body:JSONObject) {
            val sealed=body.getJSONObject("sealedGrant")
            val sk=org.bouncycastle.crypto.params.X25519PrivateKeyParameters(CloudMigrationCrypto.hex(vector.getString("workerPrivateKeyHex")),0)
            val receiver=org.bouncycastle.crypto.AsymmetricCipherKeyPair(sk.generatePublicKey(),sk)
            val hpke=org.bouncycastle.crypto.hpke.HPKE(0,32,1,2)
            val aad=requireNotNull(authorizedAad)
            val grantId=java.util.UUID.fromString(aad.getString("grantId"))
            val info="watchrss/r48/grant/v1\n".toByteArray()+CloudMigrationCrypto.hex(draft.planHash)+java.nio.ByteBuffer.allocate(16).putLong(grantId.mostSignificantBits).putLong(grantId.leastSignificantBits).array()+aad.getString("sessionId").toByteArray()
            val context=hpke.setupBaseR(java.util.Base64.getDecoder().decode(sealed.getString("encBase64")),receiver,info)
            val grant=CloudRawDocument.parse(context.open(CloudMigrationJcs.bytes(aad),java.util.Base64.getDecoder().decode(sealed.getString("ciphertextBase64")))).json()
            val root=CloudMigrationCrypto.b64(grant.getString("outputRootBase64"),32)
            val plain=CloudMigrationCrypto.hex(fixture("r48-object-key-envelope").getString("outputPlaintextHex"))
            assertEquals(semantic,CloudSnapshotCodec.sha256(plain))
            val snapshot=CloudSnapshotCodec().create(root,1,"worker",1,listOf(CloudLogicalObject(source.name,plain,false)))
            snapshot.newCiphertextChunks.forEach{(hash,bytes)->resultBlobs["fixture://$hash"]=bytes}
            val receipt=JSONObject().put("format","watchrss-r48-receipt").put("version",1).put("planId",planId).put("planHash",draft.planHash).put("jobId",jobId).put("transform",draft.plan.getJSONObject("transform"))
                .put("objects",JSONArray().put(JSONObject().put("inputDescriptorSha256",input.descriptorSha256).put("semanticSha256",semantic).put("outputSchema","r48.body-output.v1").put("outputDescriptor",snapshot.manifest.objects.single().toJson()))).put("failed",JSONArray())
            val salt=ByteArray(16).also(java.security.SecureRandom()::nextBytes);val nonce=ByteArray(12).also(java.security.SecureRandom()::nextBytes)
            val key=CloudSnapshotCodec.hkdfSha256(root,salt,"watchrss/r48/receipt/v1".toByteArray()+CloudMigrationCrypto.hex(draft.planHash),32)
            val receiptAad=JSONObject().put("v",1).put("planId",planId).put("planHash",draft.planHash).put("jobId",jobId).put("contentGeneration",current.generation)
            val encrypted=CloudSnapshotCodec.encryptAesGcm(key,nonce,CloudMigrationJcs.bytes(receipt),CloudMigrationJcs.bytes(receiptAad))
            val blob=salt+nonce+encrypted;val hash=CloudSnapshotCodec.sha256(blob);resultBlobs["fixture://receipt"]=blob
            result=JSONObject().put("planHash",draft.planHash).put("receipt",JSONObject().put("version",1).put("sha256",hash).put("sizeBytes",blob.size).put("downloadRef","fixture://receipt")).put("candidateExpiresAt",originalPlan.getLong("createdAt")+1_209_600_000).put("candidateChunkCount",snapshot.newCiphertextChunks.size)
            root.fill(0);key.fill(0)
        }
        val api=object:CloudMigrationApi {
            override suspend fun capabilities()=JSONObject().put("phaseA",JSONObject().put("cloudFormatR48WriteV1",!disabled)).put("phaseB",JSONObject().put("cloudFormatMigrationR48V1",!disabled))
                .put("workerSession",JSONObject().put("certificate",certVector.getJSONObject("certificate")).put("signatureBase64",certVector.getString("signatureBase64")).put("signingKeyId",CloudSnapshotCodec.sha256(pin)))
            override suspend fun post(path:String,body:JSONObject):JSONObject {
                bodies+=path to JSONObject(body.toString())
                if(path.endsWith("/plans"))return JSONObject().put("planId",planId).put("planHash",draft.planHash).put("state","planned")
                if(path.endsWith("/authorize")) {
                    if(clearDuringAuthorize)current=current.copy(generation="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                    val expires=minOf(draft.plan.getLong("expiresAt"),certVector.getJSONObject("certificate").getLong("expiresAt"),now+21_600_000)
                    val aad=JSONObject().put("userId",current.userId).put("installationId",current.installationId).put("planId",planId).put("planHash",draft.planHash)
                        .put("grantId",body.getString("grantId")).put("sessionId",body.getString("sessionId")).put("expiresAt",expires)
                    authorizedAad=aad
                    return JSONObject().put("jobId",jobId).put("grantId",body.getString("grantId")).put("acceptedAt",now).put("expiresAt",expires).put("aad",aad)
                }
                if(path.contains("/grants/")) {
                    state="granted";runSyntheticWorker(body);if(loseGrantResponse) {loseGrantResponse=false;throw IOException("synthetic lost response")}
                    return JSONObject().put("jobId",jobId).put("planHash",draft.planHash).put("state","granted").put("revision",++revision)
                }
                if(path.endsWith("/accept")) {
                    assertNotNull(serverProof);assertEquals(serverProof!!.getString("snapshotId"),body.getString("snapshotId"))
                    state="accepted";if(loseAcceptResponse){loseAcceptResponse=false;throw IOException("synthetic accept loss")}
                    return JSONObject().put("state","accepted")
                }
                if(path.endsWith("/cancel")) {if(cancelConflicts-->0)throw CloudHttpException(409,"{\"error\":\"migration_plan_conflict\"}");state="cancelled";return JSONObject().put("state",state).put("publishedBatchCount",0)}
                error("unexpected $path")
            }
            override suspend fun get(path:String):JSONObject {
                if(path.endsWith("/result"))return requireNotNull(result)
                return JSONObject().put("jobId",jobId).put("planId",planId).put("planHash",draft.planHash).put("state",state).put("revision",++revision).put("publishedBatchCount",if(serverProof==null)0 else 1)
            }
            override suspend fun publicationProof(jobId:String,batchId:String):JSONObject?=serverProof
            override suspend fun candidateDownload(jobId:String,sha256:String):JSONObject=JSONObject().put("downloadRef","fixture://$sha256").put("jobId",jobId).put("planHash",draft.planHash).put("sha256",sha256).put("sizeBytes",resultBlobs.getValue("fixture://$sha256").size)
            override suspend fun download(url:String,size:Long,sha256:String):ByteArray {
                if(blockReceiptDownload && url=="fixture://receipt"){downloadStarted.complete(Unit);releaseDownload.await()}
                return resultBlobs.getValue(url).also{require(it.size.toLong()==size && CloudSnapshotCodec.sha256(it)==sha256)}
            }
        }
        fun client(policy:CloudFormatWritePolicy=CloudFormatWritePolicy(true,true))=CloudMigrationInitiator({current},api,store,trust,{CloudMigrationCrypto.hex("00".repeat(32))},object:CloudMigrationPublisher {
            override suspend fun retryPublication(draft:CloudMigrationDraft,intent:JSONObject) { retryCalls++;if(obsoleteRetry)throw IllegalStateException("migration_publication_obsolete");require(state!="cancelled");serverProof=intent }
            override suspend fun publish(draft:CloudMigrationDraft,jobId:String,receiptHash:String,batchId:String,outputs:List<CloudMigrationOutput>,root:ByteArray,validation:JSONObject,beforeComplete:(String,JSONObject)->Unit):String? {
                publishCalls++
                if(obsoletePublish)throw IllegalStateException("migration_publication_obsolete")
                val base=CloudSnapshotManifest("11111111-1111-4111-8111-111111111111","phone",1,1,1,emptyMap(),emptyMap(),listOf(source))
                val rebased=CloudMigrationResult.rebase(base,outputs,ByteArray(32),root,planId)
                published=CloudSnapshotCodec().create(ByteArray(32),1,"phone",2,emptyList(),carryForwardObjects=rebased)
                val id=published!!.manifest.snapshotId
                val migration=JSONObject().put("jobId",jobId).put("planHash",draft.planHash).put("batchId",batchId).put("receiptSha256",receiptHash).put("validation",validation)
                beforeComplete(id,migration)
                if(cancelBeforeComplete){state="cancelled";throw IllegalStateException("migration_cancelled")}
                if(failBeforeComplete)throw IOException("synthetic pre-complete loss")
                serverProof=JSONObject().put("batchId",batchId).put("snapshotId",id).put("receiptSha256",receiptHash).put("validation",validation)
                if(loseCompleteResponse){loseCompleteResponse=false;throw IOException("synthetic complete loss")}
                return id
            }
        },policy,now={now},observer=CloudMigrationObserver { events+=it })
    }
    @Test fun explicitConsentAuthorizesThenSealsAndPersistsNoInputKeyMaterial()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft)
        assertNull(client.consent(f.planId,false));assertEquals(1,f.bodies.size)
        client.consent(f.planId,true)
        assertTrue(f.bodies[1].first.endsWith("/grants/authorize"));assertTrue(f.bodies[2].second.has("sealedGrant"))
        val persisted=f.store.values.values.single()
        for(key in listOf("keyBase64","sealedGrant","chunkKeys","accountKey","bearer"))assertFalse(key,persisted.contains("\"$key\""))
        assertTrue(persisted.contains("outputRootBase64"));assertEquals("granted",client.state.value.state)
        f.client().refresh(f.planId);assertEquals("granted",f.store.load(f.current.scope,f.planId)!!.getString("state"))
    }
    @Test fun grantRetryUsesExactlySameEphemeralCiphertextAndIds()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);f.loseGrantResponse=true
        try{client.consent(f.planId,true);fail()}catch(_:IOException){}
        client.consent(f.planId,true)
        val sealed=f.bodies.filter{it.second.has("sealedGrant")}.map{it.second.toString()}
        assertEquals(2,sealed.size);assertEquals(sealed[0],sealed[1])
    }
    @Test fun processDeathAfterDeliveredGrantQueriesStatusAndNeverResealsAUsedGrant()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);f.loseGrantResponse=true
        try{client.consent(f.planId,true);fail()}catch(_:IOException){}
        val count=f.bodies.size
        val restarted=f.client();restarted.consent(f.planId,true)
        assertEquals(count,f.bodies.size);assertEquals("granted",restarted.state.value.state)
    }
    @Test fun generationChangeAfterAuthorizeCannotSendInputKeys()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);f.clearDuringAuthorize=true
        assertTrue(runCatching{client.consent(f.planId,true)}.isFailure)
        assertTrue(f.bodies.none{it.second.has("sealedGrant")})
    }
    @Test fun serverKillSwitchStopsNewMaterialButDoesNotPreventCancel()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.disabled=true
        client.cancel(f.planId);assertEquals("cancelled",client.state.value.state)
        val second=Fixture();second.disabled=true
        assertTrue(runCatching{second.client().create(second.draft)}.isFailure);assertTrue(second.bodies.isEmpty())
    }
    @Test fun recordsAndNoIndependentPlaintextAreRejectedBeforePlanCreation() {
        val f=Fixture()
        for(input in listOf(CloudMigrationInput(f.input.snapshotId,f.input.manifestSha256,f.source,f.semantic,"record-v1",setOf("records"),true),CloudMigrationInput(f.input.snapshotId,f.input.manifestSha256,f.source,f.semantic,"private-article-body",setOf("article-txt"),false))) {
            assertThrows(Exception::class.java){CloudMigrationPlanBuilder.create(f.current.userId,f.current.installationId,f.current.epoch,f.current.generation,f.originalPlan.getJSONObject("transform"),listOf(input),f.now)}
        }
    }
    @Test fun resultSchemaSemanticBindingSamplingAndRebaseRejectWorkerPrivileges() {
        val f=Fixture();val receipt=JSONObject(f.receiptVector.getJSONObject("plaintext").toString()).put("planHash",f.draft.planHash)
        val outputs=CloudMigrationResult.validate(receipt,f.draft,f.jobId);assertEquals(1,outputs.size)
        val source=f.source.copy(rawDocument=CloudRawDocument.parse(f.source.toRawString()).patch(listOf("compat"),"{ \"wide\":9007199254740993 }"))
        val manifest=CloudSnapshotManifest("11111111-1111-4111-8111-111111111111","phone",1,1,1,emptyMap(),emptyMap(),listOf(f.source))
        val root=CloudMigrationCrypto.hex(f.receiptVector.getString("outputRootHex"))
        assertNotNull(CloudMigrationResult.rebase(manifest,outputs,ByteArray(32),root,f.planId).single().objectKeyEnvelope)
        assertEquals(source.toRawString(),CloudMigrationResult.rebase(manifest.copy(objects=listOf(source)),outputs,ByteArray(32),root,f.planId).single().toRawString())
        val invalid=fixture("r48-result-validation").getJSONArray("invalidOutputDescriptors")
        for(i in 0 until invalid.length())assertThrows(Exception::class.java){CloudMigrationResult.strictDescriptor(invalid.getJSONObject(i).getJSONObject("value"),f.source.name)}
        receipt.getJSONArray("objects").getJSONObject(0).put("semanticSha256","00".repeat(32))
        assertThrows(Exception::class.java){CloudMigrationResult.validate(receipt,f.draft,f.jobId)}
        for(n in listOf(0,1,7,8,9,100,2000)) {val permutation=CloudMigrationResult.permutation(n);assertEquals((0 until n).toSet(),permutation.toSet());assertEquals(n,permutation.size);assertTrue(CloudMigrationResult.sampleCount(n)<=n)}
    }
    @Test fun actualHpkeGrantReceiptValidationAndOrdinaryAcceptedHeadAreReadableWithoutWriteGate()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.state="awaiting_validation"
        // Plan/grant expiry does not remove the independent candidate validation window.
        f.now=f.originalPlan.getLong("expiresAt")+1
        assertTrue(client.validateAndPublish(f.planId,CloudMigrationRunBudget{0}))
        assertEquals("accepted",client.state.value.state)
        val published=requireNotNull(f.published)
        val decoded=CloudSnapshotCodec().decryptManifest(ByteArray(32),published.manifest.snapshotId,published.encryptedManifest)
        val body=CloudSnapshotCodec().restoreObjects(ByteArray(32),decoded){hash->f.resultBlobs.getValue("fixture://$hash")}.getValue(f.source.name)
        assertEquals(f.semantic,CloudSnapshotCodec.sha256(body));assertEquals(1,f.publishCalls)
    }
    @Test fun completeResponseLossReconcilesDurableProofAfterRestartWithoutPublishingAgain()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.state="awaiting_validation";f.loseCompleteResponse=true
        try{client.validateAndPublish(f.planId,CloudMigrationRunBudget{0});fail()}catch(_:IOException){}
        assertTrue(f.client().validateAndPublish(f.planId,CloudMigrationRunBudget{0}))
        assertEquals(1,f.publishCalls);assertEquals(0,f.retryCalls)
    }
    @Test fun cancellationWinsBeforeCompleteButDoesNotRollBackAnAlreadyPublishedProof()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.state="awaiting_validation";f.cancelBeforeComplete=true
        assertTrue(runCatching{client.validateAndPublish(f.planId,CloudMigrationRunBudget{0})}.isFailure)
        assertNull(f.serverProof)
        val g=Fixture();val other=g.client();other.create(g.draft);other.consent(g.planId,true);g.state="awaiting_validation";g.loseCompleteResponse=true
        try{other.validateAndPublish(g.planId,CloudMigrationRunBudget{0});fail()}catch(_:IOException){}
        g.state="cancelled";val before=g.bodies.count{it.first.endsWith("/accept")}
        assertTrue(g.client().validateAndPublish(g.planId,CloudMigrationRunBudget{0}))
        assertEquals(before,g.bodies.count{it.first.endsWith("/accept")});assertNotNull(g.serverProof)
    }
    @Test fun cancelDoesNotWaitBehindAStalledValidationDownload()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.state="awaiting_validation";f.blockReceiptDownload=true
        val validating=async{runCatching{client.validateAndPublish(f.planId,CloudMigrationRunBudget{0})}}
        withTimeout(1000){f.downloadStarted.await()}
        withTimeout(500){client.cancel(f.planId)}
        f.releaseDownload.complete(Unit)
        assertTrue(validating.await().isFailure);assertEquals(0,f.publishCalls);assertEquals("cancelled",client.state.value.state)
    }
    @Test fun diskFullBeforeRootCheckpointCannotSendGrantAndRetryPreservesIntent()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft)
        f.store.reject={it.has("outputRootBase64")}
        assertTrue(runCatching{client.consent(f.planId,true)}.isFailure)
        assertEquals(0,f.bodies.count{it.first.contains("/grants/") && !it.first.endsWith("/authorize")})
        assertEquals("checkpoint",client.state.value.errorCode);assertTrue(client.state.value.retryable)
        f.store.reject={false};client.consent(f.planId,true)
        assertEquals("granted",client.state.value.state);assertNull(client.state.value.errorCode)
    }
    @Test fun lostLocalCheckpointAfterDeliveredGrantRecoversWithoutSendingAnotherGrant()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft)
        f.store.reject={it.optString("state")=="granted"}
        assertTrue(runCatching{client.consent(f.planId,true)}.isFailure)
        assertEquals(1,f.bodies.count{it.first.contains("/grants/") && !it.first.endsWith("/authorize")})
        f.store.reject={false};f.client().consent(f.planId,true)
        assertEquals(1,f.bodies.count{it.first.contains("/grants/") && !it.first.endsWith("/authorize")})
        assertEquals("granted",f.store.load(f.current.scope,f.planId)!!.getString("state"))
    }
    @Test fun diskFullBeforeCompleteKeepsIntentUnpublishedAndCanRetryValidation()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.state="awaiting_validation"
        f.store.reject={it.has("publicationIntent")}
        assertTrue(runCatching{client.validateAndPublish(f.planId,CloudMigrationRunBudget{0})}.isFailure)
        assertNull(f.serverProof);assertEquals(0,f.bodies.count{it.first.endsWith("/accept")})
        f.store.reject={false};assertTrue(f.client().validateAndPublish(f.planId,CloudMigrationRunBudget{0}))
        assertEquals("accepted",f.state)
    }
    @Test fun cancellationRefreshesWorkerProgressRevisionWithAFiniteRetryBudget()=runBlocking {
        for(conflicts in listOf(2,5)) {
            val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.cancelConflicts=conflicts
            val result=runCatching{client.cancel(f.planId)}
            val attempts=f.bodies.filter{it.first.endsWith("/cancel")}
            assertEquals(3,attempts.size);assertEquals(1,attempts.map{it.second.getString("requestId")}.distinct().size)
            assertEquals(3,attempts.map{it.second.getLong("expectedRevision")}.distinct().size)
            if(conflicts==2)assertEquals("cancelled",result.getOrThrow().getString("state"))
            else {assertTrue(result.isFailure);assertEquals("conflict",client.state.value.errorCode);assertTrue(client.state.value.retryable)}
            assertEquals(0,f.publishCalls)
        }
    }
    @Test fun registeredIdentityWorkerDoesNotReceiveUnknownOrBusinessDescriptorFields() {
        val f=Fixture()
        for(extra in listOf("privateBook","future")) {
            val raw=CloudRawDocument.parse(f.source.toRawString()).patch(listOf(extra),"{\"value\":1}")
            val input=CloudMigrationInput(f.input.snapshotId,f.input.manifestSha256,CloudObjectDescriptor.fromRaw(raw.source),f.input.semanticSha256,f.input.type,f.input.categories,true)
            val error=runCatching {CloudMigrationPlanBuilder.create(f.current.userId,f.current.installationId,f.current.epoch,f.current.generation,f.originalPlan.getJSONObject("transform"),listOf(input),f.now)}.exceptionOrNull()
            assertNotNull(error);assertEquals("migration_not_suitable",error!!.message)
        }
    }
    @Test fun terminalStatusAfterRestartErasesOnlyOutputValidationRoot()=runBlocking {
        for(terminal in listOf("cancelled","expired","budget_exhausted","failed","accepted")) {
            val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true)
            assertTrue(f.store.values.values.single().contains("outputRootBase64"))
            f.state=terminal;f.client().refresh(f.planId)
            val saved=JSONObject(f.store.values.values.single())
            assertFalse(saved.has("outputRootBase64"));assertTrue(saved.has("inputs"));assertTrue(saved.has("planHash"))
            assertEquals(terminal,saved.getString("state"))
        }
    }
    @Test fun ciphertextCorruptionAndCandidateExpiryNeverReachPublisher()=runBlocking {
        for(corrupt in listOf(true,false)) {
            val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.state="awaiting_validation"
            if(corrupt)f.resultBlobs.getValue("fixture://receipt")[40]=0 else f.now=f.originalPlan.getLong("createdAt")+1_209_600_000
            assertTrue(runCatching{client.validateAndPublish(f.planId,CloudMigrationRunBudget{0})}.isFailure)
            assertEquals(0,f.publishCalls)
        }
    }
    @Test fun clientBudgetIsCumulativeAndDoesNotAllowOneAtomicOversizeOperation() {
        var clock=0L;val budget=CloudMigrationRunBudget{clock}
        budget.reserve(8_388_607);budget.reserve(1)
        assertThrows(Exception::class.java){budget.reserve(1)}
        clock=30_000;assertThrows(Exception::class.java){budget.checkpoint()}
        assertThrows(Exception::class.java){CloudMigrationRunBudget{0}.reserve(8_388_609)}
    }
    @Test fun reviewP1ObsoleteIntentIsDiscardedOnceAndTerminalAfterRestart()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.state="awaiting_validation"
        f.failBeforeComplete=true
        assertTrue(runCatching{client.validateAndPublish(f.planId,CloudMigrationRunBudget{0})}.isFailure)
        assertTrue(f.store.load(f.current.scope,f.planId)!!.has("publicationIntent"))
        f.obsoleteRetry=true
        val resumed=f.client();assertTrue(resumed.validateAndPublish(f.planId,CloudMigrationRunBudget{0}))
        val local=f.store.load(f.current.scope,f.planId)!!
        assertEquals("superseded",local.getString("state"));assertFalse(local.has("publicationIntent"));assertFalse(local.has("outputRootBase64"))
        assertEquals("publication_obsolete",resumed.state.value.errorCode);assertFalse(resumed.state.value.retryable)
        val restarted=f.client();assertEquals("superseded",restarted.refresh(f.planId).getString("state"))
        assertTrue(restarted.validateAndPublish(f.planId,CloudMigrationRunBudget{0}));assertEquals(1,f.retryCalls)
        assertNull(f.serverProof);assertTrue(f.bodies.none{it.first.endsWith("/accept")})
    }

    @Test fun reviewP1DisappearedMigrationTargetIsTerminalWithoutPublication()=runBlocking {
        val f=Fixture();val client=f.client();client.create(f.draft);client.consent(f.planId,true);f.state="awaiting_validation";f.obsoletePublish=true
        assertTrue(client.validateAndPublish(f.planId,CloudMigrationRunBudget{0}))
        assertEquals("superseded",client.state.value.state)
        assertTrue(f.client().validateAndPublish(f.planId,CloudMigrationRunBudget{0}));assertEquals(1,f.publishCalls)
        assertNull(f.serverProof);assertTrue(f.bodies.none{it.first.endsWith("/accept")})
    }

}
