package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class SyncV2P3ExportTest {
    @get:Rule val temp=TemporaryFolder()
    private val identity=SyncV2Fixtures.identity();private val key=ByteArray(32){7}
    private fun store(dir:java.io.File,c:SyncV2ContentStore.Counts=SyncV2ContentStore.Counts(),fault:(String)->Unit={})=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey,c,fault)
    private class Source(override val size:Long):SyncV2ContentExport.Source {
        override var version="body-v1";var reads=0
        override fun read(offset:Long,maxBytes:Int):ByteArray {
            reads++;return ByteArray(maxBytes){
                var x=(offset+it).toInt() xor 0x13579bdf
                x=(x xor (x ushr 16))*0x7feb352d;x=(x xor (x ushr 15))*0x846ca68b.toInt()
                (x xor (x ushr 16)).toByte()
            }
        }
    }
    private fun binding(n:Int=1)=SyncV2ContentExport.Binding(n.toString(16).padStart(64,'0'),"22".repeat(16),"auth-v1")
    @Test fun exportResumesRunningDigestAndExactSealedObjectsWithoutOriginalReread() {
        val source=Source(1048576);val dir=temp.newFolder();val b=binding();var state:JSONObject?=null
        repeat(5) {
            val s=store(dir);val export=SyncV2ContentExport(identity,key,1,s);export.step(b,source)
            state=export.descriptor(b)
        }
        val recipe=state!!.getJSONObject("recipe");val s=store(dir);SyncV2ContentCodec.verifyBody(identity,recipe,{block->SyncV2Envelope.open(identity,key,block.getString("oid"),s.objectBytes(block.getString("oid")),1)})
        assertEquals(4,source.reads)
        val before=s.counts.json();assertTrue(SyncV2ContentExport(identity,key,1,s).step(b,source));assertEquals(4,source.reads)
        assertEquals(0,s.counts.sourceBytes);assertEquals(0,s.counts.seals);assertEquals(before.getLong("exports"),s.counts.exports)
    }
    @Test fun checkpointInterruptionAdoptsStagedExportWithoutRehashCompressionOrEncryption() {
        val source=Source(262144);val dir=temp.newFolder();var crash=true
        val s=store(dir,fault={if(it=="checkpoint" && crash){crash=false;throw java.io.IOException("synthetic crash")}})
        try{SyncV2ContentExport(identity,key,1,s).step(binding(),source);fail()}catch(_:java.io.IOException){}
        val resumed=store(dir);val export=SyncV2ContentExport(identity,key,1,resumed)
        assertFalse(export.step(binding(),source));assertTrue(export.step(binding(),source));assertEquals(1,source.reads)
        assertEquals(0,resumed.counts.sourceBytes);assertEquals(1,resumed.counts.seals) // recipe only
    }
    @Test fun originalBodyOrAuthenticationChangesInvalidateOnlyTheirOwnExportBinding() {
        val source=Source(1);val dir=temp.newFolder();val s=store(dir);val export=SyncV2ContentExport(identity,key,1,s)
        export.step(binding(),source);export.step(binding(),source);source.version="body-v2"
        try{export.step(binding(),source);fail()}catch(e:IllegalStateException){assertEquals("body_revision_changed",e.message)}
        assertNotNull(export.descriptor(binding()))
        val changed=binding().copy(authentication="auth-v2");assertNull(export.descriptor(changed));export.step(changed,source);export.step(changed,source)
        assertEquals(2,source.reads)
    }
    @Test fun pressureLegacy297GzipChunksAndV2Same19408275LogicalBytesHaveSeparateCounts() {
        val size=19408275L;val source=Source(size);val legacyHash=MessageDigest.getInstance("SHA-256");var legacyEncoded=0L;var legacyPlain=0L;var legacyAuthNanos=0L;var legacyDecompressNanos=0L;var legacyFileNanos=0L;val legacyDir=temp.newFolder()
        val start=System.nanoTime()
        repeat(297){i->val offset=size*i/297;val end=size*(i+1)/297;val bytes=source.read(offset,(end-offset).toInt());val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(bytes)};val encoded=out.toByteArray();val nonce=SyncV2Codec.random(12);val cipher=SyncV2Codec.encrypt(key,nonce,ByteArray(0),encoded)
            val file=java.io.File(legacyDir,"$i");file.outputStream().use{it.write(cipher)};val ioAt=System.nanoTime();val fetched=file.readBytes();legacyFileNanos+=System.nanoTime()-ioAt;legacyEncoded+=fetched.size
            val authAt=System.nanoTime();val verified=SyncV2Codec.decrypt(key,nonce,ByteArray(0),fetched);legacyAuthNanos+=System.nanoTime()-authAt
            val decompressAt=System.nanoTime();val decoded=GZIPInputStream(ByteArrayInputStream(verified)).use{it.readBytes()};legacyDecompressNanos+=System.nanoTime()-decompressAt
            assertArrayEquals(bytes,decoded);legacyHash.update(decoded);legacyPlain+=decoded.size}

        val legacyMillis=(System.nanoTime()-start)/1000000
        val dir=temp.newFolder();val counts=SyncV2ContentStore.Counts();var done=false;var window=0;val progress=JSONArray();val blockCount=(size+262143)/262144
        while(!done) {
            val s=store(dir,counts);val export=SyncV2ContentExport(identity,key,1,s);val before=counts.sourceBytes
            val at=System.nanoTime();repeat(26){if(!done)done=export.step(binding(),source)}
            progress.put(JSONObject().put("window",++window).put("persistedSourceBytes",counts.sourceBytes-before).put("millis",(System.nanoTime()-at)/1000000))
            assertTrue(counts.sourceBytes>before || done)
        }
        assertTrue(window>=3);assertEquals(size,counts.sourceBytes)
        val descriptor=SyncV2ContentExport(identity,key,1,store(dir,counts)).descriptor(binding())!!;val recipe=descriptor.getJSONObject("recipe")
        assertEquals(blockCount,recipe.getJSONArray("blocks").length().toLong());assertEquals(SyncV2Codec.hex(legacyHash.digest()),recipe.getString("plaintextSha256"))
        assertEquals(75,recipe.getJSONArray("blocks").length())
        val networkWindows=JSONArray()
        SyncV2P3Fixture().use { f ->
            f.partDelay=10
            val ids=(0 until recipe.getJSONArray("blocks").length()).map{recipe.getJSONArray("blocks").getJSONObject(it).getString("oid")}
            val s=store(dir,counts);for(oid in ids)f.objects[oid]=s.objectBytes(oid)
            repeat(3) { index ->
                val worker=SyncV2ContentWorker(f.client(),identity,store(dir,counts),{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now})
                val result=worker.upload(ids,SyncV2ContentWorker.Budget(bytes=8L*1024*1024))
                assertTrue(result.durableBytes>0)
                networkWindows.put(JSONObject().put("window",index+1).put("durableBytes",result.durableBytes).put("objects",result.completed).put("status",result.status))
                if(index==2)assertEquals("complete",result.status)
            }
            assertEquals(75,f.completed)
        }
        SyncV2Fixtures.evidence("p3-pressure-19408275.json",JSONObject().put("logicalBytes",size).put("legacyGzipChunks",297).put("legacyEncodedBytes",legacyEncoded).put("legacyPlainBytes",legacyPlain).put("legacyMillis",legacyMillis).put("legacyAuthentications",297).put("legacyDecompressions",297).put("legacyFileReads",297).put("legacyAuthenticationMillis",legacyAuthNanos/1000000).put("legacyDecompressionMillis",legacyDecompressNanos/1000000).put("legacyFileIOMillis",legacyFileNanos/1000000)
            .put("v2Blocks",blockCount).put("preparationWindows",progress).put("networkWindows",networkWindows).put("counts",counts.json()).put("crossBridgeCalls",0).put("bridgeApplicable",false))
    }
    @Test fun thirtyFourObjects194527459BytesConfirmedCacheReopenReadsOnlyDescriptors() {
        val dir=temp.newFolder();val total=194527459L;val counts=SyncV2ContentStore.Counts();val sources=mutableListOf<Source>();val allOids=mutableListOf<String>()
        for(i in 0 until 34) {
            val source=Source(total*(i+1)/34-total*i/34);sources.add(source);val export=SyncV2ContentExport(identity,key,1,store(dir,counts))
            while(!export.step(binding(i+1),source)){}
            val d=export.descriptor(binding(i+1))!!;val blocks=d.getJSONObject("recipe").getJSONArray("blocks")
            allOids.addAll((0 until blocks.length()).map{blocks.getJSONObject(it).getString("oid")});allOids.add(d.getString("recipeOid"))
        }
        assertEquals(total,counts.sourceBytes);val first=counts.json();val reopened=SyncV2ContentStore.Counts();val start=System.nanoTime()
        for(i in 0 until 34) {
            val s=store(dir,reopened);val export=SyncV2ContentExport(identity,key,1,s)
            // Reopen, interleave state-only work (it does not change Source.version), reuse descriptor.
            s.save("small-state/$i",JSONObject().put("reading",i))
            assertTrue(export.step(binding(i+1),sources[i]));assertNotNull(export.descriptor(binding(i+1)))
        }
        SyncV2P3Fixture().use { f ->
            f.route={r->if(r.requestUrl!!.encodedPath.endsWith("/objects/have-want")) {
                val body=SyncV2Wire.parse(r.body.readByteArray());val oids=body.getJSONArray("oids");val rows=JSONArray()
                for(i in 0 until oids.length()) {
                    val oid=oids.getString(i);val total=store(dir).objectSize(oid)
                    rows.put(JSONObject().put("oid",oid).put("status","present").put("totalBytes",total).put("durableOffset",total).put("receipt",f.receipt(oid,total,total)))
                }
                f.response(f.dto().put("windowId",body.get("windowId")).put("objects",rows))
            }else null}
            val worker=SyncV2ContentWorker(f.client(),identity,store(dir,reopened),{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now})
            assertEquals("complete",worker.upload(allOids).status);assertEquals(0,f.putOffsets.size)
            assertTrue(reopened.metadataBatches<allOids.size)
        }
        assertEquals(0,reopened.sourceBytes);assertEquals(0,reopened.exports);assertEquals(0,reopened.seals);assertEquals(0,reopened.hashBytes)
        assertTrue(reopened.readBytes<1048576)
        SyncV2Fixtures.evidence("p3-cache-194527459.json",JSONObject().put("objects",34).put("logicalBytes",total).put("first",first).put("cache",reopened.json()).put("cacheMillis",(System.nanoTime()-start)/1000000).put("remoteConfirmedObjects",allOids.size).put("unbatchedReceiptBaseline",allOids.size).put("vmHwm",java.io.File("/proc/self/status").readLines().firstOrNull{it.startsWith("VmHWM:")}?:"unavailable"))
    }
    @Test fun hostUploadWindowPersistsQueueObjectsAndSignedRefsWithoutClaimingAccountPublication() {
        SyncV2P3Fixture().use { f ->
            val s=store(temp.newFolder());val queue=SyncV2ContentQueue(s)
            val row=JSONObject().put("entityKey","11".repeat(32)).put("bodyRevision","22".repeat(16)).put("rowSequence","1").put("deleted",false)
            queue.enqueue(SyncV2ContentQueue.Job(row,"book",true,1))
            val source=Source(1);val exporter=SyncV2ContentExport(identity,key,1,s);val binding=SyncV2ContentExport.Binding(row.getString("entityKey"),row.getString("bodyRevision"),exporter.authenticationBinding)
            // The synthetic receiver knows expected totals; source reads still happen only in the real exporter.
            while(!exporter.step(binding,source)){}
            val descriptor=exporter.descriptor(binding)!!;val blocks=descriptor.getJSONObject("recipe").getJSONArray("blocks")
            for(oid in (0 until blocks.length()).map{blocks.getJSONObject(it).getString("oid")}+descriptor.getString("recipeOid"))f.objects[oid]=s.objectBytes(oid)
            var refs=0;f.route={r->if(r.requestUrl!!.encodedPath.endsWith("/recipe-refs")){refs++;f.response(f.dto().put("accepted",true))}else null}
            val c=f.client();val worker=SyncV2ContentWorker(c,identity,s,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now})
            val session=SyncV2ContentSession(identity,s,worker,c,f.signer,exporter);var attachments=0
            val reads=source.reads;val outcome=session.uploadPage(queue,{source},{_,_,_->attachments++},emptyList())
            assertEquals("objects-complete",outcome.status);assertEquals(reads,source.reads);assertEquals(1,refs);assertEquals(1,attachments);assertTrue(queue.page().single().getBoolean("complete"))
        }
    }
    @Test fun boundedNativeMetadataBatchHasNoClaimedBridgeAndRejectsOversizeBatch() {
        val s=store(temp.newFolder());repeat(64){s.save("receipt/$it",JSONObject().put("offset",it))}
        val keys=(0 until 64).map{"receipt/$it"};val before=s.counts.reads;assertEquals(64,s.metadata(keys).size);assertEquals(64,s.counts.reads-before)
        try{s.metadata(keys+"extra");fail()}catch(e:IllegalStateException){assertEquals("object_too_large",e.message)}
        SyncV2Fixtures.evidence("p3-native-io.json",JSONObject().put("nativeFileReads",64).put("batchMaxEntries",64).put("maxFrameBytes",65536).put("crossBridgeCalls",0).put("bridgeApplicable",false))
    }
}
