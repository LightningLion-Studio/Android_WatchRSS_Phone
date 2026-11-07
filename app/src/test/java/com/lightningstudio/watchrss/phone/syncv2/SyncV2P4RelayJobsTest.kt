package com.lightningstudio.watchrss.phone.syncv2

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class SyncV2P4RelayJobData(val f:SyncV2P4Fixture,val store:SyncV2ContentStore,val now:Long=100000L,withBlob:Boolean=false) {
    val tree=SyncV2Index.build(f.identity.scope,emptyList())
    val commit:JSONObject
    val oid:String
    val request:JSONObject
    val raw=linkedMapOf<String,ByteArray>()
    init {
        tree.objects.forEach{(oid,bytes)->store.putObject(oid,bytes);raw[oid]=bytes}
        val body=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("scope",f.identity.scope).put("authorDeviceId",f.b).put("deviceSequence","1").put("treeRoot",tree.root)
            .put("previousOwnCommit",JSONObject.NULL).put("accountParent",JSONObject.NULL).put("mergedParents",JSONArray()).put("rollbackGeneration","0")
            .put("operation",JSONObject().put("kind","normal").put("rollbackTarget",JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
        commit=f.peerSigner.commit(body);oid=SyncV2Codec.oid("commit",body)
        raw[oid]=SyncV2LocalProtocol.bytes(commit);store.putObject(oid,raw.getValue(oid)){f.authors().header(it,false)}
        if(withBlob){val (blob,bytes)=f.blob();raw[blob]=bytes;store.putObject(blob,bytes)}
        val id="66".repeat(16)
        val grant=f.peerSigner.signed("wrss.sync.v2/relay-grant-sign",JSONObject().put("version",2).put("scope",f.identity.scope).put("authorDeviceId",f.b).put("relayDeviceId",f.a)
            .put("ref","refs/devices/${f.b}/index").put("commitOid",oid).put("objectOids",JSONArray(raw.keys.sorted())).put("expiresAtMillis",now+600000).put("grantId","55".repeat(16)))
        request=JSONObject().put("account",SyncV2CloudClient.refUpdate(f.peerSigner,f.identity.scope,"refs/account/current",null,"0",oid,id,now))
            .put("device",SyncV2CloudClient.refUpdate(f.peerSigner,f.identity.scope,"refs/devices/${f.b}/index",null,"0",oid,id,now)).put("relayGrant",grant)
    }
    fun changedGrant(field:String,value:Any)=JSONObject(request.toString()).put("relayGrant",f.peerSigner.signed("wrss.sync.v2/relay-grant-sign",JSONObject(request.getJSONObject("relayGrant").getJSONObject("body").toString()).put(field,value)))
}

class SyncV2P4RelayJobsTest {
    private fun queue(f:SyncV2P4Fixture,store:SyncV2ContentStore,now:()->Long={100000},fault:(String)->Unit={})=SyncV2RelayJobs(f.identity,f.authors(),store,f.a,true,now,fault)
    private class Cloud(val data:SyncV2P4RelayJobData):AutoCloseable {
        val server=SyncV2P3Fixture();val client=server.client();var expected=SyncV2Codec.canonical(data.request)
        var publications=0;var effects=0;var lookups=0;var published:JSONObject?=null;var loseResponse=false;var wrongLookup=false
        init {
            server.objects.putAll(data.raw)
            server.route={r->
                val path=r.requestUrl!!.encodedPath.substringAfter("/functions/v1/sync/v2/")
                assertEquals("Bearer synthetic",r.getHeader("Authorization"))
                when {
                    path=="capabilities"->server.response(server.dto().put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
                        .put("receiptKeyId",server.bootstrap.get("receiptKeyId")).put("receiptSigningPublicKey",server.bootstrap.get("receiptSigningPublicKey")).put("flags",JSONObject().put("read",true).put("contentWrite",true).put("relay",true)))
                    path.startsWith("requests/")->{lookups++;val result=published?.let{JSONObject(it.toString())}
                        if(wrongLookup && result!=null)result.getJSONObject("account").put("commitOid","commit:sha256:"+"ff".repeat(32))
                        server.response(server.dto().put("status",if(result==null)"unknown" else "succeeded").put("response",result?:JSONObject.NULL))}
                    path=="objects/have-want"->{val body=SyncV2Wire.parse(r.body.readByteArray());val oids=body.getJSONArray("oids");val rows=JSONArray()
                        for(i in 0 until oids.length()) {val oid=oids.getString(i);val total=data.raw.getValue(oid).size;val offset=server.prefix[oid]?.size?:0
                            rows.put(JSONObject().put("oid",oid).put("status",if(offset==total)"present" else if(offset==0)"missing" else "partial").put("totalBytes",if(offset==0)JSONObject.NULL else total)
                                .put("durableOffset",offset).put("receipt",if(offset==0)JSONObject.NULL else server.receipt(oid,total,offset)))}
                        server.response(server.dto().put("windowId",body.get("windowId")).put("objects",rows))}
                    path=="objects/reserve"->server.response(server.dto().put("reservationId","77".repeat(16)))
                    path=="account/publish"->{assertEquals(expected,r.body.readUtf8());publications++
                        if(published==null){effects++;published=server.dto().put("account",JSONObject().put("commitOid",data.oid).put("revision","1").put("rollbackGeneration","0"))
                            .put("device",JSONObject().put("ref","refs/devices/${data.f.b}/index").put("commitOid",data.oid).put("revision","1")).put("serverRevision","1")}
                        if(loseResponse){loseResponse=false;MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)}else server.response(published!!)}
                    else->null
                }
            }
        }
        fun relay()=SyncV2Relay(data.f.identity,data.f.authors(),client,data.store,true,signals={SyncV2TransportSignals(wifi=true,internet=true,cellular=false)})
        override fun close()=server.close()
    }
    @Test fun c29PersistsOriginalPublicationAndIdempotentQueuedAckWithoutApplyingAccount() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("c29");val data=SyncV2P4RelayJobData(f,store);val q=queue(f,store)
        val control=SyncV2LocalControl(s,f.identity,f.authors(),store,{JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")},{100000},q)
        val ack=control.refUpdate(data.request);assertEquals("relay-queued",ack.getString("kind"));assertFalse(ack.has("receipt"));assertFalse(ack.has("signature"))
        assertEquals(SyncV2Codec.canonical(ack),SyncV2Codec.canonical(control.refUpdate(data.request)));assertEquals(1,queue(f,f.store("c29")).pending())
        assertEquals(data.raw.keys,q.protectedObjects());assertTrue(control.snapshot().getJSONObject("account").isNull("commitOid"));assertEquals(0,control.snapshot().getJSONArray("refs").length())
        val persisted=store.load("relay-inbox")!!.getJSONArray("jobs").getJSONObject(0).getJSONObject("body")
        assertEquals(SyncV2Codec.canonical(data.request),SyncV2Codec.canonical(persisted))
        val changed=JSONObject(data.request.toString());val account=changed.getJSONObject("account");account.put("body",JSONObject(account.getJSONObject("body").toString()).put("expectedRevision","1"))
        changed.put("account",f.peerSigner.signed("wrss.sync.v2/ref-update-sign",account.getJSONObject("body")))
        p4Error("idempotency_conflict"){control.refUpdate(changed)}
    }}
    @Test fun localEnqueueRejectsExpiredGrantWrongPeerMissingAndCorruptObjectsBeforeAnyWrite() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("reject");val data=SyncV2P4RelayJobData(f,store);val q=queue(f,store);val before=store.counts.writes
        p4Error("relay_denied"){q.enqueue(s,data.changedGrant("expiresAtMillis",99999))}
        p4Error("relay_denied"){q.enqueue(s,data.changedGrant("relayDeviceId",f.b))}
        p4Error("scope_mismatch"){q.enqueue(s,data.changedGrant("scope",JSONObject(f.identity.scopeJson).put("contentGeneration","99")))}
        p4Error("object_missing"){q.enqueue(s,data.changedGrant("objectOids",JSONArray(listOf("blob:sha256:"+"ff".repeat(32)))))}
        p4Error("signature_invalid"){q.enqueue(s,JSONObject(data.request.toString()).put("relayGrant",JSONObject(data.request.getJSONObject("relayGrant").toString()).put("signature",f.signer.sign(byteArrayOf(1)))))}
        assertEquals(before,store.counts.writes);assertEquals(0,q.pending())
        val file=java.io.File(java.io.File(f.root,"reject"),SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8("${f.identity.scopeJson}/${data.tree.root}")))+".object")
        val raw=file.readBytes();raw[raw.lastIndex]=(raw.last().toInt() xor 1).toByte();file.writeBytes(raw)
        p4Error("hash_mismatch"){q.enqueue(s,data.request)};assertEquals(before,store.counts.writes)
    }}
    @Test fun enqueueCrashAfterFsyncRestartsWithOneJobAndSameAck() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("capture-crash");val data=SyncV2P4RelayJobData(f,store)
        var fail=true;val crashing=f.store("capture-crash"){if(it=="checkpoint" && fail){fail=false;throw IllegalStateException("simulated_crash")}}
        p4Error("simulated_crash"){queue(f,crashing).enqueue(s,data.request)}
        val resumed=queue(f,f.store("capture-crash"));assertEquals(1,resumed.pending());val ack=resumed.enqueue(s,data.request)
        assertEquals("relay-queued",ack.getString("kind"));assertEquals(1,resumed.pending());assertEquals(data.raw.keys,resumed.protectedObjects())
    }}
    @Test fun restartedJobResumesFinePrefixAndSubmitsExactBodyThroughOwnSession() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("resume");val data=SyncV2P4RelayJobData(f,store,withBlob=true);queue(f,store).enqueue(s,data.request)
        Cloud(data).use{cloud->
            p4Error("budget_paused"){queue(f,store).resumeNext(cloud.relay(),SyncV2ContentWorker.Budget(bytes=65536))}
            val blob=data.raw.keys.single{it.startsWith("blob:")};assertEquals(65536,cloud.server.prefix[blob]!!.size);assertEquals(0,cloud.publications)
            val originalPutCount=cloud.server.putOffsets.count{it==0}
            val reopened=queue(f,f.store("resume"));val result=reopened.resumeNext(cloud.relay())!!
            assertEquals("cloud-published",result.getString("kind"));assertEquals(0,reopened.pending());assertEquals(1,cloud.effects)
            assertEquals(originalPutCount,cloud.server.putOffsets.takeWhile{it!=65536}.count{it==0})
            assertEquals(1,cloud.server.putOids.zip(cloud.server.putOffsets).count{it.first==blob && it.second==0})
            assertNull(reopened.resumeNext(cloud.relay()))
        }
    }}
    @Test fun cloudSuccessBeforeLocalResultCrashIsRecoveredByLookupEvenAfterGrantExpiry() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("lost-result");val data=SyncV2P4RelayJobData(f,store);queue(f,store).enqueue(s,data.request)
        Cloud(data).use{cloud->
            cloud.loseResponse=true
            p4Error("simulated_crash"){queue(f,store,fault={if(it=="before-result")throw IllegalStateException("simulated_crash")}).resumeNext(cloud.relay())}
            assertEquals(1,cloud.effects);assertEquals(1,queue(f,store).pending())
            val result=queue(f,f.store("lost-result"),now={900000}).resumeNext(cloud.relay())!!
            assertEquals("cloud-published",result.getString("kind"));assertEquals(1,cloud.publications);assertEquals(1,cloud.effects);assertTrue(cloud.lookups>=3)
        }
    }}
    @Test fun grantRenewalPreservesOriginalRefsAndAStagedDeviceNoopIsAllowed() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("renew");val data=SyncV2P4RelayJobData(f,store);val q=queue(f,store)
        q.enqueue(s,data.request)
        val renewed=data.changedGrant("expiresAtMillis",600001)
        val ack=q.enqueue(s,renewed);assertEquals(1,q.pending());assertEquals("55".repeat(16),ack.getString("grantId"))
        Cloud(data).use{cloud->cloud.expected=SyncV2Codec.canonical(renewed);q.resumeNext(cloud.relay());assertEquals(1,cloud.effects)}
        val staged=JSONObject(data.request.toString());staged.put("device",SyncV2CloudClient.refUpdate(f.peerSigner,f.identity.scope,"refs/devices/${f.b}/index",data.oid,"1",data.oid,"88".repeat(16),100000))
        staged.put("account",SyncV2CloudClient.refUpdate(f.peerSigner,f.identity.scope,"refs/account/current",null,"0",data.oid,"88".repeat(16),100000))
        q.enqueue(s,staged);assertEquals(1,q.pending())
    }}
    @Test fun authorizationFailureAndMismatchedLookupCannotCompleteOrRewriteJob() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("auth");val data=SyncV2P4RelayJobData(f,store);val q=queue(f,store);q.enqueue(s,data.request)
        Cloud(data).use{cloud->
            val relay=cloud.relay();f.active.remove(f.a);val before=store.counts.writes;val httpBefore=cloud.server.requests
            p4Error("device_revoked"){q.resumeNext(relay)};assertEquals(before,store.counts.writes);assertEquals(httpBefore,cloud.server.requests)
            f.active.add(f.a);cloud.published=cloud.server.dto().put("account",JSONObject().put("commitOid",data.oid).put("revision","1").put("rollbackGeneration","0"))
                .put("device",JSONObject().put("ref","refs/devices/${f.b}/index").put("commitOid",data.oid).put("revision","1")).put("serverRevision","1")
            cloud.wrongLookup=true;p4Error("idempotency_conflict"){q.resumeNext(relay)};assertEquals(1,q.pending());assertEquals(0,cloud.publications)
        }
    }}
    @Test fun actualLocalRefUpdateWireReturnsOnlyRelayQueuedAfterDurableCapture() {SyncV2P4Fixture().use{f->
        val store=f.store("wire");val data=SyncV2P4RelayJobData(f,store);val cap=SyncV2LocalProtocol.capability(f.identity.scope,SyncV2LocalProtocol.Gates(true))
        val server=ServerSocket(0,1,InetAddress.getLoopbackAddress());val executor=Executors.newSingleThreadExecutor();val q=queue(f,store)
        val receiving=executor.submit<Unit>{server.accept().use{socket->
            socket.soTimeout=5000;val s=f.session(close={socket.close()});val objects=SyncV2LocalObjects(s,store)
            val control=SyncV2LocalControl(s,f.identity,f.authors(),store,{JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")},{100000},q)
            val c=SyncV2LocalConnection(s,socket.getInputStream(),socket.getOutputStream());c.respondHandshake(cap,cap,objects);c.serveOne(objects,control=control)
        }}
        try {Socket(InetAddress.getLoopbackAddress(),server.localPort).use{socket->
            socket.soTimeout=5000;val s=f.session(f.b,f.a,close={socket.close()});val c=SyncV2LocalConnection(s,socket.getInputStream(),socket.getOutputStream());c.initiate(cap,cap)
            val response=c.exchange("syncObjectsV2.refUpdate",data.request);assertEquals(setOf("kind","requestId","grantId"),response.keys().asSequence().toSet());assertEquals("relay-queued",response.getString("kind"))
            receiving.get(5,TimeUnit.SECONDS);assertEquals(1,queue(f,f.store("wire")).pending())
        }}finally{server.close();executor.shutdownNow()}
    }}
}
