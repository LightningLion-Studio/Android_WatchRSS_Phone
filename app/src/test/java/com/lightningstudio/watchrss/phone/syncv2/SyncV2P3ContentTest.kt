package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncV2P3ContentTest {
    @get:Rule val temporary=TemporaryFolder()
    private val identity=SyncV2Fixtures.identity();private val key=ByteArray(32){7}
    private fun store(dir:java.io.File,counts:SyncV2ContentStore.Counts=SyncV2ContentStore.Counts(),fault:(String)->Unit={})=SyncV2ContentStore(dir,identity.scopeJson,SyncV2Fixtures.localKey,counts,fault)
    private fun signals()=SyncV2TransportSignals(wifi=true,internet=true,cellular=false)
    private fun worker(f:SyncV2P3Fixture,s:SyncV2ContentStore,enabled:Boolean=true,yield:()->Unit={})=SyncV2ContentWorker(f.client(),identity,s,::signals,enabled=enabled,now={f.now},yieldControl=yield)
    private fun objectIn(f:SyncV2P3Fixture,s:SyncV2ContentStore,bytes:Int=262144):SyncV2Envelope.Sealed {
        val plain=ByteArray(bytes){(it*17).toByte()};return SyncV2Envelope.seal(identity,key,1,"blob",plain).also{s.putObject(it.oid,it.bytes);f.objects[it.oid]=it.bytes}
    }
    private fun error(code:String,fn:()->Unit) {try{fn();fail("accepted invalid input")}catch(e:IllegalStateException){assertEquals(code,e.message)}}
    @Test fun uploadAdvancesThreeBoundedWindowsWithReopenAndOnlyMissingFineFrames() {
        SyncV2P3Fixture().use { f ->
            val dir=temporary.newFolder();var s=store(dir);val object1=objectIn(f,s);val progress=JSONArray()
            repeat(3) {
                val result=worker(f,s).upload(listOf(object1.oid),SyncV2ContentWorker.Budget(bytes=131072))
                assertTrue(result.durableBytes>0);progress.put(JSONObject().put("window",it).put("durableBytes",result.durableBytes).put("completed",result.completed).put("status",result.status))
                s=store(dir)
            }
            assertEquals(listOf(0,65536,131072,196608,262144),f.putOffsets);assertEquals(1,f.completed)
            val before=s.counts.readBytes;val final=worker(f,s).upload(listOf(object1.oid));assertEquals("complete",final.status)
            assertEquals(5,f.putOffsets.size);assertTrue(s.counts.readBytes-before<2048)
            SyncV2Fixtures.evidence("p3-three-upload-windows.json",JSONObject().put("windows",progress).put("counts",s.counts.json()))
        }
    }
    @Test fun c8LostFinalAckQueriesPresentAndNeverRetransmitsOrCallsComplete() {
        SyncV2P3Fixture().use { f ->
            val dir=temporary.newFolder();val s=store(dir);val obj=objectIn(f,s,1);f.dropAck=true
            val first=worker(f,s).upload(listOf(obj.oid));assertEquals("temporarily_unavailable",first.status)
            assertEquals(1,f.putOffsets.size)
            assertEquals("complete",worker(f,store(dir)).upload(listOf(obj.oid)).status)
            assertEquals(1,f.putOffsets.size);assertEquals(0,f.completed)
        }
    }
    @Test fun lostIntermediateAckQueriesPrefixAndReplaysZeroConfirmedBytes() {
        SyncV2P3Fixture().use { f ->val dir=temporary.newFolder();val s=store(dir);val obj=objectIn(f,s);f.dropAck=true
            assertEquals("temporarily_unavailable",worker(f,s).upload(listOf(obj.oid)).status)
            assertEquals("complete",worker(f,store(dir)).upload(listOf(obj.oid)).status)
            assertEquals(listOf(0,65536,131072,196608,262144),f.putOffsets)
        }
    }
    @Test fun rangeDownloadPersistsThreeWindowsThenAuthenticatesOnlyOnceAcrossRestart() {
        SyncV2P3Fixture().use { f ->val source=store(temporary.newFolder());val obj=objectIn(f,source);val dir=temporary.newFolder();var s=store(dir);val progress=JSONArray()
            repeat(3) {val result=worker(f,s).download(listOf(obj.oid to obj.bytes.size),SyncV2ContentWorker.Budget(bytes=131072));assertTrue(result.durableBytes>0);progress.put(result.durableBytes);s=store(dir)}
            val w=worker(f,s);assertArrayEquals(ByteArray(262144){(it*17).toByte()},w.authenticate(obj.oid){key});assertEquals(1,s.counts.authentications)
            val reopened=store(dir);assertArrayEquals(ByteArray(262144){(it*17).toByte()},worker(f,reopened).authenticate(obj.oid){key});assertEquals(0,reopened.counts.authentications);assertEquals(5,f.ranges)
            SyncV2Fixtures.evidence("p3-three-download-windows.json",JSONObject().put("durableBytes",progress).put("ranges",f.ranges))
        }
    }
    @Test fun interruptedFrameOnlyReplaysOneFineFrameAndCheckedPublicationDoesNotRepeatRemoteAuth() {
        SyncV2P3Fixture().use { f ->val dir=temporary.newFolder();var armed=true
            val s=store(dir,fault={if(it=="frame" && armed){armed=false;throw java.io.IOException("synthetic crash")}});val source=store(temporary.newFolder());val obj=objectIn(f,source)
            assertEquals("temporarily_unavailable",worker(f,s).download(listOf(obj.oid to obj.bytes.size)).status)
            val reopened=store(dir);assertEquals(0,reopened.prefix(obj.oid,obj.bytes.size));assertEquals("complete",worker(f,reopened).download(listOf(obj.oid to obj.bytes.size)).status)
            assertEquals(6,f.ranges)
            var authCrash=true;val faulty=store(dir,fault={if(it=="authenticated" && authCrash){authCrash=false;throw java.io.IOException("synthetic crash")}})
            try{worker(f,faulty).authenticate(obj.oid){key};fail()}catch(_:java.io.IOException){}
            val final=store(dir);assertEquals(262144,worker(f,final).authenticate(obj.oid){key}.size);assertEquals(0,final.counts.authentications)
        }
    }
    @Test fun serverIgnoringRangeIsRejectedBeforeAnyDurablePrefix() {
        SyncV2P3Fixture().use { f ->val s=store(temporary.newFolder());val obj=objectIn(f,s);f.wrongRange=true
            val destination=store(temporary.newFolder());error("invalid_range"){worker(f,destination).download(listOf(obj.oid to obj.bytes.size))}
            assertEquals(0,destination.prefix(obj.oid,obj.bytes.size))
        }
    }
    @Test fun acceptedGzipBlockReopensWithoutRepeatingRemoteAuthenticationOrDecompression() {
        SyncV2P3Fixture().use { f ->
            val plain=ByteArray(262144){7};val out=java.io.ByteArrayOutputStream();java.util.zip.GZIPOutputStream(out).use{it.write(plain)}
            val sealed=SyncV2Envelope.sealWithParameters(identity.scope,{t,p->identity.opaque(t,p)},key,1,"blob",plain,SyncV2Codec.random(32),SyncV2Codec.random(12),"gzip",out.toByteArray())
            val dir=temporary.newFolder();val s=store(dir);s.putObject(sealed.oid,sealed.bytes)
            assertArrayEquals(plain,worker(f,s).authenticate(sealed.oid){key});assertEquals(262144,s.counts.decompressBytes)
            val reopened=store(dir);assertArrayEquals(plain,worker(f,reopened).authenticate(sealed.oid){key})
            assertEquals(0,reopened.counts.decompressBytes);assertEquals(0,reopened.counts.authentications)
        }
    }
    @Test fun gateUnknownAndDefaultOffMakeZeroHttpRequestsAndBusyEndsRoundImmediately() {
        SyncV2P3Fixture().use { f ->val s=store(temporary.newFolder());val obj=objectIn(f,s)
            assertEquals("feature_disabled",worker(f,s,enabled=false).upload(listOf(obj.oid)).status);assertEquals(0,f.requests)
            val unknown=SyncV2ContentWorker(f.client(),identity,s,{SyncV2TransportSignals()},enabled=true,now={f.now})
            assertEquals("transport_wait",unknown.upload(listOf(obj.oid)).status);assertEquals(0,f.requests)
            f.busy=true;assertEquals("cloud_account_busy",worker(f,s).upload(listOf(obj.oid)).status);assertEquals(2,f.requests)
        }
    }
    @Test fun demandAtFineFrameBoundaryPreemptsAndPersistsEightToOneBlobFairness() {
        SyncV2P3Fixture().use { f ->val dir=temporary.newFolder();val s=store(dir);val ranks=mutableMapOf<String,Int>()
            val tasks=(0 until 16).map{n->val obj=SyncV2Envelope.seal(identity,key,1,"blob",byteArrayOf(n.toByte()));s.putObject(obj.oid,obj.bytes);f.objects[obj.oid]=obj.bytes;ranks[obj.oid]=0;SyncV2ContentWorker.Upload(obj.oid,0,"demand",blockIndex=n)}.toMutableList()
            for(rank in 1..2){val obj=SyncV2Envelope.seal(identity,key,1,"blob",byteArrayOf((rank+20).toByte()));s.putObject(obj.oid,obj.bytes);f.objects[obj.oid]=obj.bytes;ranks[obj.oid]=rank;tasks.add(SyncV2ContentWorker.Upload(obj.oid,rank,"ordinary"))}
            assertEquals("complete",worker(f,s).uploadScheduled({tasks}).status)
            val actual=f.putOids.map{ranks[it]!!};val expected=SyncV2Fixtures.vector("priority").getJSONObject("fairness").getJSONArray("expectedRanks")
            assertEquals((0 until expected.length()).map{expected.getInt(it)},actual)
            val saved=store(dir).load("fairness")!!
            assertEquals(0,saved.getInt("demanded"));assertEquals(3,saved.getInt("nextOrdinary"))
        }
    }
    @Test fun cloudRequestsContainOnlyOpaqueMetadataAndNeverTitleUrlBusinessKindOrOrdinaryRanks() {
        SyncV2P3Fixture().use { f ->
            val s=store(temporary.newFolder());val plain="private title https://private.invalid/article article".toByteArray()
            val sealed=SyncV2Envelope.seal(identity,key,1,"blob",plain);s.putObject(sealed.oid,sealed.bytes);f.objects[sealed.oid]=sealed.bytes
            assertEquals("complete",worker(f,s).uploadScheduled({listOf(SyncV2ContentWorker.Upload(sealed.oid,3,"11".repeat(32)))}).status)
            repeat(f.server.requestCount) {
                val request=f.server.takeRequest(1,TimeUnit.SECONDS)!!;val wire=request.body.readByteArray().toString(Charsets.UTF_8)
                assertFalse(wire.contains("private title"));assertFalse(wire.contains("https://private.invalid"));assertFalse(wire.contains("\"kind\""));assertFalse(wire.contains("\"rank\""))
            }
        }
    }
    @Test fun requestDeadlineStopsSlowContentWithinTheGrantedHostBudget() {
        SyncV2P3Fixture().use { f ->
            val s=store(temporary.newFolder());val obj=objectIn(f,s);f.partDelay=500
            val at=System.nanoTime();val result=worker(f,s).upload(listOf(obj.oid),SyncV2ContentWorker.Budget(millis=100,reserveMillis=0))
            val elapsed=(System.nanoTime()-at)/1000000
            assertEquals("budget_paused",result.status);assertTrue(elapsed<1000)
        }
    }
    @Test fun controlRequestCompletesWhileContentHttpIsBlockedAndFrameYieldRemainsBounded() {
        SyncV2P3Fixture().use { f ->val s=store(temporary.newFolder());val obj=objectIn(f,s);val entered=CountDownLatch(1);val release=CountDownLatch(1)
            f.route={request->if("/parts/" in request.requestUrl!!.encodedPath){entered.countDown();release.await(3,TimeUnit.SECONDS)};null}
            val pool=SyncV2ContentDispatcher();val marks=mutableListOf<Long>();val start=System.nanoTime()
            try {
                val future=pool.submit<SyncV2ContentWorker.Result>{worker(f,s,yield={marks.add(System.nanoTime())}).upload(listOf(obj.oid))}
                assertTrue(entered.await(3,TimeUnit.SECONDS))
                val control=f.client();control.capabilities();val at=System.nanoTime();control.demand(JSONObject().put("scope",identity.scope).put("entityKey","11".repeat(32)).put("bodyRevision","22".repeat(16)).put("requesterDeviceId","aa".repeat(16)).put("demandId","33".repeat(16)).put("demandAtMillis",f.now).put("expiresAtMillis",f.now+1000))
                val latency=(System.nanoTime()-at)/1000000;assertTrue(latency<1000);release.countDown();assertEquals("complete",future.get(20,TimeUnit.SECONDS).status)
                val elapsed=(System.nanoTime()-start)/1000000;val gaps=marks.zipWithNext{a,b->(b-a)/1000000};assertTrue(gaps.all{it<1000})
                SyncV2Fixtures.evidence("p3-control-and-content.json",JSONObject().put("controlLatencyMillis",latency).put("contentMillis",elapsed).put("sealedBytes",obj.bytes.size).put("maxFrameYieldGapMillis",gaps.maxOrNull()?:0).put("counts",s.counts.json()))
            } finally {release.countDown();pool.close()}
        }
    }
}
