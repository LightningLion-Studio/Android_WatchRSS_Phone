package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import com.lightningstudio.watchrss.phone.data.backup.*
import com.lightningstudio.watchrss.phone.data.book.BookSync
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import kotlinx.coroutines.*
import okhttp3.*
import org.json.*
import org.junit.Test
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real HTTP/real isolated worker. Separate opt-in task; never an in-process server or synthetic response. */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class R48JointLocalIntegrationTest {
    private fun checkThat(value:Boolean,code:String) {check(value){code}}
    private fun privateWrite(file:File,bytes:ByteArray) {
        val parent=requireNotNull(file.parentFile);parent.mkdirs();Files.setPosixFilePermissions(parent.toPath(),PosixFilePermissions.fromString("rwx------"))
        val tmp=File(file.parentFile,file.name+".tmp");Files.write(tmp.toPath(),bytes)
        Files.setPosixFilePermissions(tmp.toPath(),PosixFilePermissions.fromString("rw-------"))
        Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    private inner class FileStore(private val directory:File):CloudMigrationStore {
        private fun file(scope:String,id:String)=File(directory,"checkpoint-"+CloudSnapshotCodec.sha256("$scope/$id".toByteArray())+".json")
        override fun load(scope:String,planId:String)=file(scope,planId).takeIf{it.isFile}?.readText()?.let(::JSONObject)
        override fun save(scope:String,planId:String,value:JSONObject)=privateWrite(file(scope,planId),value.toString().toByteArray())
        override fun remove(scope:String,planId:String) {file(scope,planId).delete()}
    }
    @Test fun resultReadBodyDisconnectCanBeRetriedWithoutChangingAcceptedHead()=runBlocking {
        val envFile=File(requireNotNull(System.getenv("WATCHRSS_R48_JOINT_ENV")))
        val env=JSONObject(envFile.readText());checkThat(env.getBoolean("localOnly") && env.getBoolean("ready"),"joint_not_ready")
        val metadataFile=File(envFile.parentFile,"r48-phone.json");val originalMetadata=metadataFile.readBytes();val meta=JSONObject(originalMetadata.toString(Charsets.UTF_8))
        checkThat(meta.getBoolean("ready") && meta.getString("runId")==env.getString("runId"),"joint_phone_not_ready")
        val seeds=JSONObject(File(env.getString("seedFile")).readText());val identity=seeds.getJSONObject("clients").getJSONObject("phone")
        val session=PhoneAccountSession(env.getString("userId"),"synthetic",identity.getString("accessToken"),"",System.currentTimeMillis()+86_400_000)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val resultPath="/functions/v1/cloud/format-migrations/${meta.getString("jobId")}/result"
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            if(chain.request().url.encodedPath==resultPath)calls.incrementAndGet()
            chain.proceed(chain.request())
        }.build()
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl=env.getString("baseUrl"),supabaseAnonKey=env.getString("supabaseAnonKey")),{identity.getString("appAccessToken")},http,downloadBackoff={delay(20)})
        client.currentDeviceId=identity.getString("deviceId");client.useEpoch(session,env.getString("libraryEpoch"));client.useContentGeneration(session,env.getString("contentGeneration"))
        val api=PhoneCloudMigrationApi(client,{session},{})
        val process=ProcessBuilder("python3",env.getJSONObject("faultControl").getString("tool"),"fault","--method","GET","--path",resultPath)
            .redirectErrorStream(true).redirectOutput(File(envFile.parentFile,"phone-evidence/receipt-fault-arm.log")).start()
        checkThat(process.waitFor(10,TimeUnit.SECONDS) && process.exitValue()==0,"joint_read_fault_arm_failed")
        val interrupted=runCatching{api.get("/cloud/format-migrations/${meta.getString("jobId")}/result")}.exceptionOrNull()
        checkThat(interrupted is IOException && interrupted !is CloudHttpException,"joint_read_disconnect_not_observed")
        val result=api.get("/cloud/format-migrations/${meta.getString("jobId")}/result");val receipt=result.getJSONObject("receipt")
        val size=receipt.getLong("sizeBytes");val budget=CloudMigrationRunBudget(now={System.nanoTime()/1_000_000});budget.reserve(size)
        val blob=budget.within{api.download(receipt.getString("downloadRef"),size,receipt.getString("sha256"))}
        checkThat(calls.get()==2 && budget.bytes==size,"joint_read_retry_budget_or_count")
        val rootFile=File(meta.getString("outputRootFile"));checkThat(CloudSnapshotCodec.sha256(rootFile.readBytes())==meta.getString("outputRootFileSha256"),"joint_root_artifact_changed")
        val root=CloudMigrationCrypto.b64(JSONObject(rootFile.readText()).getString("outputRootBase64"),32)
        try {
            val aad=JSONObject().put("v",1).put("planId",meta.getString("planId")).put("planHash",meta.getString("planHash")).put("jobId",meta.getString("jobId")).put("contentGeneration",env.getString("contentGeneration"))
            val opened=CloudMigrationCrypto().openReceipt(root,meta.getString("planHash"),aad,blob,receipt.getString("sha256"),size)
            checkThat(opened.getString("planHash")==meta.getString("planHash") && opened.getJSONArray("objects").length()==2,"joint_retried_receipt_invalid")
        } finally {root.fill(0)}
        val dropped=JSONObject(File(env.getJSONObject("faultControl").getString("dropEvidence")).readText())
        checkThat(dropped.getString("method")=="GET" && dropped.getString("path")==resultPath && dropped.getInt("committedResponseStatus")==200,"joint_read_body_not_really_dropped")
        checkThat(originalMetadata.contentEquals(metadataFile.readBytes()),"joint_shared_head_metadata_changed")
        File(envFile.parentFile,"phone-evidence/result-body-retry.json").writeText(JSONObject().put("status","PASS").put("attempts",calls.get()).put("payloadBytesReserved",budget.bytes).put("receiptSha256",receipt.getString("sha256")).put("headUnchanged",true).toString(2))
    }
    @Test fun productionCodecAndInitiatorCompleteRealLocalJointProtocol()=runBlocking {
        val envPath=File(requireNotNull(System.getenv("WATCHRSS_R48_JOINT_ENV"))).canonicalFile
        checkThat(envPath.name=="r48-env.json" && envPath.isFile,"joint_env_missing")
        val env=JSONObject(envPath.readText());checkThat(env.getBoolean("ready") && env.getBoolean("localOnly"),"joint_not_ready")
        val base=env.getString("baseUrl");val uri=java.net.URI(base)
        checkThat(uri.scheme=="http" && (uri.host=="127.0.0.1" || uri.host=="localhost" || uri.host.startsWith("172.")),"joint_origin_not_local")
        checkThat(env.getJSONObject("worker").getString("imageId")=="sha256:6b2152c28fd8e6176ae87db4032d31bdfea6e2d6f6c929899ffadc6903afbdfd","joint_worker_image_drift")
        val seedFile=File(env.getString("seedFile")).canonicalFile
        checkThat(seedFile.parentFile==envPath.parentFile,"joint_seed_scope")
        val seeds=JSONObject(seedFile.readText());val identity=seeds.getJSONObject("clients").getJSONObject("phone")
        val session=PhoneAccountSession(env.getString("userId"),"synthetic",identity.getString("accessToken"),"",System.currentTimeMillis()+86_400_000)
        val device=identity.getString("deviceId");val key=CloudMigrationCrypto.b64(seeds.getString("rootKeyBase64"),32)
        val output=File(envPath.parentFile,"phone-private");output.mkdirs();Files.setPosixFilePermissions(output.toPath(),PosixFilePermissions.fromString("rwx------"))
        val evidence=File(envPath.parentFile,"phone-evidence").apply{mkdirs()}
        val events=JSONArray();val requests=mutableListOf<String>();var offline=false;var dropGrant=false
        val requestLog=File(evidence,"requests-${System.currentTimeMillis()}.txt")
        fun fault(path:String) {
            val tool=env.getJSONObject("faultControl").getString("tool")
            val process=ProcessBuilder("python3",tool,"fault","--method","POST","--path",path)
                .redirectErrorStream(true).redirectOutput(File(evidence,"fault-arm.log")).start()
            checkThat(process.waitFor(10,TimeUnit.SECONDS) && process.exitValue()==0,"joint_fault_arm_failed")
        }
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(30,TimeUnit.SECONDS).addInterceptor { chain ->
            checkThat(JSONObject(envPath.readText()).getString("runId")==env.getString("runId"),"joint_environment_replaced")
            if(offline)throw IOException("joint_offline")
            val request=chain.request();val trace="${request.method} ${request.url.encodedPath}";requests+=trace;requestLog.appendText(trace+"\n")
            val response=chain.proceed(request)
            if(!response.isSuccessful)requestLog.appendText("status=${response.code} code="+runCatching{JSONObject(response.peekBody(4096).string()).optString("error")}.getOrDefault("non_json")+"\n")
            if(dropGrant && request.url.encodedPath.endsWith("/grants/authorize") && response.isSuccessful) {
                val b=okio.Buffer();request.body!!.writeTo(b);val grantId=JSONObject(b.readUtf8()).getString("grantId")
                fault(request.url.encodedPath.removeSuffix("authorize")+grantId);dropGrant=false
            }
            response
        }.build()
        val client=PhoneCloudClient(AccountEnvironment(backendBaseUrl=base,supabaseAnonKey=env.getString("supabaseAnonKey")),
            deviceAccessToken={identity.getString("appAccessToken")},http=http,downloadBackoff={delay(it.coerceAtMost(1000))})
        client.currentDeviceId=device;client.allowR48Writes={true}
        val bootstrap=client.bootstrap(session);client.useEpoch(session,bootstrap.libraryEpoch)
        CloudKeyTrust.validate(session.userId,bootstrap.libraryEpoch,bootstrap.keyVersionFloor,bootstrap.keyCommitments,mapOf(1 to key))
        checkThat(bootstrap.libraryEpoch==env.getString("libraryEpoch") && client.knownContentGeneration(session)==env.getString("contentGeneration"),"joint_scope_mismatch")
        val ctx=CloudMigrationContext(client.scopeId(session),session.userId,device,bootstrap.libraryEpoch,env.getString("contentGeneration"))
        val transform=JSONObject().put("id","identity-repack").put("version",1).put("codeSha256",env.getJSONObject("worker").getString("transformSha256"))
        val pins=env.getJSONObject("worker").getJSONArray("pins")
        val trust=CloudWorkerTrust(CloudMigrationCrypto.b64(pins.getString(0),32),CloudMigrationCrypto.b64(pins.getString(1),32),emptySet(),
            setOf(env.getJSONObject("worker").getString("buildSha256")),setOf(CloudMigrationCrypto.transformKey(transform)))
        val codec=CloudSnapshotCodec();val store=FileStore(output);val api=PhoneCloudMigrationApi(client,{session},{checkThat(client.knownContentGeneration(session)==ctx.generation,"joint_generation_changed")})
        suspend fun heads()=client.snapshotHeads(session)
        suspend fun manifests()=heads().filter{it.keyVersion==1}.map {head ->
            val remote=client.snapshot(session,head.id,true)
            CloudSnapshotReuse.authenticate(codec,key,head,client.download(remote.manifestSignedUrl,head.manifestSizeBytes,head.manifestSha256))
        }
        fun next(heads:List<CloudSnapshotHead>):Long {
            val sequenceFile=File(output,"sequence.txt")
            val local=sequenceFile.takeIf{it.exists()}?.readText()?.toLong() ?: (identity.getLong("nextDeviceSequence")-1)
            val next=maxOf(local,heads.filter{it.sourceDeviceId==device}.maxOfOrNull{it.deviceSequence} ?: 0,bootstrap.devices.firstOrNull{it.deviceId==device}?.lastSequence ?: 0)+1
            privateWrite(sequenceFile,next.toString().toByteArray());return next
        }
        suspend fun upload(snapshot:EncryptedCloudSnapshot,binding:JSONObject?=null,before:(()->Unit)?=null) {
            val reservation=client.reserveSnapshot(session,snapshot,30,ctx.epoch,binding)
            for(target in reservation.missingObjects) {
                val fresh=client.refreshUpload(session,target,ctx.epoch)
                if(!fresh.alreadyUploaded)client.uploadCiphertext(session,fresh,if(target.kind=="manifest")snapshot.encryptedManifest else snapshot.newCiphertextChunks.getValue(target.sha256),ctx.epoch)
            }
            before?.invoke();client.completeSnapshot(session,snapshot,ctx.epoch,binding)
        }
        val originals=linkedMapOf<String,ByteArray>();val entries=mutableListOf<CloudPrivateOverlayEntry>()
        for(i in 1..2) {
            val id="joint-${env.getString("runId")}-article-$i";val text="R48 joint original $i 中文与 HTML 原字节\nline two"
            val row=PhoneArticleEntity(id,device,"https://synthetic.invalid/r48/$i","Joint article $i","Site","", "<p>$text &amp; preserved</p>",text,null,
                CloudSnapshotCodec.sha256(text.toByteArray()),1,1,true,1,i.toLong(),null,null,false,0,0,false,0,0,false,0)
            val name=CloudPrivateOverlay.articleName(id);originals[name]=CloudPrivateOverlayArchive.encode(row)
            entries+=CloudPrivateOverlayEntry("article",id,name,System.currentTimeMillis(),device)
        }
        val bookText="R48 joint book original 中文\nchapter body";val fingerprint=BookSync.fingerprint(listOf(BookSync.chapterHash(1,"Chapter",bookText)));val bookId=BookSync.id(fingerprint)
        val bookSource=ImportedContentIds.txtNovelSourceUrl("r48-joint")
        val bookBytes=(BookSync.canonical(JSONObject().put("format","watchrss-book").put("version",1).put("title","Joint book").put("chapterCount",1).put("sourceUrl",bookSource).put("kind","txt"))+"\n"+
            BookSync.canonical(JSONObject().put("chapterIndex",1).put("title","Chapter").put("contentText",bookText).put("contentHtml",JSONObject.NULL).put("url",ImportedContentIds.txtNovelChapterUrl("r48-joint",1,"original")))+"\n").toByteArray()
        val bookName="books/$bookId.jsonl";originals[bookName]=bookBytes
        entries+=CloudPrivateOverlayEntry("book",bookId,bookName,System.currentTimeMillis(),device)
        val index=CloudPrivateOverlay.encode(entries);originals[CloudPrivateOverlay.NAME]=index
        val logical=originals.map{(name,bytes)->CloudLogicalObject(name,bytes,false).let{ if(name==bookName)it.copy(privateBook=JSONObject().put("bookId",bookId).put("fingerprint",fingerprint).put("title","Joint book").put("chapterCount",1).put("sourceUrl",bookSource).put("kind","txt").put("packagePath",bookName)) else it }}
        val oldHeads=heads();val old=manifests().firstOrNull{it.sourceDeviceId==device}
        val source=codec.create(key,1,device,next(oldHeads),logical,parentHeads=oldHeads.associate{it.sourceDeviceId to it.id},observedHeads=oldHeads.associate{it.sourceDeviceId to it.deviceSequence},
            previousManifest=old,carryForwardObjects=old?.objects.orEmpty().filterNot { d -> logical.any{it.name==d.name} },snapshotSettings=JSONObject().put("backendScope",ctx.scope).put("ownerUserId",ctx.userId).put("libraryEpoch",ctx.epoch).put("contentGeneration",ctx.generation),privateOverlay=true)
        upload(source);events.put(JSONObject().put("step","write").put("status","PASS").put("snapshotId",source.manifest.snapshotId))
        val sourceRemote=client.snapshot(session,source.manifest.snapshotId,true)
        val sourceManifest=CloudSnapshotReuse.authenticate(codec,key,sourceRemote.head,client.download(sourceRemote.manifestSignedUrl,sourceRemote.head.manifestSizeBytes,sourceRemote.head.manifestSha256))
        val selected=sourceManifest.objects.filter{it.name.startsWith("private/articles/")}
        fun draft()=CloudMigrationPlanBuilder.create(ctx.userId,device,ctx.epoch,ctx.generation,transform,selected.map{CloudMigrationInput(source.manifest.snapshotId,
            CloudSnapshotCodec.sha256(source.encryptedManifest),it,CloudSnapshotCodec.sha256(originals.getValue(it.name)),"private-article-body",setOf("article-metadata","article-txt","article-html"),true)},System.currentTimeMillis(),"full")
        var dropComplete=false;var publishCount=0; var rootPath:File?=null
        val publisher=object:CloudMigrationPublisher {
            override suspend fun retryPublication(draft:CloudMigrationDraft,intent:JSONObject) {
                val bytes=File(output,"manifest-${intent.getString("snapshotId")}.bin").readBytes()
                val manifest=codec.decryptManifest(key,intent.getString("snapshotId"),bytes)
                client.completeSnapshot(session,EncryptedCloudSnapshot(manifest,bytes,emptyMap()),ctx.epoch,intent.getJSONObject("migrationContext"))
            }
            override suspend fun publish(draft:CloudMigrationDraft,jobId:String,receiptHash:String,batchId:String,outputs:List<CloudMigrationOutput>,root:ByteArray,validation:JSONObject,beforeComplete:(String,JSONObject)->Unit):String? {
                publishCount++
                val currentHeads=heads();val current=manifests().first{it.sourceDeviceId==device}
                val rebased=CloudMigrationResult.rebase(current,outputs,key,root,draft.plan.getString("planId"))
                checkThat(rebased.zip(current.objects).count{(a,b)->a.toRawString()!=b.toRawString()}==outputs.size,"joint_rebase_skipped")
                val snapshot=codec.create(key,1,device,next(currentHeads),emptyList(),parentHeads=currentHeads.associate{it.sourceDeviceId to it.id},observedHeads=currentHeads.associate{it.sourceDeviceId to it.deviceSequence},previousManifest=current,carryForwardObjects=rebased,snapshotSettings=current.settings)
                val binding=JSONObject().put("jobId",jobId).put("planHash",draft.planHash).put("batchId",batchId).put("receiptSha256",receiptHash)
                    .put("outputCiphertextSha256s",JSONArray(outputs.flatMap{it.descriptor.chunks}.map{it.ciphertextSha256}.distinct().sorted())).put("validation",validation)
                rootPath=File(output,"root-${draft.plan.getString("planId")}.json").also {privateWrite(it,JSONObject().put("outputRootBase64",CloudMigrationCrypto.encode(root)).put("planId",draft.plan.getString("planId")).toString().toByteArray())}
                privateWrite(File(output,"manifest-${snapshot.manifest.snapshotId}.bin"),snapshot.encryptedManifest)
                upload(snapshot,binding) {
                    beforeComplete(snapshot.manifest.snapshotId,binding)
                    if(dropComplete){dropComplete=false;fault("/functions/v1/cloud/snapshots/complete")}
                }
                return snapshot.manifest.snapshotId
            }
        }
        fun initiator()=CloudMigrationInitiator({ctx},api,store,trust,{key.copyOf()},publisher,CloudFormatWritePolicy(true,true))
        suspend fun waitFor(client:CloudMigrationInitiator,id:String,target:Set<String>):JSONObject {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(120)
            while(true) {
                val status=client.refresh(id)
                if(status.getString("state") in target)return status
                checkThat(status.getString("state") !in setOf("failed","expired","cancelled","budget_exhausted"),"joint_worker_terminal_${status.optString("state")}_${status.optString("errorCode")}")
                checkThat(System.nanoTime()<end,"joint_worker_wait_timeout");delay(500)
            }
        }
        val plan=draft();var active=initiator();active.create(plan);val planId=plan.plan.getString("planId")
        offline=true;checkThat(runCatching{active.consent(planId,true)}.exceptionOrNull() is IOException,"joint_offline_did_not_fail");offline=false
        checkThat(store.load(ctx.scope,planId)!!.getString("state")=="planned","joint_offline_lost_plan")
        events.put(JSONObject().put("step","offline").put("status","PASS"))
        dropGrant=true;checkThat(runCatching{active.consent(planId,true)}.exceptionOrNull() is IOException,"joint_grant_loss_not_observed")
        val grantWrites=requests.count{it.contains("/grants/") && !it.endsWith("/authorize")}
        active=initiator();active.consent(planId,true)
        checkThat(requests.count{it.contains("/grants/") && !it.endsWith("/authorize")}==grantWrites,"joint_duplicate_grant")
        events.put(JSONObject().put("step","grant_response_loss_restart").put("status","PASS"))
        waitFor(active,planId,setOf("awaiting_validation"));dropComplete=true
        val completeLoss=runCatching{active.validateAndPublish(planId,CloudMigrationRunBudget(now={System.nanoTime()/1_000_000}))}.exceptionOrNull()
        checkThat(completeLoss is IOException && completeLoss !is CloudHttpException && publishCount==1,"joint_complete_loss_not_observed")
        val dropped=JSONObject(File(env.getJSONObject("faultControl").getString("dropEvidence")).readText())
        checkThat(dropped.getString("path")=="/functions/v1/cloud/snapshots/complete" && dropped.getInt("committedResponseStatus")==200,"joint_complete_not_committed_before_loss")
        active=initiator();checkThat(active.validateAndPublish(planId,CloudMigrationRunBudget(now={System.nanoTime()/1_000_000})),"joint_accept_not_complete")
        val final=active.refresh(planId);checkThat(final.getString("state")=="accepted" && publishCount==1,"joint_duplicate_publication")
        val local=store.load(ctx.scope,planId)!!;val jobId=local.getString("jobId")
        val proof=final.getJSONArray("publications").getJSONObject(0);val snapshotId=proof.getString("snapshotId")
        events.put(JSONObject().put("step","complete_response_loss_restart").put("status","PASS").put("publishCalls",publishCount))
        client.allowR48Writes={false}
        val remote=client.snapshot(session,snapshotId);val encrypted=client.download(remote.manifestSignedUrl,remote.head.manifestSizeBytes,remote.head.manifestSha256)
        val accepted=CloudSnapshotReuse.authenticate(codec,key,remote.head,encrypted)
        val chunks=remote.chunks.associate{it.sha256 to client.download(it.signedUrl,it.sizeBytes,it.sha256)};val restored=codec.restoreObjects(key,accepted){chunks.getValue(it)}
        for((name,bytes) in originals)checkThat(restored[name]?.contentEquals(bytes)==true,"joint_accepted_original_mismatch")
        for((name,bytes) in restored.filterKeys{it !in originals}) {
            val baseline=seeds.getJSONObject("baseline")
            checkThat(name==baseline.getJSONObject("inputDescriptor").getString("name") && CloudSnapshotCodec.sha256(bytes)==baseline.getString("plaintextSha256"),"joint_unexpected_carried_object")
            originals[name]=bytes
        }
        checkThat(accepted.objects.filter{it.name in selected.map{d->d.name}}.all{it.objectKeyEnvelope!=null},"joint_output_not_wrapped")
        val pending=codec.create(key,1,device,remote.head.deviceSequence+1,listOf(CloudLogicalObject("pending/local.txt","local pending".toByteArray(),false)))
        val cache=CloudLocalCache(File(output,"pending-cache"));cache.storePendingManifest(ctx.scope,pending.manifest.snapshotId,pending.encryptedManifest,emptyList())
        val floor=runCatching{client.reserveSnapshot(session,pending,30,ctx.epoch)}.exceptionOrNull()
        checkThat(floor is CloudHttpException && floor.status==426 && cloudErrorCode(floor)=="cloud_format_upgrade_required","joint_floor_missing")
        checkThat(CloudMigrationDiagnostics.classify(floor as CloudHttpException)==CloudMigrationFailure.CLOUD_FORMAT_UPGRADE_REQUIRED,"joint_floor_wrong_state")
        checkThat(cache.loadPendingManifest(ctx.scope)!!.second.contentEquals(pending.encryptedManifest),"joint_floor_lost_pending")
        checkThat(client.snapshot(session,snapshotId).head.id==snapshotId,"joint_floor_blocked_read")
        events.put(JSONObject().put("step","floor_426_read_and_pending").put("status","PASS"));client.allowR48Writes={true}
        val cancelled=draft();val cancelClient=initiator();cancelClient.create(cancelled);cancelClient.consent(cancelled.plan.getString("planId"),true)
        val cancelledState=cancelClient.cancel(cancelled.plan.getString("planId"));checkThat(cancelledState.getString("state")=="cancelled","joint_cancel_not_terminal")
        checkThat(runCatching{cancelClient.validateAndPublish(cancelled.plan.getString("planId"),CloudMigrationRunBudget(now={System.nanoTime()/1_000_000}))}.isFailure,"joint_cancel_published")
        checkThat(client.snapshot(session,snapshotId).head.id==snapshotId,"joint_cancel_lost_accepted")
        events.put(JSONObject().put("step","cancel").put("status","PASS"))
        val exhausted=draft();exhausted.plan.getJSONObject("budget").put("cloudWriteBytes",1)
        val budgetClient=initiator();budgetClient.create(exhausted);budgetClient.consent(exhausted.plan.getString("planId"),true)
        val budgetStatus=waitFor(budgetClient,exhausted.plan.getString("planId"),setOf("budget_exhausted"))
        checkThat(budgetStatus.getString("state")=="budget_exhausted","joint_budget_not_terminal")
        checkThat(client.snapshot(session,source.manifest.snapshotId).head.id==source.manifest.snapshotId && client.snapshot(session,snapshotId).head.id==snapshotId,"joint_budget_lost_original_or_accepted")
        events.put(JSONObject().put("step","worker_budget").put("status","PASS"))
        val expected=JSONArray()
        for((name,bytes) in originals) {
            val file=File(output,"original-${CloudSnapshotCodec.sha256(name.toByteArray())}.bin");privateWrite(file,bytes)
            expected.put(JSONObject().put("name",name).put("sha256",CloudSnapshotCodec.sha256(bytes)).put("sizeBytes",bytes.size).put("bytes",bytes.size).put("plaintextFile",file.absolutePath))
        }
        val expectedArticles=JSONArray()
        for(entry in entries.filter{it.kind=="article"}) {
            val row=CloudPrivateOverlayArchive.validate(originals.getValue(entry.objectName!!),entry.id).articles.single()
            expectedArticles.put(JSONObject().put("articleId",row.articleId).put("url",row.url).put("title",row.title)
                .put("textSha256",CloudSnapshotCodec.sha256(row.contentText.toByteArray())).put("htmlSha256",row.contentHtml?.let{CloudSnapshotCodec.sha256(it.toByteArray())} ?: JSONObject.NULL))
        }
        val result=JSONObject().put("version",1).put("ready",true).put("localOnly",true).put("runId",env.getString("runId")).put("baseUrl",base)
            .put("sourceSnapshotId",source.manifest.snapshotId).put("acceptedSnapshotId",snapshotId).put("snapshotId",snapshotId).put("jobId",jobId).put("planId",planId).put("planHash",plan.planHash)
            .put("manifestSha256",remote.head.manifestSha256).put("manifestSizeBytes",remote.head.manifestSizeBytes).put("rootFile",requireNotNull(rootPath).absolutePath).put("outputRootFile",rootPath!!.absolutePath).put("outputRootFileSha256",CloudSnapshotCodec.sha256(rootPath!!.readBytes()))
            .put("seedFile",seedFile.absolutePath).put("expectedObjects",expected).put("expectedArticles",expectedArticles)
            .put("expectedBooks",JSONArray().put(JSONObject().put("bookId",bookId).put("fingerprint",fingerprint).put("chapterCount",1).put("packageName",bookName).put("chapterTextSha256",CloudSnapshotCodec.sha256(bookText.toByteArray())))).put("checks",events).put("validation",proof.getJSONObject("validation"))
        privateWrite(File(output,"accepted-manifest.bin"),encrypted)
        val target=File(envPath.parentFile,"r48-phone.json");val temp=File(target.parentFile,target.name+".tmp");temp.writeText(result.toString(2));Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        File(evidence,"requests.txt").writeText(requests.joinToString("\n"))
        println("R48_JOINT_RESULT accepted=true checks=${events.length()} objects=${expected.length()} requests=${requests.size}")
        key.fill(0)
    }
}
