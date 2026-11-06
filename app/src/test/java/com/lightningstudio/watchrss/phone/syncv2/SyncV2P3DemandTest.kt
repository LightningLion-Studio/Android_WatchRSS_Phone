package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncV2P3DemandTest {
    @get:Rule val temp=TemporaryFolder()
    private val identity=SyncV2Fixtures.identity();private val key=ByteArray(32){7}
    private fun demand(f:SyncV2P3Fixture,entity:String="11".repeat(32),device:String=f.signer.deviceId,at:Long=f.now,expires:Long=f.now+600000)=JSONObject()
        .put("demandId","33".repeat(16)).put("entityKey",entity).put("bodyRevision","22".repeat(16)).put("requesterDeviceId",device).put("demandAtMillis",at).put("expiresAtMillis",expires)
    @Test fun c9AuthenticatedDemandPollingHonorsThirtySecondsPostRefreshAndExpiryAtFineBoundary() {
        SyncV2P3Fixture().use { f ->
            val s=SyncV2ContentStore(temp.newFolder(),identity.scopeJson,SyncV2Fixtures.localKey);val client=f.client();client.capabilities();var mono=0L;var gets=0
            f.route={r->if(r.method=="GET" && r.requestUrl!!.encodedPath.endsWith("/demand")){gets++;f.response(f.dto().put("serverNowMillis",f.now+mono).put("demands",JSONArray().also{if(mono<1000)it.put(demand(f,expires=f.now+1000))}))}else null}
            val worker=SyncV2ContentWorker(client,identity,s,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,clock={mono},now={f.now+mono},approvedDevice={it==f.signer.deviceId})
            assertEquals(1,worker.pollDemands(true).size);mono=1000;assertEquals(0,worker.pollDemands().size);assertEquals(1,gets)
            mono=30000;worker.pollDemands();assertEquals(2,gets)
            worker.sendDemand("11".repeat(32),"22".repeat(16),"44".repeat(16),f.now+mono,f.now+mono+1000)
            assertEquals(3,gets);assertEquals(1,f.controlRequests)
        }
    }
    @Test fun c9UnknownRequesterIsIgnoredAndMalformedOrDuplicateRowsNeverEnterRankZero() {
        SyncV2P3Fixture().use { f ->val s=SyncV2ContentStore(temp.newFolder(),identity.scopeJson,SyncV2Fixtures.localKey);val c=f.client();c.capabilities();var entries=JSONArray().put(demand(f,device="ff".repeat(16)))
            f.route={r->if(r.method=="GET" && r.requestUrl!!.encodedPath.endsWith("/demand"))f.response(f.dto().put("serverNowMillis",f.now).put("demands",entries))else null}
            val w=SyncV2ContentWorker(c,identity,s,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now},approvedDevice={it==f.signer.deviceId})
            assertTrue(w.pollDemands(true).isEmpty());entries=JSONArray().put(demand(f)).put(demand(f))
            try{w.pollDemands(true);fail()}catch(e:IllegalStateException){assertEquals("invalid_schema",e.message)}
            entries=JSONArray().put(demand(f,expires=f.now));try{w.pollDemands(true);fail()}catch(e:IllegalStateException){assertEquals("invalid_schema",e.message)}
        }
    }
    @Test fun newRemoteDemandPreemptsAtTheNextFineFrameWithoutResettingPersistentFairness() {
        SyncV2P3Fixture().use { f ->val s=SyncV2ContentStore(temp.newFolder(),identity.scopeJson,SyncV2Fixtures.localKey);var mono=0L
            fun make(n:Int)=SyncV2Envelope.seal(identity,key,1,"blob",ByteArray(n){it.toByte()}).also{s.putObject(it.oid,it.bytes);f.objects[it.oid]=it.bytes}
            val a=make(262144);val b=make(1);val entity="11".repeat(32)
            f.route={r->if(r.method=="GET" && r.requestUrl!!.encodedPath.endsWith("/demand"))f.response(f.dto().put("serverNowMillis",f.now+mono).put("demands",JSONArray().also{if(mono in 30001..65000)it.put(demand(f,entity=entity,at=f.now+30001,expires=f.now+65001))}))else null}
            val w=SyncV2ContentWorker(f.client(),identity,s,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,clock={mono},now={f.now+mono},yieldControl={mono+=30001},approvedDevice={it==f.signer.deviceId})
            assertEquals("complete",w.uploadScheduled({listOf(SyncV2ContentWorker.Upload(a.oid,1,"00".repeat(32)),SyncV2ContentWorker.Upload(b.oid,4,entity,revision="22".repeat(16)))}).status)
            assertEquals(a.oid,f.putOids[0]);assertEquals(b.oid,f.putOids[1]);assertEquals(65536,f.putOffsets[2])
        }
    }
}
