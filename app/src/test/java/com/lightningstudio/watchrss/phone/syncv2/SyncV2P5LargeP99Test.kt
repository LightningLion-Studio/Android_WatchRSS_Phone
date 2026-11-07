package com.lightningstudio.watchrss.phone.syncv2

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Opt-in, serialized real-backend measurement. Paths and sample count come from
 * the environment; an absent fixture skips before credentials, Room or HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2P5LargeP99Test {
    private class Reads {
        var active=false;var queries=0L;var blobs=0L;var strings=0L
        fun reset(){queries=0;blobs=0;strings=0}
        fun wrap(db:SupportSQLiteDatabase):SupportSQLiteDatabase {
            fun c(cursor:android.database.Cursor):android.database.Cursor {
                if(active)queries++
                return object:android.database.CursorWrapper(cursor){
                    override fun getBlob(i:Int)=super.getBlob(i).also{if(active)blobs+=it.size}
                    override fun getString(i:Int)=super.getString(i).also{if(active&&it!=null)strings+=it.toByteArray(Charsets.UTF_8).size}
                }
            }
            return object:SupportSQLiteDatabase by db {
                override fun query(query:String)=c(db.query(query))
                override fun query(query:String,bindArgs:Array<out Any?>)=c(db.query(query,bindArgs))
                override fun query(query:androidx.sqlite.db.SupportSQLiteQuery)=c(db.query(query))
            }
        }
    }
    @Test fun serialLargeLocalAndMergeEvents() {
        val fixture=SyncV2JointEnvironment.directory("WATCHRSS_P5_PHONE_JOINT")
        val evidence=SyncV2JointEnvironment.directory("WRSS_P5_EVIDENCE")
        val samples=(System.getenv("WRSS_P5_LARGE_SAMPLES")?.toIntOrNull()?:1000).also{require(it in 1..1000)}
        val folderCount=(System.getenv("WRSS_P5_LARGE_FOLDERS")?.toIntOrNull()?:12500).also{require(it in 32..12500);if(samples>=1000)require(it==12500)}
        val modes=(System.getenv("WRSS_P5_LARGE_MODES")?.takeIf{it.isNotBlank()}?:"local,merge").split(',').also{require(it.distinct()==it && it.all{m->m in setOf("local","merge")})}
        val owner=SyncV2Wire.parse(File(fixture,".owner.json").readBytes());check(owner.getBoolean("phoneP5Owned")&&owner.getInt("backendPort")==18972)
        val pin=SyncV2Wire.parse(File(evidence,"backend-pin.json").readBytes())
        check(pin.getString("sourceCommit")=="b87fe55aa38fe934dbe46fb61b1f39ab8c3944a9"&&pin.getString("binarySha256")=="ccca16f102198ef1d7b51a6b2599d00986fa384e3d9c963e72111d952d64b117"&&pin.getBoolean("runningExecutableMatches"))
        val phone=SyncV2Wire.parse(File(fixture,"phone.json").readBytes());check(phone.getBoolean("syntheticOnly")&&phone.getString("baseUrl")=="http://127.0.0.1:18972")
        var httpCalls=0L;var httpNanos=0L;var putObjects=0L;var putBytes=0L;var measuring=false
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(30,TimeUnit.SECONDS)
            .addInterceptor{chain->val r=chain.request();val start=System.nanoTime();val out=chain.proceed(r)
                if(measuring){httpCalls++;httpNanos+=System.nanoTime()-start;if(r.method=="PUT"){putObjects++;putBytes+=r.body!!.contentLength()}}
                out}.build()
        val bootstrap=Request.Builder().url("http://127.0.0.1:18972/functions/v1/cloud/bootstrap").post("{}".toRequestBody("application/json".toMediaType()))
        val headers=phone.getJSONObject("headers");for(k in headers.keys())bootstrap.header(k,headers.getString(k))
        val boot=http.newCall(bootstrap.build()).execute().use{check(it.code==200);SyncV2Wire.parse(it.body!!.bytes())}
        val key=SyncV2Codec.unhex(phone.getJSONObject("crypto").getString("contentKeyHex"),32)
        val trust=SyncV2ServiceTrust.fromBootstrap(phone.getString("accountId"),boot.getJSONObject("syncV2"),phone.getJSONObject("servicePin")){error("service_pin_changed")}
        val devices=boot.getJSONArray("devices");val approved=(0 until devices.length()).map{devices.getJSONObject(it)}.filter{it.isNull("revokedAt")&&it.optInt("attestationKeyVersion")==1&&it.getString("attestation")==CloudProtocolV2.attestation(key,phone.getString("userId"),phone.getString("legacyLibraryEpoch"),it.getString("deviceId"),Base64.getDecoder().decode(it.getString("publicKeySpki")))}.map{it.getString("deviceId")}.toSet();check(approved.size==4)
        val session=SyncV2CloudClient.Session(phone.getString("userId"),phone.getString("legacyDeviceId"),phone.getString("legacyLibraryEpoch"),phone.getString("legacyContentGeneration"),SyncV2Codec.scope(phone.getJSONObject("scope")),phone.getString("syncDeviceId"),headers.getString("Authorization").removePrefix("Bearer "),"",headers.getString("x-watchrss-device-authorization"))
        val client=SyncV2CloudClient("http://127.0.0.1:18972/functions/v1/sync/v2/".toHttpUrl(),{session},trust,true,http);client.capabilities()
        val descriptor=client.descriptor();val identity=SyncV2Identity.recover(SyncV2Wire.parse(client.recovery(descriptor.getString("recoveryOid")),8192,true),descriptor.getJSONObject("descriptor"),key,phone.getString("accountId"),phone.getJSONObject("scope"),descriptor.getString("descriptorOid"))
        val sk=phone.getJSONObject("signingKey");val signer=SyncV2Signing.fromPrivate(SyncV2Codec.unhex(sk.getString("privateKeyHex"),32),phone.getString("syncDeviceId"),sk.getString("keyId"))
        val checkStart=System.nanoTime();val checkpoint=client.checkpoint().getJSONObject("body");val at=System.nanoTime();val utc=checkpoint.getLong("issuedAtMillis")+(at-checkStart)/1000000+1;fun now()=utc+(System.nanoTime()-at)/1000000
        val root=java.nio.file.Files.createTempDirectory(fixture.toPath(),"phone-large-p99-").toFile()
        val room=Room.databaseBuilder(org.robolectric.RuntimeEnvironment.getApplication(),PhoneCompanionDatabase::class.java,File(root,"phone.db").absolutePath).allowMainThreadQueries().build()
        val sql=room.openHelper.writableDatabase;val reads=Reads();val projection=SyncV2Projection(reads.wrap(sql),identity,SyncV2Codec.random(32),true).also{it.install()}
        val originals=File(root,"files").also{it.mkdirs()};val sourceReads=SyncV2RecoveryStorage.SourceReads()
        var phase="setup";var eventStart=0L;val marks=JSONObject()
        val actions=SyncV2AccountActions(client,identity,projection,signer,{key},{it in approved},originals,true,{now()},recoveryReads=sourceReads,fault={mark->if(measuring && mark in setOf("before-action-stage","action-staged","publication-attempted","server-confirmed","business-applied","after-account-commit"))marks.put(mark,System.nanoTime()-eventStart)})
        fun drain(){repeat(40){if(!projection.runWindow().paused)return};error("projection_incomplete")}
        val summary=JSONObject().put("backend",pin).put("platform","JVM/native Room sdk35; not a physical phone").put("unchangedFolders",folderCount).put("untouchedArticles",4).put("untouchedBooks",2).put("untouchedNotes",256).put("untouchedRssStates",128).put("requestedPerMode",samples).put("modes",JSONArray(modes)).put("p99Reported",false)
        fun save(){File(evidence,"large-p99-summary.json").writeText(summary.put("phase",phase).toString(2)+"\n")}
        val raw=File(evidence,"large-p99-events.ndjson");check(!raw.exists());val results=mutableMapOf<String,MutableList<JSONObject>>()
        try {
            save();val publisher=SyncV2CloudSync(client,identity,projection,{it in approved},{key},now={now()});assertEquals("empty-account",publisher.readCurrent())
            val schema=javaClass.classLoader!!.getResourceAsStream("syncv2-business-schema.json")!!.bufferedReader().use{JSONObject(it.readText())}.getJSONArray("records")
            fun value(kind:String)=JSONObject((0 until schema.length()).map{schema.getJSONObject(it)}.single{it.getString("kind")==kind}.getJSONObject("value").toString())
            val note=value("note");val noteId=note.getString("noteId");val entity=identity.entity("note",noteId)
            val fs=(0 until folderCount).map{i->value("folder").put("folderId","large-folder-${i.toString().padStart(5,'0')}").put("name","folder-$i")}
            val articles=(0 until 4).map{value("article").put("articleId","large-article-$it").put("rssSourceUrl",JSONObject.NULL)}
            val bookBytes="unchanged original book\n".repeat(10000).toByteArray()
            val books=(0 until 2).map{i->val v=value("book");val instance=(i+1).toString(16).padStart(32,'0');v.put("instanceId",instance).put("bookId",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.lp(*listOf("watchrss/book/instance-id/v1",v.getString("fingerprint"),instance).map(SyncV2Codec::utf8).toTypedArray()))))}
            val notes=(0 until 256).map{value("note").put("noteId","large-untouched-note-$it")}
            val rss=(0 until 128).map{value("rss-state").put("articleIdHash",SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8("large-unrelated-rss-$it"))))}
            val all=fs+articles+books+notes+rss+note
            fun row(v:JSONObject):JSONObject {val kind=when{v.has("noteId")->"note";v.has("folderId")->"folder";v.has("articleId")->"article";v.has("articleIdHash")->"rss-state";else->"book"};val id=SyncV2Business.logicalId(kind,v);val p=SyncV2Business.split(kind,id,identity.entity(kind,id),v,null);return JSONObject().put("meta",p.meta).put("state",p.state).put("value",v)}
            val business=all.map(::row);sql.beginTransaction();try{SyncV2PhoneApply(sql).apply(emptyList(),business);sql.setTransactionSuccessful()}finally{sql.endTransaction()}
            for(a in articles)sql.execSQL("UPDATE phone_articles SET contentText=? WHERE articleId=?",arrayOf("unchanged source article ".repeat(5000),a.getString("articleId")))
            for(b in books){File(originals,"imported_books/${b.getString("bookId")}.jsonl").also{it.parentFile!!.mkdirs();it.writeBytes(bookBytes)};sql.execSQL("UPDATE imported_books SET packageSha256=?,packageBytes=? WHERE bookId=?",arrayOf(SyncV2Codec.hex(SyncV2Codec.sha(bookBytes)),bookBytes.size,b.getString("bookId")))}
            drain();phase="seed-real-control-publication";save()
            // Normal product publisher uploads the seed and verifies real service receipts.
            // Preserve its exact prepared ciphertext for local authentication during setup.
            var seed:SyncV2Projection.WireIndex?=projection.prepareWireIndex(signer,key,1)
            // The large cold seed can span more than one five-minute read window.
            // Pre-fill immutable control bytes with bounded product-client batches;
            // the normal publisher then rechecks have/want and commits the real root.
            fun randomId()=SyncV2Codec.hex(SyncV2Codec.random(16))
            val seedControls=seed!!.objects.entries.filter{it.key.substringBefore(':') in setOf("meta","state","tree")};summary.put("seedControlObjects",seedControls.size)
            for((batchIndex,batch)in seedControls.chunked(64).withIndex()) {
                val window=client.negotiate();val reservation=client.reserve(randomId(),batch.map{it.key to it.value.size}).getString("reservationId")
                val status=client.verifiedHaveWant(window.getString("windowId"),batch.map{it.key to it.value.size},now())
                for((objectId,bytes)in batch) {
                    val prior=status.getValue(objectId);if(prior.getString("status")=="present")continue
                    var offset=prior.getInt("durableOffset")
                    while(offset<bytes.size){val part=bytes.copyOfRange(offset,minOf(offset+65536,bytes.size));val receipt=client.put(objectId,offset,reservation,randomId(),part);offset+=part.size;client.verifyReceipt(receipt.getJSONObject("receipt"),objectId,bytes.size.toLong(),offset.toLong(),now())}
                    val complete=client.complete(objectId,reservation,randomId(),bytes.size);client.verifyReceipt(complete.getJSONObject("receipt"),objectId,bytes.size.toLong(),bytes.size.toLong(),now())
                }
                summary.put("seedControlsReady",minOf(seedControls.size,(batchIndex+1)*64));if(batchIndex%10==0)save()
            }
            var response=publisher.publishIndex(signer,key,1);val firstOid=response.getJSONObject("account").getString("commitOid")
            val firstHeader=SyncV2Wire.parse(client.raw(firstOid),262144,true);check(firstHeader.getJSONObject("body").getString("treeRoot")==seed!!.tree.root)
            val setupHeaders=mutableListOf(firstHeader)
            // Retire the seed with an identical-tree normal commit, then prepare and
            // publish an actual service-validated rollback. These setup headers use
            // the same frozen signer/ref-update/client methods as product actions.
            // No synthetic checkpoint or private pendingChoice flag is installed.
            fun setupCommit(kind:String,target:String?):JSONObject {
                val previous=setupHeaders.last().getJSONObject("body");val old=response.getJSONObject("account");val own=response.getJSONObject("device")
                val body=JSONObject(previous.toString()).put("deviceSequence",SyncV2Codec.next(previous.getString("deviceSequence"))).put("previousOwnCommit",own.getString("commitOid"))
                    .put("accountParent",old.getString("commitOid")).put("createdAtMillis",now()).put("mergedParents",JSONArray(if(target!=null&&target!=own.getString("commitOid"))listOf(target)else emptyList<String>()))
                    .put("rollbackGeneration",if(kind=="rollback")SyncV2Codec.next(old.getString("rollbackGeneration"))else old.getString("rollbackGeneration"))
                    .put("operation",JSONObject().put("kind",kind).put("rollbackTarget",target?:JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
                val signed=signer.commit(body);val objectId=SyncV2Codec.oid("commit",body);val bytes=SyncV2Codec.utf8(SyncV2Codec.canonical(signed));val reservation=client.reserve(randomId(),listOf(objectId to bytes.size)).getString("reservationId")
                val status=client.verifiedHaveWant(client.negotiate().getString("windowId"),listOf(objectId to bytes.size),now()).getValue(objectId);assertEquals(0,status.getInt("durableOffset"))
                val uploaded=client.put(objectId,0,reservation,randomId(),bytes);client.verifyReceipt(uploaded.getJSONObject("receipt"),objectId,bytes.size.toLong(),bytes.size.toLong(),now())
                client.verifyReceipt(client.complete(objectId,reservation,randomId(),bytes.size).getJSONObject("receipt"),objectId,bytes.size.toLong(),bytes.size.toLong(),now())
                val requestId=randomId();response=client.publish(JSONObject().put("account",SyncV2CloudClient.refUpdate(signer,identity.scope,"refs/account/current",old.getString("commitOid"),old.getString("revision"),objectId,requestId,now()))
                    .put("device",SyncV2CloudClient.refUpdate(signer,identity.scope,"refs/devices/${signer.deviceId}/index",own.getString("commitOid"),own.getString("revision"),objectId,requestId,now())).put("relayGrant",JSONObject.NULL))
                assertEquals(objectId,response.getJSONObject("account").getString("commitOid"));setupHeaders.add(signed);return signed
            }
            setupCommit("normal",null)
            val old=response.getJSONObject("account");val lease=client.prepareHistory(JSONObject().put("requestId",randomId()).put("targetCommitOid",firstOid).put("expectedAccountRevision",old.getString("revision")).put("expectedRollbackGeneration",old.getString("rollbackGeneration")))
            assertEquals(seed!!.tree.root,lease.getString("treeRoot"));val header=setupCommit("rollback",firstOid)
            val account=response.getJSONObject("account");val oid=account.getString("commitOid");assertEquals("1",account.getString("rollbackGeneration"));summary.put("rollbackGeneration","1").put("setupCommitCount",3)
            val authors=client.authors(identity){it in approved};val saved=projection.state().body;val bindings=saved.getJSONArray("signingBindings")
            for(i in 0 until bindings.length())authors.accept(bindings.getJSONObject(i),saved.getString("revocationRevision"))
            val refs=seed!!.refs.associate{r->val parent=r.getJSONObject("body").getString("stateOid");val ref=SyncV2Codec.oid("state-refs",r);parent to JSONObject().put("scope",identity.scope).put("parentOid",parent).put("refsOid",ref).put("authorDeviceId",signer.deviceId).put("keyId",signer.keyId).put("totalBytes",seed!!.objects.getValue(ref).size)}
            val verified=SyncV2ControlClosure(identity,authors,{key}).verify(header,{seed!!.objects.getValue(it)},{refs.getValue(it)})
            projection.installCloudIndex(JSONObject().put("oid",oid).put("revision",account.getString("revision")).put("generation",account.getString("rollbackGeneration")),setupHeaders.reversed(),verified,authors::header,response.getString("serverRevision"),{h->SyncV2Wire.parse(client.raw(h),262144,true)})
            seed=null
            fun pending(cursor:String){val local=projection.accountLocalRows(setOf(entity)).single().getJSONObject("value");assertEquals("pendingChoice",projection.receiveLegacy(SyncV2Projection.LegacyInput("synthetic-large-measurement",14,cursor,JSONObject().put(entity,local)),null,true))}
            // Establish the authenticated business baseline and ACK through the actual
            // product choice once, outside the measured steady-state events.
            phase="baseline-choice";save();pending("warmup");actions.choose(actions.preview(),"local");drain()
            val folderProof=sql.query("SELECT entity_key,source_hash,meta,state FROM syncv2_candidates WHERE kind='folder' ORDER BY entity_key").use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1)+SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(2)))+SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(3))))}}
            fun folderWires()=projection.cloudRows().filter{it.getJSONObject("meta").getString("kind")=="folder"}.associate{it.getJSONObject("meta").getString("entityKey") to SyncV2Codec.hash(it.getJSONObject("row"))}
            val wireProof=folderWires()
            sql.execSQL("CREATE TABLE p99_folder_writes(n INTEGER)");sql.execSQL("CREATE TRIGGER p99_unchanged_folders AFTER UPDATE ON note_folders BEGIN INSERT INTO p99_folder_writes VALUES(1); END")
            raw.bufferedWriter().use{out->for(mode in modes){val values=mutableListOf<JSONObject>();results[mode]=values
                repeat(samples){index->
                    phase="$mode-${index+1}";save();reads.reset();sourceReads.reset();httpCalls=0;httpNanos=0;putObjects=0;putBytes=0
                    for(k in marks.keys().asSequence().toList())marks.remove(k)
                    reads.active=true;val fullStart=System.nanoTime();val text="large-$mode-event-${index+1}\n";sql.execSQL("UPDATE notes SET markdown=?,plainText=?,contentHash=?,updatedAt=? WHERE noteId=?",arrayOf(text,text,com.lightningstudio.watchrss.phone.data.note.MarkdownNoteCodec.sha256(text),now(),noteId));drain();val edited=System.nanoTime()
                    pending("$mode-${index+1}");val received=System.nanoTime();val preBlobs=reads.blobs;val preStrings=reads.strings;val preQueries=reads.queries;measuring=true;eventStart=System.nanoTime()
                    val preview=actions.preview();val previewed=System.nanoTime();assertTrue(preview.reason,preview.canMerge)
                    val result=actions.choose(preview,mode);val chosen=System.nanoTime();measuring=false;val choiceBlobs=reads.blobs-preBlobs;val choiceStrings=reads.strings-preStrings;val choiceQueries=reads.queries-preQueries
                    assertTrue(result.getBoolean("localCommitted"));assertFalse(result.getBoolean("appliedAckPending"));assertTrue(projection.state().body.isNull("pendingChoice"))
                    val post=projection.runWindow();assertFalse(post.paused);assertEquals(0,post.rows);assertEquals(0,post.tailEntries);val end=System.nanoTime();reads.active=false
                    assertEquals(0,sourceReads.databaseBytes);assertEquals(0,sourceReads.fileBytes);assertTrue("bounded action BLOB returns $choiceBlobs",choiceBlobs<4L*1024*1024);assertTrue(putObjects<=8)
                    val event=JSONObject().put("choice",mode).put("event",index+1).put("previewNs",previewed-eventStart).put("chooseNs",chosen-previewed).put("previewChooseNs",chosen-eventStart).put("editProjectionNs",edited-fullStart).put("receiveLegacyNs",received-edited).put("postProjectionNs",end-chosen).put("fullCycleNs",end-fullStart).put("postProjectionRows",post.rows).put("postProjectionTail",post.tailEntries).put("appliedAckPending",false).put("marksNs",JSONObject(marks.toString())).put("sqlQueries",choiceQueries).put("blobBytes",choiceBlobs).put("stringUtf8Bytes",choiceStrings).put("fullCycleSqlQueries",reads.queries).put("fullCycleBlobBytes",reads.blobs).put("fullCycleStringUtf8Bytes",reads.strings).put("sourceReads",sourceReads.json()).put("httpCalls",httpCalls).put("httpHeadersNs",httpNanos).put("uploadedObjects",putObjects).put("uploadedBytes",putBytes).put("accountRevision",projection.state().body.getJSONObject("account").getString("revision"))
                    values.add(event);out.write(event.toString());out.newLine();out.flush()
                    summary.put("completed-$mode",index+1);if((index+1)%10==0)save()
                }
            }}
            val after=sql.query("SELECT entity_key,source_hash,meta,state FROM syncv2_candidates WHERE kind='folder' ORDER BY entity_key").use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1)+SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(2)))+SyncV2Codec.hex(SyncV2Codec.sha(c.getBlob(3))))}}
            assertEquals(folderProof,after);assertEquals(wireProof,folderWires());assertEquals(folderCount,wireProof.size);assertEquals(0,sql.query("SELECT COUNT(*) FROM p99_folder_writes").use{it.moveToFirst();it.getInt(0)})
            val stats=JSONObject();for((mode,values)in results){val s=JSONObject().put("events",values.size);for(field in listOf("previewNs","chooseNs","previewChooseNs","editProjectionNs","receiveLegacyNs","postProjectionNs","fullCycleNs")){val sorted=values.map{it.getLong(field)}.sorted();s.put(field,JSONObject().put("min",sorted.first()).put("p50",sorted[(sorted.size+1)/2-1]).put("max",sorted.last()).also{if(sorted.size>=1000)it.put("p99",sorted[(sorted.size*99+99)/100-1])})};stats.put(mode,s)}
            summary.put("results",stats).put("p99Reported",results.values.all{it.size>=1000}).put("changedFolderCandidates",0).put("businessFolderUpdates",0).put("status","passed");phase="complete";save()
        }catch(e:Throwable){summary.put("status","failed").put("error",if(e is SyncV2CloudError)e.code else e.message?.takeIf{Regex("[a-z][a-z0-9_]{0,63}").matches(it)}?:e.javaClass.simpleName).put("frames",JSONArray(e.stackTrace.filter{it.className.startsWith("com.lightningstudio.watchrss.phone.syncv2")}.take(8).map{"${it.className.substringAfterLast('.')}::${it.methodName}:${it.lineNumber}"}));save();throw AssertionError("large measurement failed at $phase; see safe summary")}
        finally{room.close();key.fill(0);root.deleteRecursively()}
    }
}
