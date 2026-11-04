package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Ordinary account/device/epoch/generation identity; no secrets. */
data class CloudMigrationContext(val scope:String,val userId:String,val installationId:String,val epoch:String,val generation:String)
data class CloudMigrationUiState(val state:String="idle",val objects:Int=0,val verifiedObjects:Int=0,val totalObjects:Int=0,val errorCode:String?=null,val publishedBatches:Int=0,val retryable:Boolean=false)

/** A regular CM3 publisher must refresh/rebase latest authenticated heads and fence every IO. */
interface CloudMigrationPublisher {
    suspend fun retryPublication(draft:CloudMigrationDraft,intent:JSONObject)
    suspend fun publish(draft:CloudMigrationDraft,jobId:String,receiptHash:String,batchId:String,outputs:List<CloudMigrationOutput>,root:ByteArray,validation:JSONObject,beforeComplete:(String,JSONObject)->Unit):String?
}

internal class CloudMigrationPublicationObsolete : IllegalStateException("migration_publication_obsolete")

/** Explicitly invoked only. There is no startup migration, automatic grant, or background all-library loop. */
class CloudMigrationInitiator(
    private val context: () -> CloudMigrationContext,
    api: CloudMigrationApi,
    private val store: CloudMigrationStore,
    private val trust: CloudWorkerTrust,
    private val inputKey: (CloudMigrationInput) -> ByteArray,
    private val publisher: CloudMigrationPublisher,
    private val policy: CloudFormatWritePolicy = CloudFormatWritePolicy(),
    private val crypto: CloudMigrationCrypto = CloudMigrationCrypto(),
    private val now: () -> Long = System::currentTimeMillis,
    stateOverride:MutableStateFlow<CloudMigrationUiState>? = null,
    observer:CloudMigrationObserver = CloudMigrationObserver {}
) {
    private val mutex=Mutex()
    private val mutableState=stateOverride ?: MutableStateFlow(CloudMigrationUiState())
    val state:StateFlow<CloudMigrationUiState> = mutableState
    private val diagnostics=CloudMigrationDiagnostics(observer)
    private val rawApi=api
    private val api=object:CloudMigrationApi by rawApi {
        override suspend fun download(url:String,size:Long,sha256:String):ByteArray =
            diagnostics.measure(CloudMigrationOperation.DOWNLOAD,downloadedBytes=size) { rawApi.download(url,size,sha256) }
    }
    private suspend fun <T> observed(operation:CloudMigrationOperation,block:suspend()->T):T = diagnostics.measure(operation,
        failed={failure -> mutableState.update { it.copy(errorCode=failure.name.lowercase(),retryable=failure.retryable) }}) {
        block().also { mutableState.update { if(it.state=="superseded")it else it.copy(errorCode=null,retryable=false) } }
    }
    private val cancelling=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingGrants=java.util.concurrent.ConcurrentHashMap<String,JSONObject>() // exact ciphertext retry, memory only
    private fun acceptBody(intent:JSONObject)=JSONObject(intent.toString()).also{it.remove("jobId");it.remove("migrationContext")}
    private fun requestId()=UUID.randomUUID().toString()
    private fun base(id:String)="/cloud/format-migrations/$id".also { CloudMigrationCrypto.uuid(id) }
    private fun checkScope(draft:CloudMigrationDraft):CloudMigrationContext = context().also {
        require(it.userId==draft.plan.getString("userId") && it.installationId==draft.plan.getString("installationId") && it.epoch==draft.plan.getString("libraryEpoch") && it.generation==draft.plan.getString("contentGeneration")) { "migration_scope_changed" }
    }
    private fun check(draft:CloudMigrationDraft):CloudMigrationContext = checkScope(draft).also {
        require(draft.plan.getString("planId") !in cancelling) { "migration_cancelled" }
    }
    private fun save(draft:CloudMigrationDraft,value:JSONObject) { val ctx=checkScope(draft);store.save(ctx.scope,draft.plan.getString("planId"),value) }
    private fun supersede(draft:CloudMigrationDraft,local:JSONObject):Boolean {
        local.remove("publicationIntent");local.remove("batchId");local.remove("outputRootBase64")
        local.put("state","superseded");save(draft,local)
        pendingGrants.remove(draft.plan.getString("planId"))
        return showSuperseded(draft)
    }
    private fun showSuperseded(draft:CloudMigrationDraft):Boolean {
        mutableState.value=CloudMigrationUiState("superseded",draft.inputs.size,errorCode="publication_obsolete",retryable=false)
        return true
    }
    private fun load(id:String):Pair<CloudMigrationDraft,JSONObject> {
        val value=requireNotNull(store.load(context().scope,id)) { "migration_plan_missing" }
        val rows=value.getJSONArray("inputs")
        val inputs=(0 until rows.length()).map { i -> val r=rows.getJSONObject(i);CloudMigrationInput(r.getString("snapshotId"),r.getString("manifestSha256"),CloudObjectDescriptor.fromRaw(r.getString("descriptorRaw")),r.getString("semanticSha256"),r.getString("type"),r.getJSONArray("categories").let { a -> (0 until a.length()).map{a.getString(it)}.toSet() },true,r.optInt("keyVersion",1)) }
        val draft=CloudMigrationDraft(value.getJSONObject("plan"),inputs,value.getJSONObject("details"));checkScope(draft)
        require(draft.planHash==value.getString("planHash") && CloudMigrationPlanBuilder.detailsCommitment(draft.details)==draft.plan.getString("localDetailsCommitment"))
        return draft to value
    }
    suspend fun create(draft:CloudMigrationDraft):JSONObject = observed(CloudMigrationOperation.CREATE) { mutex.withLock {
        check(draft);require(policy.canMigrate(api.capabilities())) { "migration_feature_disabled" };check(draft)
        require(now()<draft.plan.getLong("expiresAt"))
        require(CloudMigrationJcs.bytes(draft.plan).size<=1_048_576) { "migration_not_suitable" }
        val reply=api.post("/cloud/format-migrations/plans",draft.plan);check(draft)
        require(reply.getString("planId")==draft.plan.getString("planId") && reply.getString("planHash")==draft.planHash && reply.getString("state")=="planned")
        val local=JSONObject().put("plan",draft.plan).put("planHash",draft.planHash).put("details",draft.details).put("state","planned")
            .put("inputs",JSONArray(draft.inputs.map { input -> JSONObject().put("snapshotId",input.snapshotId).put("manifestSha256",input.manifestSha256)
                .put("keyVersion",input.keyVersion).put("descriptorRaw",input.descriptor.toRawString()).put("semanticSha256",input.semanticSha256).put("type",input.type).put("categories",JSONArray(input.categories.sorted())) }))
        save(draft,local);mutableState.value=CloudMigrationUiState("planned",draft.inputs.size)
        reply
    } }
    suspend fun consent(planId:String,accepted:Boolean):JSONObject? = observed(CloudMigrationOperation.CONSENT) { mutex.withLock {
        val (draft,local)=load(planId)
        if(!accepted) { mutableState.value=CloudMigrationUiState("declined");return@withLock null }
        require(local.optString("state") in setOf("planned","needs_grant")) { "migration_consent_state" }
        if(local.has("jobId") && pendingGrants[planId]==null) {
            // After process death a sealed grant is deliberately unavailable. Query delivery first.
            val remote=refresh(draft,local)
            if(remote.getString("state") in setOf("granted","running","awaiting_validation","accepted")) {
                if(!local.has("sampleOrder"))local.put("sampleOrder",JSONArray(CloudMigrationResult.permutation(draft.inputs.size)))
                save(draft,local);return@withLock remote
            }
            require(remote.getString("state") in setOf("planned","needs_grant"))
            local.remove("grantSessionId") // a new explicit consent uses a new grant; never change a used body
        }
        val caps=api.capabilities();require(policy.canMigrate(caps)) { "migration_feature_disabled" };check(draft)
        val nowMillis=now();require(nowMillis<draft.plan.getLong("expiresAt"))
        val session=requireNotNull(caps?.optJSONObject("workerSession"))
        val cert=crypto.verifyWorker(session,trust,nowMillis,draft.plan.getJSONObject("transform"))
        val sessionId=cert.getString("sessionId")
        local.put("signingKeyId",session.getString("signingKeyId")).put("workerBuildSha256",cert.getString("workerBuildSha256"))
        if(local.optString("grantSessionId")!=sessionId) {
            local.put("grantId",requestId()).put("authorizeRequestId",requestId()).put("submitRequestId",requestId()).put("grantSessionId",sessionId)
            pendingGrants.remove(planId);save(draft,local)
        }
        val grantId=local.getString("grantId")
        val authorized=api.post(base(planId)+"/grants/authorize",JSONObject().put("requestId",local.getString("authorizeRequestId")).put("planHash",draft.planHash).put("grantId",grantId).put("sessionId",sessionId)
            .put("consent",JSONObject().put("version","cloud-format-memory-once-v1").put("accepted",true)))
        check(draft)
        val acceptedAt=CloudMigrationCrypto.integer(authorized,"acceptedAt");val expires=CloudMigrationCrypto.integer(authorized,"expiresAt")
        require(authorized.getString("grantId")==grantId && expires==minOf(draft.plan.getLong("expiresAt"),cert.getLong("expiresAt"),acceptedAt+21_600_000) && now()<expires)
        val jobId=authorized.getString("jobId");CloudMigrationCrypto.uuid(jobId)
        val expectedAad=JSONObject().put("userId",draft.plan.getString("userId")).put("installationId",draft.plan.getString("installationId")).put("planId",planId).put("planHash",draft.planHash).put("grantId",grantId).put("sessionId",sessionId).put("expiresAt",expires)
        require(CloudMigrationJcs.canonical(authorized.getJSONObject("aad").toString())==CloudMigrationJcs.canonical(expectedAad.toString()))
        val root=local.optString("outputRootBase64").takeIf{it.isNotBlank()}?.let { CloudMigrationCrypto.b64(it,32) } ?: crypto.randomBytes(32)
        try {
            local.put("outputRootBase64",CloudMigrationCrypto.encode(root)).put("jobId",jobId).put("grantExpiresAt",expires)
            require(acceptedAt<=now() && acceptedAt>=draft.plan.getLong("createdAt"))
            // Save the output validation root before sending; never save the grant or input keys.
            save(draft,local)
            val sealed=pendingGrants[planId] ?: run {
                val objects=JSONArray()
                for(input in draft.inputs) {
                    val key=inputKey(input)
                    try { objects.put(JSONObject().put("descriptor",input.descriptor.toJson()).put("chunkKeys",CloudSnapshotCodec().migrationChunkKeys(key,input.descriptor))) }
                    finally { key.fill(0) }
                }
                val plain=JSONObject().put("v",1).put("userId",draft.plan.getString("userId")).put("installationId",draft.plan.getString("installationId"))
                    .put("planId",planId).put("planHash",draft.planHash).put("grantId",grantId).put("sessionId",sessionId).put("expiresAt",expires).put("jobId",jobId)
                    .put("inputObjects",objects).put("outputRootBase64",CloudMigrationCrypto.encode(root)).put("targetFormats",draft.plan.getJSONArray("targetFormats"))
                    .put("invariants",JSONArray(draft.inputs.map { JSONObject().put("inputDescriptorSha256",it.descriptorSha256).put("semanticSha256",it.semanticSha256) }))
                    .put("budget",draft.plan.getJSONObject("budget")).put("transform",draft.plan.getJSONObject("transform"))
                    .put("allowedOutputs",JSONArray(draft.inputs.map { JSONObject().put("name",it.descriptor.name).put("schema","r48.body-output.v1") }))
                require(CloudMigrationPlanBuilder.detailsCommitment(CloudMigrationPlanBuilder.detailsFromGrant(objects,plain.getJSONArray("invariants")))==draft.plan.getString("localDetailsCommitment"))
                val bytes=CloudMigrationJcs.bytes(plain)
                require(bytes.size<=780_000) { "migration_not_suitable" }
                try { crypto.sealGrant(CloudMigrationCrypto.b64(cert.getString("pubKey"),32),draft.planHash,grantId,sessionId,expectedAad,bytes) }
                finally { bytes.fill(0);objects.put(JSONObject.NULL) }
            }.also { pendingGrants[planId]=it }
            val reply=api.post(base(planId)+"/grants/$grantId",JSONObject().put("requestId",local.getString("submitRequestId")).put("planHash",draft.planHash).put("sessionId",sessionId).put("sealedGrant",sealed));check(draft)
            require(reply.getString("jobId")==jobId && reply.getString("planHash")==draft.planHash && reply.getString("state")=="granted")
            local.put("state","granted").put("sampleOrder",JSONArray(CloudMigrationResult.permutation(draft.inputs.size))).put("revision",reply.getLong("revision"))
            save(draft,local);pendingGrants.remove(planId);mutableState.value=CloudMigrationUiState("granted",draft.inputs.size)
            reply
        } finally { root.fill(0) }
    } }
    suspend fun refresh(planId:String):JSONObject = observed(CloudMigrationOperation.REFRESH) { mutex.withLock { val (draft,local)=load(planId);refresh(draft,local) } }
    private suspend fun refresh(draft:CloudMigrationDraft,local:JSONObject):JSONObject {
        if(local.optString("state")=="superseded") {
            showSuperseded(draft)
            return JSONObject().put("state","superseded").put("jobId",local.getString("jobId")).put("planId",draft.plan.getString("planId")).put("planHash",draft.planHash)
        }
        val reply=api.get(base(local.getString("jobId")));checkScope(draft)
        require(reply.getString("jobId")==local.getString("jobId") && reply.getString("planId")==draft.plan.getString("planId") && reply.getString("planHash")==draft.planHash)
        val state=reply.getString("state");require(state in setOf("planned","granted","running","awaiting_validation","accepted","needs_grant","budget_exhausted","failed","cancelled","expired"))
        require(CloudMigrationCrypto.integer(reply,"revision")>=local.optLong("revision"))
        local.put("state",state).put("revision",reply.getLong("revision"))
        if(state in setOf("cancelled","expired","budget_exhausted","failed","accepted")) {
            local.remove("outputRootBase64");pendingGrants.remove(draft.plan.getString("planId"))
        }
        save(draft,local)
        mutableState.value=CloudMigrationUiState(state,draft.inputs.size,publishedBatches=reply.optInt("publishedBatchCount"))
        return reply
    }
    /** Cancellation is not queued behind a long validation/download lock. */
    suspend fun cancel(planId:String):JSONObject = observed(CloudMigrationOperation.CANCEL) {
        val (draft,local)=load(planId); val job=local.optString("jobId").ifBlank{planId}
        cancelling.add(planId)
        val id=local.optString("cancelRequestId").ifBlank { requestId().also{local.put("cancelRequestId",it);save(draft,local)} }
        var cancelled:JSONObject?=null
        for(attempt in 0..2) {
            val latest=api.get(base(job));checkScope(draft)
            require(latest.getString("planHash")==draft.planHash)
            try {
                cancelled=api.post(base(latest.getString("jobId"))+"/cancel",JSONObject().put("requestId",id).put("expectedRevision",CloudMigrationCrypto.integer(latest,"revision")))
                checkScope(draft);break
            } catch(error:CloudHttpException) {
                if(error.status!=409 || cloudErrorCode(error)!="migration_plan_conflict" || attempt==2)throw error
                // Worker progress may advance revision between GET and cancel. No new grant or
                // publication is allowed while cancelling; refresh only this optimistic fence.
                kotlinx.coroutines.delay(50L*(attempt+1))
            }
        }
        val reply=requireNotNull(cancelled)
        require(reply.getString("state")=="cancelled");local.put("state","cancelled").remove("outputRootBase64");save(draft,local);pendingGrants.remove(planId)
        // The validation coroutine observes cancelling before any publish/accept or at beforeComplete.
        mutableState.value=CloudMigrationUiState("cancelled",publishedBatches=reply.optInt("publishedBatchCount"))
        reply
    }

    /** One bounded verification run. Returns false when another scheduled/user-invoked run is required. */
    suspend fun validateAndPublish(planId:String,budget:CloudMigrationRunBudget = CloudMigrationRunBudget()):Boolean = observed(CloudMigrationOperation.VALIDATE) { budget.within { mutex.withLock {
        val (draft,local)=load(planId)
        if(local.optString("state")=="superseded")return@withLock showSuperseded(draft)
        val capabilities=api.capabilities();check(draft)
        // Reconcile a complete whose response/accept was lost before doing any new conversion or publish.
        val status=refresh(draft,local)
        if(local.has("publicationIntent")) {
            val intent=local.getJSONObject("publicationIntent")
            val proof=api.publicationProof(local.getString("jobId"),intent.getString("batchId"));check(draft)
            if(proof==null) {
                if(status.getString("state") in setOf("cancelled","expired","failed","budget_exhausted"))return@withLock supersede(draft,local)
                require(status.getString("state")=="awaiting_validation" && policy.canMigrate(capabilities))
                try { publisher.retryPublication(draft,intent) }
                catch(error:IllegalStateException) {if(error.message=="migration_publication_obsolete")return@withLock supersede(draft,local) else throw error}
                check(draft)
                api.post(base(local.getString("jobId"))+"/accept",acceptBody(intent));check(draft)
                local.remove("publicationIntent");save(draft,local);return@withLock true
            }
            for(field in listOf("batchId","snapshotId","receiptSha256"))require(proof.getString(field)==intent.getString(field))
            require(CloudMigrationJcs.canonical(proof.getJSONObject("validation").toString())==CloudMigrationJcs.canonical(intent.getJSONObject("validation").toString()))
            if(status.getString("state") in setOf("cancelled","expired")) {
                mutableState.value=mutableState.value.copy(publishedBatches=status.optInt("publishedBatchCount"));return@withLock true
            }
            api.post(base(local.getString("jobId"))+"/accept",acceptBody(intent));check(draft)
            local.remove("publicationIntent");save(draft,local);return@withLock true
        }
        require(policy.canMigrate(capabilities)) { "migration_feature_disabled" }
        val signing=local.getString("signingKeyId")
        require(signing !in trust.revokedPinHashes && listOfNotNull(trust.currentPin,trust.nextPin).any{CloudSnapshotCodec.sha256(it)==signing}) { "migration_worker_revoked" }
        require(local.getString("workerBuildSha256") in trust.allowedBuilds && CloudMigrationCrypto.transformKey(draft.plan.getJSONObject("transform")) in trust.allowedTransforms)
        require(status.getString("state")=="awaiting_validation") { "migration_validation_required" }
        val result=api.get(base(local.getString("jobId"))+"/result");check(draft)
        require(result.getString("planHash")==draft.planHash && now()<CloudMigrationCrypto.integer(result,"candidateExpiresAt"))
        val receipt=result.getJSONObject("receipt");require(receipt.getInt("version")==1)
        val receiptHash=CloudMigrationCrypto.hash(receipt.getString("sha256"));val size=CloudMigrationCrypto.integer(receipt,"sizeBytes")
        val root=CloudMigrationCrypto.b64(local.getString("outputRootBase64"),32)
        try {
            budget.reserve(size)
            val blob=api.download(receipt.getString("downloadRef"),size,receiptHash);check(draft);budget.checkpoint()
            val aad=JSONObject().put("v",1).put("planId",planId).put("planHash",draft.planHash).put("jobId",local.getString("jobId")).put("contentGeneration",draft.plan.getString("contentGeneration"))
            val outputs=CloudMigrationResult.validate(crypto.openReceipt(root,draft.planHash,aad,blob,receiptHash,size),draft,local.getString("jobId"))
            require(outputs.flatMap{it.descriptor.chunks}.distinctBy{it.ciphertextSha256}.size==result.getInt("candidateChunkCount"))
            if(local.optString("receiptHash")!=receiptHash) local.put("receiptHash",receiptHash).put("verified",JSONArray())
            val mode=draft.plan.getJSONObject("validation").getString("mode")
            val order=local.getJSONArray("sampleOrder")
            val byInput=outputs.associateBy { it.input.descriptorSha256 }
            val chosen=(0 until order.length()).map { draft.inputs[order.getInt(it)].descriptorSha256 }.filter { it in byInput }
                .take(if(mode=="full") outputs.size else CloudMigrationResult.sampleCount(outputs.size))
            val verified=local.getJSONArray("verified").let{a -> (0 until a.length()).map{a.getString(it)}.toMutableSet()}
            for(id in chosen.filter { it !in verified }) {
                val output=byInput.getValue(id);val required=output.descriptor.chunks.distinctBy{it.ciphertextSha256}.sumOf { it.ciphertextBytes.toLong() }
                if(required+size>8_388_608 || output.descriptor.originalBytes>8_388_608) error("migration_not_suitable")
                if(required>8_388_608-budget.bytes) { save(draft,local);return@withLock false }
                val chunks=linkedMapOf<String,ByteArray>()
                for(chunk in output.descriptor.chunks.distinctBy{it.ciphertextSha256}) {
                    budget.reserve(chunk.ciphertextBytes.toLong())
                    val download=api.candidateDownload(local.getString("jobId"),chunk.ciphertextSha256);check(draft)
                    require(download.getString("planHash")==draft.planHash && download.getString("jobId")==local.getString("jobId") && download.getString("sha256")==chunk.ciphertextSha256 && CloudMigrationCrypto.integer(download,"sizeBytes")==chunk.ciphertextBytes.toLong())
                    chunks[chunk.ciphertextSha256]=api.download(download.getString("downloadRef"),chunk.ciphertextBytes.toLong(),chunk.ciphertextSha256);check(draft);budget.checkpoint()
                }
                // Root is scoped to this plan; the temporary manifest never leaves the client.
                val temp=CloudSnapshotManifest(UUID.randomUUID().toString(),"validation",1,1,now(),emptyMap(),emptyMap(),listOf(output.descriptor))
                val bytes=CloudSnapshotCodec().restoreObjects(root,temp){chunks.getValue(it)}.getValue(output.descriptor.name)
                try { require(CloudSnapshotCodec.sha256(bytes)==output.input.semanticSha256) { "migration_result_mismatch" } } finally { bytes.fill(0) }
                verified.add(id);local.put("verified",JSONArray(verified.toList()));save(draft,local)
            }
            check(draft);budget.checkpoint()
            require(verified.containsAll(chosen))
            val validation=JSONObject().put("mode",mode).put("verifiedObjects",chosen.size).put("totalObjects",outputs.size)
            mutableState.value=CloudMigrationUiState("awaiting_validation",draft.inputs.size,chosen.size,outputs.size)
            val batchId=local.optString("batchId").ifBlank {requestId().also{local.put("batchId",it);save(draft,local)}}
            // Gate/expiry/cancellation is checked again by publisher and atomically at normal complete.
            require(policy.canMigrate(api.capabilities())) { "migration_feature_disabled" };check(draft)
            var publication:JSONObject?=null
            val snapshotId=try { publisher.publish(draft,local.getString("jobId"),receiptHash,batchId,outputs,root,validation) { id,migrationContext ->
                check(draft)
                val intent=JSONObject().put("requestId",requestId()).put("jobId",local.getString("jobId")).put("migrationContext",migrationContext).put("planHash",draft.planHash).put("batchId",batchId).put("snapshotId",id).put("receiptSha256",receiptHash).put("validation",migrationContext.getJSONObject("validation"))
                local.put("publicationIntent",intent);save(draft,local);publication=intent
            } } catch(error:IllegalStateException) {if(error.message=="migration_publication_obsolete")return@withLock supersede(draft,local) else throw error}
                ?: return@withLock false
            check(draft)
            val proof=requireNotNull(publication);require(proof.getString("snapshotId")==snapshotId)
            val accepted=api.post(base(local.getString("jobId"))+"/accept",acceptBody(proof));check(draft)
            val acceptedState=accepted.optString("state","awaiting_validation")
            require(acceptedState in setOf("accepted","awaiting_validation"))
            local.remove("publicationIntent");local.remove("batchId");local.put("state",acceptedState)
            if(acceptedState=="accepted")local.remove("outputRootBase64")
            save(draft,local);mutableState.value=CloudMigrationUiState(acceptedState,draft.inputs.size,chosen.size,outputs.size)
            true
        } finally { root.fill(0) }
    } } }
}
