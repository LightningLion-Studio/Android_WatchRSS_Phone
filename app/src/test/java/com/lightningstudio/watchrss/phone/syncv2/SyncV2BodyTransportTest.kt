package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SyncV2BodyTransportTest {
    private class Backend(val f:SyncV2P5Fixture) {
        val descriptors=linkedMapOf<String,JSONObject>();val objects=mutableMapOf<String,ByteArray>();val ready=mutableSetOf<String>()
        val puts=mutableListOf<Pair<String,Int>>();val reads=mutableListOf<Int>();var writeError:String?=null;var status=503;var delay:Long?=null;var retryable:Boolean?=null
        var losePart=false;var loseRead=false;var bodyRequests=0;var reserveCalls=0;var beforeRead:(()->Unit)?=null
        init {val previous=f.f.route;f.f.route={r->val url=r.requestUrl!!;val p=url.encodedPath.removePrefix("/functions/v1/cloud/bodies/v1/")
            if(!url.encodedPath.startsWith("/functions/v1/cloud/bodies/v1/"))previous!!.invoke(r)else {
                bodyRequests++
                fun json(v:JSONObject):MockResponse=f.f.response(JSONObject().put("version",1).put("purpose",SyncV2BodyCodec.PURPOSE).put("scope",f.identity.scope).also{o->for(k in v.keys())o.put(k,v.get(k))})
                if(r.method!="GET" && writeError!=null) {f.f.response(JSONObject().put("error",JSONObject().put("code",writeError).put("retryable",retryable?:(status==429||status==507)).put("retryAfterMillis",delay?:JSONObject.NULL).put("details",JSONObject()))).setResponseCode(status)}
                else if(p=="reserve") {
                    reserveCalls++;assertEquals(null,url.queryParameter("repoId"));assertEquals("old-epoch",r.getHeader("x-watchrss-library-epoch"));val d=JSONObject(r.body.readUtf8());val b=SyncV2BodyCodec.validate(d,f.identity.scope);val id=b.getString("carrierId");descriptors[id]=d
                    json(JSONObject().put("carrierId",id).put("state",if(id in ready)"ready"else"reserved").put("expiresAtMillis",600000).put("objects",JSONArray(SyncV2BodyCodec.objects(b).map{o->JSONObject(o.toString()).put("offset",objects[o.getString("sha256")]?.size?:0)})))
                }else if(r.method=="PUT") {
                    val parts=p.split('/');val hash=parts[3];val at=parts[5].toInt();val raw=r.body.readByteArray()
                    assertEquals(f.identity.repoId,r.getHeader("x-watchrss-sync-repo-id"));assertTrue(raw.size in 1..65536);assertEquals(SyncV2BodyCodec.hash(raw),r.getHeader("x-watchrss-sync-part-sha256"))
                    assertEquals(objects[hash]?.size?:0,at);objects[hash]=(objects[hash]?:byteArrayOf())+raw;puts.add(hash to at)
                    if(losePart){losePart=false;MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}else json(JSONObject())
                }else if(p.endsWith("/complete")) {
                    val id=p.split('/')[1];for(o in SyncV2BodyCodec.objects(descriptors.getValue(id).getJSONObject("body"))){assertEquals(o.getInt("sizeBytes"),objects[o.getString("sha256")]!!.size);assertEquals(o.getString("sha256"),SyncV2BodyCodec.hash(objects[o.getString("sha256")]!!))};ready.add(id)
                    json(JSONObject().put("carrierId",id).put("state","ready"))
                }else if(p=="carriers") {
                    assertEquals(f.identity.repoId,url.queryParameter("repoId"));val a=JSONArray()
                    for(id in ready.sorted()) {val d=descriptors.getValue(id);if(d.getJSONObject("body").getString("entityKey")==url.queryParameter("entityKey")&&d.getJSONObject("body").getString("bodyRevision")==url.queryParameter("bodyRevision"))a.put(JSONObject().put("carrierId",id).put("descriptor",d))}
                    json(JSONObject().put("carriers",a).put("nextAfter",JSONObject.NULL))
                }else if("/objects/" in p) {
                    val hash=p.split('/')[3];val raw=objects.getValue(hash);val at=url.queryParameter("offset")!!.toInt();reads.add(at);beforeRead?.invoke()
                    if(loseRead&&at>0){loseRead=false;MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}else MockResponse().setBody(Buffer().write(raw.copyOfRange(at,minOf(raw.size,at+65536)))).setHeader("x-watchrss-body-total-bytes",raw.size).setHeader("x-watchrss-body-offset",at)
                }else error("unexpected body route $p")
            }
        }}
    }
    private fun keys(f:SyncV2P5Fixture):SyncV2ContentKeys {
        val context=RuntimeEnvironment.getApplication() as Context
        val manager=CloudKeyManager(null,preferencesOverride=context.getSharedPreferences("body-${System.nanoTime()}",Context.MODE_PRIVATE)).also{it.bindBackend("http://synthetic");it.saveAccountKey("u",1,f.key,true)}
        return SyncV2ContentKeys.authenticated(manager,"u"){}
    }
    @Test fun lostUploadAndDownloadResponsesResumeFromDurableOffsetsAcrossReopen() {
        SyncV2P5Fixture().use{f->val backend=Backend(f);val client=f.f.client();client.capabilities();val http=SyncV2BodyHttp(client){}
            val raw=ByteArray(400000){(it%251).toByte()};val entity=f.identity.entity("asset","resume.png");val binding=SyncV2BodyCodec.Binding("asset","resume.png",entity,SyncV2BodyCodec.revision("asset",entity,SyncV2BodyCodec.hash(raw)))
            val anchor=JSONObject().put("commitOid","commit:sha256:"+"ab".repeat(32)).put("revision","1").put("rollbackGeneration","0")
            val p=SyncV2BodyCodec.prepare(f.identity.scope,anchor,binding,f.key,1,f.f.signer,raw);val cache=File(f.root,"upload")
            for((h,b)in p.objects)SyncV2BodyHttp.atomic(File(cache,h),b)
            backend.losePart=true
            try{http.upload(p.descriptor,cache);fail("lost response succeeded")}catch(_:java.io.IOException){}
            val first=backend.puts.first();val next=f.f.client();next.capabilities();val reopened=SyncV2BodyHttp(next){};reopened.upload(p.descriptor,cache)
            assertEquals(1,backend.puts.count{it==first});assertEquals(1,backend.ready.size)
            val count=backend.puts.size;reopened.upload(p.descriptor,cache);assertEquals(count,backend.puts.size)
            val body=p.descriptor.getJSONObject("body");val objectRef=body.getJSONArray("chunks").getJSONObject(0);val dir=File(f.root,"download")
            backend.loseRead=true;try{reopened.download(body.getString("carrierId"),objectRef.getString("sha256"),objectRef.getInt("sizeBytes"),dir);fail("download succeeded")}catch(_:java.io.IOException){}
            assertEquals(65536,File(dir,objectRef.getString("sha256")+".part").length().toInt());val before=backend.reads.size
            val got=SyncV2BodyHttp(client){}.download(body.getString("carrierId"),objectRef.getString("sha256"),objectRef.getInt("sizeBytes"),dir)
            assertEquals(65536,backend.reads[before]);assertEquals(objectRef.getString("sha256"),SyncV2BodyCodec.hash(got))
        }
    }
    private fun renamedBody(kind:String,choice:Boolean) {
        SyncV2P5Fixture().use{f->f.start();val backend=Backend(f);val client=f.f.client()
            val text="body original";val fingerprint=com.lightningstudio.watchrss.phone.data.book.BookSync.fingerprint(listOf(com.lightningstudio.watchrss.phone.data.book.BookSync.chapterHash(1,"chapter",text)))
            val id=if(kind=="book")com.lightningstudio.watchrss.phone.data.book.BookSync.id(fingerprint)else"rename.png"
            val raw=if(kind=="asset")text.toByteArray()else (JSONObject().put("format","watchrss-book").put("version",1).put("title","original title").put("chapterCount",1).put("sourceUrl","https://watchrss.local/import-txt-novel/review").put("kind","txt").toString()+"\n"+JSONObject().put("chapterIndex",1).put("title","chapter").put("contentText",text).put("contentHtml",JSONObject.NULL).put("url","https://watchrss.local/import-txt-novel/review/chapter/1").toString()+"\n").toByteArray()
            val value=JSONObject().put("bookId",id).put("fingerprint",fingerprint).put("title","original title").put("chapterCount",1).put("deletedChapters",JSONArray()).put("deletionClock",0).put("deleted",false).put("deletedAt",0).put("updatedAt",1).put("sourceDeviceId","synthetic-phone").put("sourceUrl","https://watchrss.local/import-txt-novel/review").put("kind","txt")
            val apply=SyncV2PhoneApply(f.sql)
            if(kind=="book")apply.upsert("imported_books",listOf("bookId"),JSONObject().put("bookId",id).put("stateJson",value.toString()))
            else apply.upsert("note_assets",listOf("assetId"),JSONObject().put("assetId","private-rename").put("storageKey",id).put("mimeType","image/png").put("displayName","original title").put("createdAt",1).put("deleted",false).put("deletedAt",0))
            f.drain();f.projection.prepareWireIndex(f.f.signer,f.key,1).close()
            val entity=f.identity.entity(kind,id);val rev=SyncV2BodyCodec.revision(kind,entity,SyncV2BodyCodec.hash(raw))
            f.projection.bindArticleRevision(f.projection.wireContentRow(entity)!!,rev,independent=true)
            val cloud=SyncV2CloudSync(client,f.identity,f.projection,{true},{f.key},{f.f.now});cloud.publishIndex(f.f.signer,f.key,1);f.actions().refresh()
            val old=f.projection.cloudRows(setOf(entity)).single().getJSONObject("row")
            val account=f.projection.state().body.getJSONObject("account");val anchor=JSONObject().put("commitOid",account.get("oid")).put("revision",account.get("revision")).put("rollbackGeneration",account.get("generation"))
            val prepared=SyncV2BodyCodec.prepare(f.identity.scope,anchor,SyncV2BodyCodec.Binding(kind,id,entity,rev),f.key,1,f.f.signer,raw)
            val upload=File(f.root,"fixture-upload");for((h,b)in prepared.objects)SyncV2BodyHttp.atomic(File(upload,h),b)
            SyncV2BodyHttp(client){}.upload(prepared.descriptor,upload)
            val repository=SyncV2BodyRepository(f.sql,f.root);assertFalse(repository.file(kind,id).exists())
            if(kind=="book")f.sql.execSQL("UPDATE imported_books SET stateJson=? WHERE bookId=?",arrayOf(value.put("title","offline renamed").put("updatedAt",2).toString(),id))
            else f.sql.execSQL("UPDATE note_assets SET displayName='offline renamed',mimeType='application/octet-stream',createdAt=2 WHERE storageKey=?",arrayOf(id))
            f.drain()
            if(choice){f.projection.requestConcurrentChoice();val actions=f.actions();actions.choose(actions.preview(),"local")}
            else cloud.publishIndex(f.f.signer,f.key,1)
            f.actions().refresh()
            val current=f.projection.cloudRows(setOf(entity)).single().getJSONObject("row")
            for(field in SyncV2BodyIdentity.fields)assertEquals("$kind/$choice/$field",old.get(field),current.get(field))
            assertFalse(repository.file(kind,id).exists());val ack=f.ackCalls
            keys(f).use{keys->val channel=SyncV2BodyChannel(f.projection,f.identity,keys,f.f.signer,client,repository,File(f.root,"receiver-cache"),SyncV2Lifetime{true},{true},{SyncV2TransportSignals(wifi=true,internet=true)})
                assertEquals(emptyList<String>(),channel.sync().errors)}
            assertArrayEquals(raw,repository.file(kind,id).readBytes());assertEquals(ack,f.ackCalls)
            val title=if(kind=="book")f.sql.query("SELECT stateJson FROM imported_books WHERE bookId=?",arrayOf(id)).use{it.moveToFirst();JSONObject(it.getString(0)).getString("title")}else f.sql.query("SELECT displayName FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst();it.getString(0)}
            assertEquals("offline renamed",title);assertEquals(1,backend.ready.size)
        }
    }
    @Test fun metadataOnlyBookRenameRetainsDescriptorUntilLaterDownload()=renamedBody("book",false)
    @Test fun metadataOnlyAssetRenameRetainsDescriptorUntilLaterDownload()=renamedBody("asset",false)
    @Test fun localChoiceBookRenameRetainsDescriptorUntilLaterDownload()=renamedBody("book",true)
    @Test fun localChoiceAssetRenameRetainsDescriptorUntilLaterDownload()=renamedBody("asset",true)
    @Test fun finalInstallRacePreservesConcurrentLocalBytesAndRecordsConflict() {
        for(present in listOf(false,true))SyncV2P5Fixture().use{f->
            val original="local original".toByteArray();val incoming="remote original".toByteArray()
            val target=File(f.root,"notes/assets/race.png");if(present){target.parentFile!!.mkdirs();target.writeBytes(incoming)}
            val repository=SyncV2BodyRepository(f.sql,f.root){file->com.lightningstudio.watchrss.phone.data.local.CloudExportFileWitness.writeBytes(file,original)}
            f.sql.beginTransaction();try {
                try{repository.install("asset","race.png",incoming);fail("replaced concurrent original")}catch(e:IllegalStateException){assertEquals("body_revision_changed",e.message)}
            }finally{f.sql.endTransaction()}
            assertArrayEquals(original,target.readBytes())
            val conflict=JSONObject(com.lightningstudio.watchrss.phone.data.local.ConditionalBodyFile.conflictRecord(target).readText())
            assertEquals(SyncV2BodyCodec.hash(original),conflict.getString("actualSha256"));assertEquals(1,conflict.getInt("attempts"))
        }
    }
    @Test fun actualSignedAccountBodyGateRetryAndAtomicReceiveNeverAcknowledgeBody() {
        SyncV2P5Fixture().use{f->f.start();val backend=Backend(f);val client=f.f.client();val raw="actual original é😀".repeat(18000).toByteArray();val id="phone.png"
            SyncV2PhoneApply(f.sql).upsert("note_assets",listOf("assetId"),JSONObject().put("assetId","private-uuid").put("storageKey",id).put("mimeType","image/png").put("displayName",id).put("createdAt",1).put("deleted",false).put("deletedAt",0))
            val repository=SyncV2BodyRepository(f.sql,f.root);val original=repository.file("asset",id);original.parentFile!!.mkdirs();original.writeBytes(raw);f.drain()
            keys(f).use{keys->val life=SyncV2Lifetime{true};var online=false;var clock=1000000L
                fun channel()=SyncV2BodyChannel(f.projection,f.identity,keys,f.f.signer,client,repository,File(f.root,"bodies"),life,{true},{SyncV2TransportSignals(wifi=online,internet=online)},{clock})
                var channel=channel();f.projection.prepareWireIndex(f.f.signer,f.key,1).close();channel.bindPrepared()
                SyncV2CloudSync(client,f.identity,f.projection,{true},{f.key},{f.f.now}).publishIndex(f.f.signer,f.key,1);f.actions().refresh()
                val ack=f.ackCalls;assertEquals(listOf("network_unavailable"),channel.sync().errors);assertEquals(0,backend.bodyRequests)
                online=true;backend.writeError="body_channel_write_disabled";assertEquals(listOf("body_channel_write_disabled"),channel.sync().errors);assertEquals(0,backend.objects.size);assertArrayEquals(raw,original.readBytes());assertEquals(ack,f.ackCalls)
                // Metadata remains independently publishable while the body gate is closed.
                f.edit("metadata continues\n");SyncV2CloudSync(client,f.identity,f.projection,{true},{f.key},{f.f.now}).publishIndex(f.f.signer,f.key,1);f.actions().refresh();assertEquals("metadata continues\n",f.text())
                backend.writeError="rate_limited";backend.status=429;backend.delay=1000
                assertEquals(listOf("rate_limited"),channel.sync().errors);val requests=backend.bodyRequests;channel=channel();assertEquals(listOf("rate_limited"),channel.sync().errors);assertEquals(requests,backend.bodyRequests)
                clock+=1001;backend.writeError="quota_exceeded";backend.status=507;backend.delay=2000
                assertEquals(listOf("quota_exceeded"),channel.sync().errors);val quotaRequests=backend.bodyRequests;channel=channel();assertEquals(listOf("quota_exceeded"),channel.sync().errors);assertEquals(quotaRequests,backend.bodyRequests)
                // Frozen backend 507 shape: no Retry-After and retryable=false.
                clock+=2001;backend.delay=null;backend.retryable=false
                assertEquals(listOf("quota_exceeded"),channel.sync().errors)
                val noDelayRequests=backend.bodyRequests
                channel=channel();assertEquals(listOf("quota_exceeded"),channel.sync().errors);assertEquals(noDelayRequests,backend.bodyRequests)
                val persisted=JSONObject(File(f.root,"bodies/retry.json").readText());assertEquals(clock+60000,persisted.getLong("until"))
                clock+=60001;backend.writeError=null;assertEquals(emptyList<String>(),channel.sync().errors);assertEquals(1,backend.ready.size)
                // A separate local body directory simulates the receiving phone, with the same authenticated Room metadata.
                val receiverRoot=File(f.root,"receiver");val receiver=SyncV2BodyRepository(f.sql,receiverRoot)
                val remote=SyncV2BodyChannel(f.projection,f.identity,keys,f.f.signer,client,receiver,File(f.root,"receiver-cache"),life,{true},{SyncV2TransportSignals(wifi=true,internet=true)},{clock})
                val beforeAck=f.ackCalls;val beforeRevision=f.projection.state().revision;val originalClock=f.sql.query("SELECT createdAt FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst();it.getLong(0)}
                val carrierId=backend.ready.single();val good=backend.descriptors.getValue(carrierId)
                backend.descriptors[carrierId]=JSONObject(good.toString()).put("signature",SyncV2Codec.b64(ByteArray(64){1}))
                assertEquals(listOf("signature_invalid"),remote.sync().errors);assertFalse(receiver.file("asset",id).exists());assertArrayEquals(raw,original.readBytes());assertEquals(beforeAck,f.ackCalls)
                backend.descriptors[carrierId]=f.f.signer.signed(SyncV2BodyCodec.DOMAIN,JSONObject(good.getJSONObject("body").toString()).put("chunks",JSONArray()))
                assertEquals(listOf("closure_incomplete"),remote.sync().errors);assertFalse(receiver.file("asset",id).exists());assertArrayEquals(raw,original.readBytes())
                backend.descriptors[carrierId]=good
                val hash=good.getJSONObject("body").getJSONArray("chunks").getJSONObject(0).getString("sha256");val saved=backend.objects.getValue(hash)
                backend.objects[hash]=saved.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
                assertEquals(listOf("hash_mismatch"),remote.sync().errors);assertFalse(receiver.file("asset",id).exists());assertArrayEquals(raw,original.readBytes());assertEquals(beforeAck,f.ackCalls)
                backend.objects[hash]=saved
                assertEquals(emptyList<String>(),remote.sync().errors);assertArrayEquals(raw,receiver.file("asset",id).readBytes());assertEquals(beforeAck,f.ackCalls);assertEquals(beforeRevision,f.projection.state().revision)
                assertEquals(originalClock,f.sql.query("SELECT createdAt FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst();it.getLong(0)})
                assertEquals("private-uuid",f.sql.query("SELECT assetId FROM note_assets WHERE storageKey=?",arrayOf(id)).use{it.moveToFirst();it.getString(0)})
            }
        }
    }
}
