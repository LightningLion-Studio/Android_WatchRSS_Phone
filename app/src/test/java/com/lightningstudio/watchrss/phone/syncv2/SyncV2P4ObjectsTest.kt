package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SyncV2P4ObjectsTest {
    @Test fun c30J01PartialPresentAndLostCompleteAckUseOnlyFourWireFieldsAfterReconnect() {SyncV2P4Fixture().use{f->
        val name="c30-reconnect";val (oid,raw)=f.blob(150000)
        val (first,_)=f.pair();SyncV2LocalObjects(first,f.store(name)).putPart(frame(f,first,oid,raw,0));first.close()
        fun row(port:SyncV2LocalObjects)=port.haveWant(JSONObject().put("scope",f.identity.scope)
            .put("windowId",port.openWindow().getString("windowId")).put("oids",JSONArray(listOf(oid))))
            .getJSONArray("objects").getJSONObject(0)
        fun assertReceipt(row:JSONObject,offset:Int,status:String) {
            assertEquals(status,row.getString("status"));assertEquals(offset,row.getInt("durableOffset"))
            val value=row.getJSONObject("receipt")
            SyncV2Codec.exact(value,setOf("kind","oid","durableOffset","totalBytes"))
            assertEquals("local",value.getString("kind"));assertEquals(oid,value.getString("oid"))
            assertEquals(raw.size,value.getInt("totalBytes"));assertEquals(offset,value.getInt("durableOffset"))
        }
        val (next,_)=f.pair();val reopened=f.store(name);val port=SyncV2LocalObjects(next,reopened)
        assertReceipt(row(port),65536,"partial")
        val bound=reopened.load("local-receipt/${next.peerDeviceId}/$oid")!!
        assertEquals(next.sessionId,bound.getString("sessionId"));assertEquals(next.selfDeviceId,bound.getString("holderDeviceId"))
        assertEquals(next.peerDeviceId,bound.getString("peerDeviceId"));assertEquals(SyncV2Codec.canonical(f.identity.scope),SyncV2Codec.canonical(bound.getJSONObject("scope")))
        val transfer="66".repeat(16)
        // Resume from the acknowledged offset only; the first frame is never resent.
        for(offset in 65536 until raw.size step 65536)port.putPart(frame(f,next,oid,raw,offset,transfer))
        assertReceipt(row(port),raw.size,"present")
        port.complete(JSONObject().put("scope",f.identity.scope).put("transferId",transfer).put("oid",oid).put("totalBytes",raw.size))
        next.close() // Drop the final complete ACK; a new store and session must agree.
        val (last,_)=f.pair();assertReceipt(row(SyncV2LocalObjects(last,f.store(name))),raw.size,"present")
        assertArrayEquals(raw,f.store(name).objectBytes(oid))
    }}
    private fun frame(f:SyncV2P4Fixture,s:SyncV2LocalSession,oid:String,raw:ByteArray,offset:Int,transfer:String="44".repeat(16)):SyncV2Wrp2.Frame {
        val out=ByteArrayOutputStream();SyncV2Wrp2.write(out,s,oid,raw.size,offset,transfer,raw.copyOfRange(offset,minOf(raw.size,offset+65536)))
        return SyncV2Wrp2.read(ByteArrayInputStream(out.toByteArray()),s)
    }
    @Test fun finalAckLossResumesWithoutResendAndCompleteIsLocalOnly() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("receive");val port=SyncV2LocalObjects(s,store);val (oid,raw)=f.blob()
        for(offset in raw.indices step 65536)port.putPart(frame(f,s,oid,raw,offset))
        // Drop all returned ACKs, construct a fresh adapter over the persisted store.
        val (next,_)=f.pair();val recovered=SyncV2LocalObjects(next,f.store("receive"));val window=recovered.openWindow().getString("windowId")
        val result=recovered.haveWant(JSONObject().put("scope",f.identity.scope).put("windowId",window).put("oids",JSONArray(listOf(oid)))).getJSONArray("objects").getJSONObject(0)
        assertEquals("present",result.getString("status"));assertEquals(raw.size,result.getInt("durableOffset"));assertArrayEquals(raw,store.objectBytes(oid))
        val complete=port.complete(JSONObject().put("scope",f.identity.scope).put("transferId","44".repeat(16)).put("oid",oid).put("totalBytes",raw.size))
        assertEquals("local",complete.getString("kind"));assertFalse(complete.has("custody"));assertFalse(complete.has("signature"))
    }}
    @Test fun durablePrefixSurvivesCloudLocalLanAdapterChanges() {SyncV2P4Fixture().use{f->
        val store=f.store("switch");val (oid,raw)=f.blob(230000)
        // Same persistent P3 prefix as the cloud downloader; a local socket resumes at 65536.
        store.append(oid,raw.size,0,raw.copyOf(65536))
        val (s,_)=f.pair();val local=SyncV2LocalObjects(s,store)
        val at=store.prefix(oid,raw.size);assertEquals(65536,at)
        local.putPart(frame(f,s,oid,raw,at));s.close()
        val (lan,_)=f.pair();val lanPort=SyncV2LocalObjects(lan,f.store("switch"))
        for(offset in 131072 until raw.size step 65536)lanPort.putPart(frame(f,lan,oid,raw,offset,"55".repeat(16)))
        assertArrayEquals(raw,f.store("switch").objectBytes(oid));assertEquals(0L,store.counts.seals)
    }}
    @Test fun duplicateMismatchGapAndWrongTotalCannotAdvancePrefix() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("dup");val port=SyncV2LocalObjects(s,store);val (oid,raw)=f.blob(230000)
        val first=frame(f,s,oid,raw,0);port.putPart(first);port.putPart(first)
        assertEquals(65536,store.prefix(oid,raw.size))
        val changed=raw.copyOf().also{it[0]=(it[0]+1).toByte()};p4Error("part_conflict"){port.putPart(frame(f,s,oid,changed,0))}
        p4Error("offset_conflict"){port.putPart(frame(f,s,oid,raw,131072))};assertEquals(65536,store.prefix(oid,raw.size))
    }}
    @Test fun rawRangeReturnsExactSignedRefsBytesAndMappingIsImmutable() {SyncV2P3Fixture().use{cloud->SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("refs");val port=SyncV2LocalObjects(s,store,contentKey={f.key})
        val descriptor=f.bodyPackage(store);val parent=descriptor.getString("recipeOid");val blobs=JSONArray()
        val blocks=descriptor.getJSONObject("recipe").getJSONArray("blocks");for(i in 0 until blocks.length())blobs.put(blocks.getJSONObject(i).getString("oid"))
        val signed=SyncV2ContentCodec.refs(f.identity,cloud.signer,parent,blobs);val raw=SyncV2LocalProtocol.bytes(signed)
        val refs="recipe-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}"
        val mapping=JSONObject().put("scope",f.identity.scope).put("parentOid",parent).put("refsOid",refs).put("authorDeviceId",cloud.signer.deviceId).put("keyId",cloud.signer.keyId).put("totalBytes",raw.size)
        port.registerRefs(f.identity,cloud.authors(),parent,blobs,mapping,raw);port.registerRefs(f.identity,cloud.authors(),parent,blobs,mapping,raw)
        val window=port.openWindow().getString("windowId")
        val reply=port.reference("syncObjectsV2.recipeRefsGet",JSONObject().put("scope",f.identity.scope).put("windowId",window).put("parentOid",parent))
        assertEquals(refs,reply.getString("refsOid"))
        val request=f.body(refs,window).put("range",JSONObject().put("start",3).put("end",37));val read=port.objectGet(request)
        val output=ByteArrayOutputStream();port.send(read,output)
        assertArrayEquals(raw.copyOfRange(3,38),SyncV2Wrp2.read(ByteArrayInputStream(output.toByteArray()),s).payload)
        val bad=JSONObject(mapping.toString()).put("keyId","ff".repeat(16));p4Error("identity_conflict"){port.registerRefs(f.identity,cloud.authors(),parent,blobs,bad,raw)}
        p4Error("invalid_oid"){port.reference("syncObjectsV2.stateRefsGet",JSONObject().put("scope",f.identity.scope).put("windowId",window).put("parentOid",parent))}
    }}}
    @Test fun invalidRangesExpiredWindowsAndMissingObjectsArePrecise() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();var now=1L;val store=f.store("range");val port=SyncV2LocalObjects(s,store,{now});val (oid,raw)=f.blob();store.putObject(oid,raw)
        val window=port.openWindow().getString("windowId")
        for(range in listOf(JSONObject().put("start",-1).put("end",2),JSONObject().put("start",3).put("end",2),JSONObject().put("start",0).put("end",raw.size),JSONObject().put("start",0).put("end",1).put("extra",1)))p4Error("invalid_range"){port.objectGet(f.body(oid,window).put("range",range))}
        p4Error("object_missing"){port.objectGet(f.body("blob:sha256:"+"00".repeat(32),window))}
        now=300001;p4Error("window_expired"){port.objectGet(f.body(oid,window))}
    }}
    @Test fun transferIdAndOldSessionCannotWriteUnderNewRead() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("read");val port=SyncV2LocalObjects(s,store);val (oid,raw)=f.blob()
        val expected=SyncV2LocalObjects.Read(JSONObject(),oid,raw.size,0,raw.size-1,"55".repeat(16))
        p4Error("invalid_range"){port.receive(frame(f,s,oid,raw,0),expected)};assertEquals(0,store.prefix(oid,raw.size))
        val stale=frame(f,s,oid,raw,0);s.close();p4Error("authentication_required"){port.putPart(stale)}
    }}
    @Test fun storageFailureBeforeAckRecoversCompleteDurablePrefix() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();var fail=true;val store=f.store("crash"){at->if(at=="frame" && fail){fail=false;throw IllegalStateException("simulated_crash")}}
        val port=SyncV2LocalObjects(s,store);val (oid,raw)=f.blob(40)
        p4Error("simulated_crash"){port.putPart(frame(f,s,oid,raw,0))}
        assertEquals(0,f.store("crash").prefix(oid,raw.size))
        val recovered=SyncV2LocalObjects(s,f.store("crash"));recovered.putPart(frame(f,s,oid,raw,0));assertArrayEquals(raw,store.objectBytes(oid))
    }}
    @Test fun treeDiffSkipsKnownImmutableNodes() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("trees");val port=SyncV2LocalObjects(s,store);val tree=SyncV2Index.build(f.identity.scope,emptyList())
        tree.objects.forEach{(oid,raw)->store.putObject(oid,raw)};val window=port.openWindow().getString("windowId")
        val body=JSONObject().put("scope",f.identity.scope).put("windowId",window).put("treeRoot",tree.root).put("knownNodes",JSONArray()).put("pageToken",JSONObject.NULL)
        assertEquals(tree.root,port.treeDiff(body).getJSONArray("nodes").getString(0))
        body.put("knownNodes",JSONArray(listOf(tree.root)));assertEquals(0,port.treeDiff(body).getJSONArray("nodes").length())
    }}
    @Test fun c28WindowSnapshotIsFrozenBoundToSocketAndRequiredForReads() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();var revision="1"
        val snapshot={JSONObject().put("refs",JSONArray()).put("serverRevision",revision).put("historyFloorRevision","0").put("checkpointOid",JSONObject.NULL)
            .put("account",JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0"))}
        val store=f.store("snap");val port=SyncV2LocalObjects(s,store,snapshot=snapshot);val (oid,raw)=f.blob();store.putObject(oid,raw)
        val captured=port.openWindow();revision="2";assertEquals("1",captured.getString("serverRevision"));SyncV2CloudClient.window(captured)
        val window=captured.getString("windowId");port.objectGet(f.body(oid,window))
        p4Error("window_expired"){port.objectGet(f.body(oid,window).apply{remove("windowId")})}
        p4Error("window_expired"){port.haveWant(JSONObject().put("scope",f.identity.scope).put("oids",JSONArray(listOf(oid))))}
        val (next,_)=f.pair();p4Error("window_expired"){SyncV2LocalObjects(next,store).objectGet(f.body(oid,window))}
        s.close();p4Error("authentication_required"){port.objectGet(f.body(oid,window))}
    }}
    @Test fun localPrefixHandsBackToCloudAndAtomicFrameCheckpointSurvivesCrash() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("handoff");val port=SyncV2LocalObjects(s,store);val (oid,raw)=f.blob(230000)
        port.putPart(frame(f,s,oid,raw,0))
        val reopened=f.store("handoff");assertEquals(65536,reopened.prefix(oid,raw.size))
        for(offset in 65536 until raw.size step 65536)reopened.append(oid,raw.size,offset,raw.copyOfRange(offset,minOf(raw.size,offset+65536)))
        reopened.finish(oid,raw.size);assertArrayEquals(raw,reopened.objectBytes(oid))
        var crash=true;val other=f.store("local-checkpoint"){if(it=="checkpoint" && crash){crash=false;throw IllegalStateException("simulated_crash")}}
        val (small,bytes)=f.blob(10);p4Error("simulated_crash"){SyncV2LocalObjects(s,other).putPart(frame(f,s,small,bytes,0))}
        val recovery=f.store("local-checkpoint");assertEquals(bytes.size,recovery.prefix(small,bytes.size))
        val recovered=SyncV2LocalObjects(s,recovery);val window=recovered.openWindow().getString("windowId")
        assertEquals("present",recovered.haveWant(JSONObject().put("scope",f.identity.scope).put("windowId",window).put("oids",JSONArray(listOf(small)))).getJSONArray("objects").getJSONObject(0).getString("status"))
        assertArrayEquals(bytes,recovery.objectBytes(small))
    }}
    @Test fun stateRefsRawOidAndParentAreVerifiedAndDifferentSignedBytesNeverOverwrite() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("state-refs");val port=SyncV2LocalObjects(s,store,contentKey={f.key})
        val plain=JSONObject().put("version",2).put("entityKey","11".repeat(32)).put("requiredFeatures",JSONArray()).put("payload",JSONObject()).put("noteSegments",JSONArray())
        val state=SyncV2Envelope.seal(f.identity,f.key,1,"state",SyncV2LocalProtocol.bytes(plain));store.putObject(state.oid,state.bytes)
        fun registration(signer:SyncV2Signing):Pair<JSONObject,ByteArray> {
            val signed=SyncV2Index.refs(f.identity,signer,state.oid);val raw=SyncV2LocalProtocol.bytes(signed)
            val mapping=JSONObject().put("scope",f.identity.scope).put("parentOid",state.oid).put("refsOid","state-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}")
                .put("authorDeviceId",signer.deviceId).put("keyId",signer.keyId).put("totalBytes",raw.size)
            return mapping to raw
        }
        val (first,raw)=registration(f.signer);port.registerRefs(f.identity,f.authors(),state.oid,JSONArray(),first,raw)
        val (different,other)=registration(f.peerSigner);p4Error("idempotency_conflict"){port.registerRefs(f.identity,f.authors(),state.oid,JSONArray(),different,other)}
        val window=port.openWindow().getString("windowId")
        val mapping=port.reference("syncObjectsV2.stateRefsGet",JSONObject().put("scope",f.identity.scope).put("windowId",window).put("parentOid",state.oid))
        assertEquals(first.get("refsOid"),mapping.get("refsOid"));assertArrayEquals(raw,store.objectBytes(mapping.getString("refsOid")))
        val damaged=raw.copyOf().also{it[it.lastIndex]=0};p4Error("hash_mismatch"){SyncV2ContentCodec.verifyRefs(f.identity,f.authors(),state.oid,JSONArray(),first,damaged)}
    }}

}
