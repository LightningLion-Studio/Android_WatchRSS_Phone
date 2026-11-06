package com.lightningstudio.watchrss.phone.syncv2

import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SyncV2P3ReviewRecoveryTest {
    @get:Rule val temp=TemporaryFolder()
    private val identity=SyncV2Fixtures.identity();private val key=ByteArray(32){7}
    private fun store(dir:File,fault:(String)->Unit={})=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey,fault=fault)
    private fun worker(f:SyncV2P3Fixture,s:SyncV2ContentStore)=SyncV2ContentWorker(f.client(),identity,s,
        {SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now},reserveRequestId={"55".repeat(16)})
    private fun code(expected:String,fn:()->Unit){try{fn();fail("accepted invalid bytes")}catch(e:IllegalStateException){assertEquals(expected,e.message)}}
    private fun error(f:SyncV2P3Fixture)=f.response(JSONObject().put("error",JSONObject().put("code","reservation_expired").put("retryable",true).put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(410)
    @Test fun r01BadRangeIsQuarantinedAndReopenedWorkerFetchesCorrectBytesWithoutTouchingOtherBlocks() {
        SyncV2P3Fixture().use { f ->
            val dir=temp.newFolder();val s=store(dir);val obj=SyncV2Envelope.seal(identity,key,1,"blob",ByteArray(1000){1})
            val good=SyncV2Envelope.seal(identity,key,1,"blob",byteArrayOf(2));s.putObject(good.oid,good.bytes)
            val original=File(temp.newFolder(),"original").also{it.writeText("original business body")};f.objects[obj.oid]=obj.bytes
            f.route={r->if(r.method=="GET" && r.getHeader("Range")!=null) {
                val wrong=obj.bytes.copyOf();wrong[wrong.lastIndex]=(wrong.last().toInt() xor 1).toByte()
                MockResponse().setResponseCode(206).setHeader("ETag",obj.oid).setHeader("Content-Range","bytes 0-${wrong.lastIndex}/${wrong.size}").setBody(Buffer().write(wrong))
            }else null}
            code("hash_mismatch"){worker(f,s).download(listOf(obj.oid to obj.bytes.size))}
            assertEquals(0,s.prefix(obj.oid,obj.bytes.size));assertFalse(s.load("quarantine/${obj.oid}")!!.getBoolean("pending"))
            f.route=null;val reopened=store(dir)
            assertEquals("complete",worker(f,reopened).download(listOf(obj.oid to obj.bytes.size)).status)
            assertArrayEquals(ByteArray(1000){1},worker(f,reopened).authenticate(obj.oid){key})
            assertArrayEquals(good.bytes,reopened.objectBytes(good.oid));assertEquals("original business body",original.readText())
            assertTrue(dir.listFiles()!!.any{it.name.endsWith(".part.bad")})
        }
    }
    @Test fun r01AeadFailureAndCrashDuringQuarantineRecoverWithoutManualCacheReset() {
        SyncV2P3Fixture().use { f ->
            val dir=temp.newFolder();val obj=SyncV2Envelope.seal(identity,key,1,"blob",byteArrayOf(1));f.objects[obj.oid]=obj.bytes
            val s=store(dir);assertEquals("complete",worker(f,s).download(listOf(obj.oid to obj.bytes.size)).status)
            code("aead_invalid"){worker(f,s).authenticate(obj.oid){ByteArray(32)}}
            val reopened=store(dir);assertNull(reopened.load("download/${obj.oid}"));assertEquals(0,reopened.prefix(obj.oid,obj.bytes.size))
            assertEquals("complete",worker(f,reopened).download(listOf(obj.oid to obj.bytes.size)).status)
            assertArrayEquals(byteArrayOf(1),worker(f,reopened).authenticate(obj.oid){key})
            var once=true;val crashing=store(dir){if(it=="quarantine" && once){once=false;throw java.io.IOException("crash")}}
            try{crashing.quarantineDownload(obj.oid,"hash_mismatch");fail()}catch(_:java.io.IOException){}
            val final=store(dir);assertEquals(0,final.prefix(obj.oid,obj.bytes.size));assertNull(final.load("download/${obj.oid}"))
            assertEquals("complete",worker(f,final).download(listOf(obj.oid to obj.bytes.size)).status)
            assertArrayEquals(byteArrayOf(1),worker(f,final).authenticate(obj.oid){key})
        }
    }
    @Test fun r02ExpiredIdempotentPartialReservationRenewsSameOidAndKeepsCheckpointAndReplayLedger() {
        SyncV2P3Fixture().use { f ->
            f.replayReserve=true;val dir=temp.newFolder();val obj=SyncV2Envelope.seal(identity,key,1,"blob",ByteArray(262144){1})
            val s=store(dir);s.putObject(obj.oid,obj.bytes);f.objects[obj.oid]=obj.bytes
            assertEquals("budget_paused",worker(f,s).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(bytes=65536)).status)
            f.now+=600001
            assertEquals("complete",worker(f,store(dir)).upload(listOf(obj.oid)).status)
            assertEquals(listOf(0,65536,131072,196608,262144),f.putOffsets);assertTrue(f.reserveIds.distinct().size>=2)
            assertEquals(0,store(dir).load("upload/${obj.oid}")!!.getInt("replayedBytes"));assertArrayEquals(obj.bytes,store(dir).objectBytes(obj.oid))
        }
    }
    @Test fun r02ExpiryAtPutAndCompleteRenewsInProductPathWithoutReplayingConfirmedFrames() {
        SyncV2P3Fixture().use { f ->
            val s=store(temp.newFolder());val obj=SyncV2Envelope.seal(identity,key,1,"blob",ByteArray(100000){1});s.putObject(obj.oid,obj.bytes);f.objects[obj.oid]=obj.bytes
            var putExpired=false;var completeExpired=false;var requireRenewal=false;var previousReserves=0
            f.route={r->val path=r.requestUrl!!.encodedPath
                when {
                    path.endsWith("/parts/65536") && !putExpired->{putExpired=true;error(f)}
                    path.endsWith("/complete") && !completeExpired->{completeExpired=true;requireRenewal=true;previousReserves=f.reserveIds.size;error(f)}
                    path.endsWith("/objects/have-want") && requireRenewal && f.reserveIds.size==previousReserves->error(f)
                    else->null
                }
            }
            assertEquals("complete",worker(f,s).upload(listOf(obj.oid)).status)
            assertTrue(putExpired && completeExpired);assertEquals(listOf(0,65536),f.putOffsets);assertEquals(0,f.completed)
            assertTrue(f.reserveIds.size>=3)
        }
    }
    private inner class DownloadWorld(val f:SyncV2P3Fixture,val count:Int,val bytes:Int=100000) {
        val rows=mutableListOf<JSONObject>();val owners=mutableMapOf<String,Int>();val mappings=mutableMapOf<String,JSONObject>();val available=mutableMapOf<String,JSONObject>()
        var demands=JSONArray();var onRange:(Int,Int)->Unit={_,_->};val seen=mutableListOf<Pair<Int,Int>>()
        init {
            val origin=store(temp.newFolder())
            for(i in 1..count) {
                val ex=SyncV2ContentExport(identity,key,1,origin);val binding=SyncV2ContentExport.Binding(i.toString(16).padStart(64,'0'),"22".repeat(16),ex.authenticationBinding)
                val source=object:SyncV2ContentExport.Source {override val size=bytes.toLong();override val version="v$i";override fun read(offset:Long,maxBytes:Int)=ByteArray(maxBytes){(i+offset+it).toByte()}}
                while(!ex.step(binding,source)){};val d=ex.descriptor(binding)!!;val recipe=d.getJSONObject("recipe");val oid=d.getString("recipeOid");val blocks=recipe.getJSONArray("blocks");val blobs=JSONArray()
                for(n in 0 until blocks.length()){val b=blocks.getJSONObject(n).getString("oid");blobs.put(b);owners[b]=i;f.objects[b]=origin.objectBytes(b);f.prefix[b]=f.objects[b]!!}
                f.objects[oid]=origin.objectBytes(oid);f.prefix[oid]=f.objects[oid]!!
                val refs=SyncV2ContentCodec.refs(identity,f.signer,oid,blobs);val raw=SyncV2Codec.utf8(SyncV2Codec.canonical(refs));val roid=SyncV2Codec.oid("recipe-refs",refs);f.objects[roid]=raw
                mappings[oid]=f.dto().put("parentOid",oid).put("refsOid",roid).put("authorDeviceId",f.signer.deviceId).put("keyId",f.signer.keyId).put("totalBytes",raw.size)
                val signed=f.signer.signed("wrss.sync.v2/availability-sign",JSONObject().put("version",2).put("scope",identity.scope).put("entityKey",binding.entity).put("bodyRevision",binding.revision).put("recipeOid",oid).put("status","cloud-ready").put("blobOidsHash",SyncV2Codec.hash(blobs)).put("storeId","99".repeat(16)).put("receiptRevision","1").put("createdAtMillis",f.now).put("expiresAtMillis",f.now+600000))
                available[oid]=f.dto().put("availability",JSONObject().put("scope",identity.scope).put("entityKey",binding.entity).put("bodyRevision",binding.revision).put("recipeOid",oid).put("status","cloud-ready").put("receipt",signed))
                rows.add(JSONObject().put("entityKey",binding.entity).put("bodyRevision",binding.revision).put("recipeOid",oid).put("opaqueContentId",recipe.get("opaqueContentId")).put("contentBytes",bytes).put("rowSequence","1").put("deleted",false))
            }
            f.route={r->val path=r.requestUrl!!.encodedPath
                when {
                    path.contains("/recipe-refs/")->f.response(mappings.getValue(path.substringAfterLast('/')))
                    path.endsWith("/availability/check")->f.response(available.getValue(JSONObject(r.body.readUtf8()).getString("recipeOid")))
                    path.endsWith("/demand")->f.response(f.dto().put("serverNowMillis",f.now).put("demands",JSONArray((0 until demands.length()).map{demands.getJSONObject(it)}.filter{it.getLong("expiresAtMillis")>f.now})))
                    else->{if(r.method=="GET" && r.getHeader("Range")!=null)owners[path.substringAfterLast('/')]?.let{owner->val at=r.getHeader("Range")!!.substringAfter("bytes=").substringBefore('-').toInt();seen.add(owner to at);onRange(owner,at)};null}
                }
            }
        }
        fun demand(i:Int,expires:Long=f.now+600000)=JSONObject().put("demandId",i.toString(16).padStart(32,'0')).put("entityKey",rows[i-1].get("entityKey")).put("bodyRevision",rows[i-1].get("bodyRevision")).put("requesterDeviceId",f.signer.deviceId).put("demandAtMillis",f.now).put("expiresAtMillis",expires)
        fun run(dir:File,budget:Long=32L*1024*1024,clock:()->Long={System.nanoTime()/1000000},fault:(String)->Unit={},priorities:List<SyncV2ContentPriority.Item> = emptyList()):SyncV2ContentSession.DownloadOutcome {
            val s=store(dir,fault);val c=f.client();val w=SyncV2ContentWorker(c,identity,s,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now},clock=clock,approvedDevice={it==f.signer.deviceId})
            val r=SyncV2ContentReceive(identity,f.authors(),s,w,c,{key},now={f.now})
            val session=SyncV2ContentSession(identity,s,w,c,f.signer,SyncV2ContentExport(identity,key,1,s))
            return session.downloadPage(rows,r,{f.now},{e->rows.single{it.getString("entityKey")==e}},{},SyncV2ContentWorker.Budget(bytes=budget),priorities)
        }
    }
    @Test fun r07DemandArrivesAndExpiresAtFineFrameBoundariesWithoutRestartingPartialBlob() {
        SyncV2P3Fixture().use { f ->
            val world=DownloadWorld(f,2,200000);var clock=0L
            world.onRange={owner,at->
                if(owner==1 && at==0){world.demands=JSONArray().put(world.demand(2,f.now+1000));clock=30001}
                if(owner==2 && at==0){clock=32002;f.now+=2001}
            }
            val dir=temp.newFolder();assertEquals("complete",world.run(dir,clock={clock}).status)
            assertEquals(listOf(1 to 0,2 to 0,1 to 65536),world.seen.take(3))
            assertEquals(1,world.seen.count{it==1 to 0});assertEquals(1,world.seen.count{it==2 to 0})
            assertEquals(0,store(dir).load("fairness")!!.getInt("demanded"))
        }
    }
    @Test fun r07OrdinaryRanksUseLocalClassificationAndKeepCompletedRecentImportSlots() {
        SyncV2P3Fixture().use { f ->
            val world=DownloadWorld(f,4,1)
            val items=listOf(SyncV2ContentPriority.Item(world.rows[0].getString("entityKey"),"rss",1),
                SyncV2ContentPriority.Item(world.rows[1].getString("entityKey"),"note",2),
                SyncV2ContentPriority.Item(world.rows[2].getString("entityKey"),"file",10,importedFile=true),
                SyncV2ContentPriority.Item(world.rows[3].getString("entityKey"),"file",20,importedFile=true),
                SyncV2ContentPriority.Item("ff".repeat(32),"file",30,importedFile=true,complete=true))
            assertEquals("complete",world.run(temp.newFolder(),priorities=items).status)
            assertEquals(listOf(2,4,1,3),world.seen.map{it.first})
        }
    }
    @Test fun r07DownloadFairnessSurvivesAcceptanceCrashAndReopenWithoutCountingCachedBlobs() {
        SyncV2P3Fixture().use { f ->
            val world=DownloadWorld(f,10,1);world.demands=JSONArray((1..9).map{world.demand(it)})
            val dir=temp.newFolder();var crash=true
            try {world.run(dir,fault={stage->if(crash && stage=="checkpoint" && store(dir).load("receive/${world.rows[0].getString("recipeOid")}")?.optInt("index")==1){crash=false;throw java.io.IOException("acceptance crash")}});fail("no acceptance crash")}
            catch(_:java.io.IOException){}
            // Metadata commit failure propagates through the product API; the acceptance intent
            // must reconcile the committed digest and fairness on the next host invocation.
            // An upload window may run before another download window on the same store.
            val reopened=store(dir);val receivedBlob=world.owners.keys.single{world.owners[it]==1}
            assertEquals("complete",worker(f,reopened).upload(listOf(receivedBlob)).status)
            assertEquals(1,reopened.load("fairness")!!.getInt("demanded"))
            val ordinary=SyncV2ContentPriority.Item(world.rows[9].getString("entityKey"),"note",1)
            assertEquals("complete",world.run(dir,priorities=listOf(ordinary)).status)
            assertEquals((1..8).toList()+listOf(10,9),world.seen.map{it.first})
            val saved=store(dir).load("fairness")!!;assertEquals(1,saved.getInt("demanded"));assertEquals(2,saved.getInt("nextOrdinary"))
            val before=world.seen.size
            assertEquals("complete",world.run(dir,priorities=listOf(ordinary)).status)
            assertEquals(before,world.seen.size);assertEquals(SyncV2Codec.canonical(saved),SyncV2Codec.canonical(store(dir).load("fairness")))
        }
    }
}
