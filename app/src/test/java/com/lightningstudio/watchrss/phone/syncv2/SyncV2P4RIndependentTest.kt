package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Regressions ported from the read-only P4R independent probes. Expectations come from
 * C8/C33. The only wire adaptation is seven-field headers and explicit control transfer IDs. */
class SyncV2P4RIndependentTest {
    private val transfer="66".repeat(16)
    private fun commit(f:SyncV2P4Fixture):Triple<String,ByteArray,ByteArray> {
        val body=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("scope",f.identity.scope).put("authorDeviceId",f.b)
        val signed=f.peerSigner.commit(body);val good=SyncV2LocalProtocol.bytes(signed)
        val bad=SyncV2LocalProtocol.bytes(JSONObject(signed.toString()).put("signature",f.signer.sign(byteArrayOf(1))))
        assertEquals(good.size,bad.size)
        return Triple(SyncV2Codec.oid("commit",body),good,bad)
    }
    private fun frame(s:SyncV2LocalSession,oid:String,raw:ByteArray)=SyncV2Wrp2.Frame(JSONObject().put("scope",s.scope).put("sessionId",s.sessionId)
        .put("oid",oid).put("totalBytes",raw.size).put("offset",0).put("partBytes",raw.size).put("partSha256",SyncV2Codec.hex(SyncV2Codec.sha(raw))),raw)
    private fun want(f:SyncV2P4Fixture,port:SyncV2LocalObjects,oid:String)=JSONObject().put("scope",f.identity.scope)
        .put("windowId",port.openWindow().getString("windowId")).put("oids",JSONArray(listOf(oid)))
    private fun complete(s:SyncV2LocalSession,oid:String,size:Int)=JSONObject().put("scope",s.scope).put("transferId",transfer).put("oid",oid).put("totalBytes",size)

    @Test fun independentWireAndRecoveryRegressions() {
        val observed=JSONArray()
        fun expect(id:String,expected:String,block:()->Any?):Any? {
            val result=runCatching(block);val actual=result.exceptionOrNull()?.message?:"accepted"
            assertEquals(id,expected,actual);observed.put(JSONObject().put("id",id).put("expected",expected).put("result",actual).put("value",result.getOrNull()?:JSONObject.NULL))
            return result.getOrNull()
        }
        SyncV2P4Fixture().use{f->
            val (s,_)=f.pair();val store=f.store("p4r-independent");val obj=SyncV2LocalObjects(s,store)
            expect("knownRefs-malformed","invalid_schema"){obj.openWindow(JSONObject().put("scope",f.identity.scope).put("knownRefs",JSONArray().put(JSONObject())).put("features",JSONArray()))}
            val h=frame(s,"blob:sha256:"+"0".repeat(64),byteArrayOf(1))
            expect("put-header-no-transferId","accepted"){SyncV2Wrp2.validate(s,h.header,h.payload)}
            expect("put-header-extra-transferId","invalid_schema"){SyncV2Wrp2.validate(s,JSONObject(h.header.toString()).put("transferId",transfer),h.payload)}
            expect("receipt-zero","invalid_schema"){obj.localReceipt(JSONObject().put("kind","local").put("oid","blob:sha256:"+"9".repeat(64)).put("totalBytes",0).put("durableOffset",0))}
            obj.localReceipt(JSONObject().put("kind","local").put("oid",h.header.get("oid")).put("totalBytes",100).put("durableOffset",80))
            expect("receipt-regression","accepted"){obj.localReceipt(JSONObject().put("kind","local").put("oid",h.header.get("oid")).put("totalBytes",100).put("durableOffset",40))}
            expect("unknown-window","window_expired"){obj.haveWant(JSONObject().put("scope",f.identity.scope).put("windowId","f".repeat(32)).put("oids",JSONArray()))}
            expect("malformed-window","window_expired"){obj.haveWant(JSONObject().put("scope",f.identity.scope).put("windowId","bad").put("oids",JSONArray()))}
            val good=byteArrayOf(1,2,3);val oid="blob:sha256:"+SyncV2Codec.hex(SyncV2Codec.sha(good));store.putObject(oid,good)
            expect("complete-unknown-transfer","invalid_schema"){obj.complete(complete(s,oid,3))}
            val (id,correct,bad)=commit(f);val cs=f.store("p4r-signature");val co=SyncV2LocalObjects(s,cs,verifyCommit={f.authors().header(it,false)})
            expect("bad-signature-put","signature_invalid"){co.putPart(frame(s,id,bad),transfer)}
            assertEquals(0,expect("bad-signature-rejected-prefix","accepted"){f.store("p4r-signature").prefix(id,bad.size)});assertNull(cs.localTotal(id))
            val row=(expect("bad-signature-haveWant","accepted"){co.haveWant(want(f,co,id))} as JSONObject).getJSONArray("objects").getJSONObject(0)
            assertEquals("missing",row.getString("status"));assertTrue(row.isNull("receipt"))
            expect("bad-signature-repair","accepted"){co.putPart(frame(s,id,correct),transfer)}
            val reopened=f.store("p4r-signature")
            assertEquals(correct.size,expect("bad-signature-reopen-prefix","accepted"){reopened.prefix(id,correct.size)})
            assertArrayEquals(correct,reopened.objectBytes(id))
            val x=f.session(features=listOf("witness"));val y=f.session(f.b,f.a);val hx=x.hello();val hy=y.hello();x.receiveHello(hy);y.receiveHello(hx);x.receiveProof(y.ownProof());y.receiveProof(x.ownProof())
            expect("features-not-peer-negotiated","feature_missing"){SyncV2LocalObjects(x,store).openWindow(JSONObject().put("scope",f.identity.scope).put("knownRefs",JSONArray()).put("features",JSONArray(listOf("witness"))))}
            var revoked=false
            val z=f.session(member={if(it==f.b && revoked)"revoked" else "active"});val w=f.session(f.b,f.a);val hz=z.hello();val hw=w.hello();z.receiveHello(hw);w.receiveHello(hz);z.receiveProof(w.ownProof());w.receiveProof(z.ownProof());revoked=true
            expect("revoked-after-proof-window","member_revoked"){SyncV2LocalObjects(z,store).openWindow()}
        }
        System.getenv("P4R_RESULTS")?.let{File(it).writeText(observed.toString(2)+"\n")}
    }
    @Test fun completeAndHaveWantRejectSignatureAndDiscardReceiveStateBeforeRepair() {
        for(entry in listOf("complete","haveWant"))SyncV2P4Fixture().use{f->
            val (s,_)=f.pair();val (oid,good,bad)=commit(f);var crash=true
            val store=f.store("p4r-$entry"){if(it=="checkpoint" && crash){crash=false;throw IllegalStateException("simulated_crash")}}
            val port=SyncV2LocalObjects(s,store,verifyCommit={f.authors().header(it,false)})
            p4Error("simulated_crash"){port.putPart(frame(s,oid,bad),transfer)}
            val receiptKey="local-receipt/${f.b}/$oid";store.save(receiptKey,JSONObject().put("durableOffset",bad.size))
            p4Error("signature_invalid"){if(entry=="complete")port.complete(complete(s,oid,bad.size))else port.haveWant(want(f,port,oid))}
            val reopened=f.store("p4r-$entry");assertEquals(0,reopened.prefix(oid,bad.size));assertNull(reopened.localTotal(oid));assertNull(reopened.load(receiptKey))
            p4Error("invalid_schema"){port.complete(complete(s,oid,bad.size))}
            val (next,_)=f.pair();val repaired=SyncV2LocalObjects(next,reopened,verifyCommit={f.authors().header(it,false)})
            repaired.putPart(frame(next,oid,good),transfer);assertArrayEquals(good,reopened.objectBytes(oid))
        }
    }
    @Test fun quarantineCrashRecoversFailedPrefixWithoutRemovingAuthenticatedObjectOrCustody() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val (oid,good,bad)=commit(f);val store=f.store("p4r-crash")
        store.putObject(oid,good){f.authors().header(it,false)};store.savePlain(oid,good)
        val descriptor=f.bodyPackage(store,32);val recipe=descriptor.getString("recipeOid");val blobs=JSONArray()
        val blocks=descriptor.getJSONObject("recipe").getJSONArray("blocks");for(i in 0 until blocks.length())blobs.put(blocks.getJSONObject(i).getString("oid"))
        val raw=SyncV2LocalProtocol.bytes(SyncV2ContentCodec.refs(f.identity,f.signer,recipe,blobs));val refs="recipe-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}"
        val mapping=JSONObject().put("scope",f.identity.scope).put("parentOid",recipe).put("refsOid",refs).put("authorDeviceId",f.a).put("keyId",f.signer.keyId).put("totalBytes",raw.size)
        SyncV2LocalObjects(s,store,contentKey={f.key}).registerRefs(f.identity,f.authors(),recipe,blobs,mapping,raw)
        val pinBody=JSONObject().put("version",2).put("scope",f.identity.scope).put("pinId","88".repeat(16)).put("holderDeviceId",f.a).put("claimantDeviceId",f.b)
            .put("entityKey","11".repeat(32)).put("bodyRevision","22".repeat(16)).put("recipeOid",recipe).put("blobOidsHash",SyncV2Codec.hash(blobs)).put("createdAtMillis",100)
        val pin=SyncV2Custody(f.identity,store,f.signer,f.authors()){f.key}.persist(s,pinBody)
        val receiptKey="local-receipt/${f.b}/$oid";store.save(receiptKey,JSONObject().put("durableOffset",bad.size))
        store.appendLocal(oid,bad.size,0,bad,JSONObject())
        val crashing=f.store("p4r-crash"){if(it=="quarantine")throw IllegalStateException("simulated_crash")}
        p4Error("simulated_crash"){crashing.finish(oid,bad.size,{f.authors().header(it,false)},receiptKey)}
        val reopened=f.store("p4r-crash");assertEquals(0,reopened.prefix(oid,bad.size));assertNull(reopened.localTotal(oid));assertNull(reopened.load(receiptKey))
        assertArrayEquals(good,reopened.objectBytes(oid));assertArrayEquals(good,reopened.plain(oid))
        val retained=SyncV2Custody(f.identity,reopened,f.signer,f.authors()){f.key}.read(pinBody.getString("pinId"))
        assertEquals("active",retained.getString("state"));assertEquals(SyncV2Codec.canonical(pin),SyncV2Codec.canonical(JSONObject().put("body",retained.get("body")).put("signature",retained.get("signature"))))
        val port=SyncV2LocalObjects(s,reopened);assertEquals("present",port.haveWant(want(f,port,oid)).getJSONArray("objects").getJSONObject(0).getString("status"))
        assertArrayEquals(raw,reopened.objectBytes(refs));for(i in 0 until blobs.length())assertTrue(reopened.objectSize(blobs.getString(i))>0)
    }}
    @Test fun malformedCommitRepresentationCannotOccupyValidOidOrBlockDifferentLengthRepair() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val (oid,good,_)=commit(f)
        for((index,bad)in listOf(SyncV2LocalProtocol.bytes(JSONObject().put("body",42).put("signature","bad")),byteArrayOf('{'.code.toByte())).withIndex()) {
            val store=f.store("p4r-malformed-$index");val port=SyncV2LocalObjects(s,store,verifyCommit={f.authors().header(it,false)})
            p4Error(if(index==0)"invalid_schema" else "invalid_encoding"){port.putPart(frame(s,oid,bad),transfer)}
            assertEquals(0,f.store("p4r-malformed-$index").prefix(oid,bad.size))
            port.putPart(frame(s,oid,good),transfer);assertArrayEquals(good,store.objectBytes(oid))
        }
    }}
    @Test fun missingAuthorityDoesNotDeleteDurableBytesOrWriteQuarantine() {SyncV2P4Fixture().use{f->
        val (oid,good,_)=commit(f);val store=f.store("p4r-authority");store.appendLocal(oid,good.size,0,good,JSONObject());val writes=store.counts.writes
        p4Error("authentication_required"){store.finish(oid,good.size,{SyncV2Codec.fail("authentication_required")})}
        assertEquals(writes,store.counts.writes);assertEquals(good.size,store.prefix(oid,good.size))
        store.finish(oid,good.size,{f.authors().header(it,false)});assertArrayEquals(good,store.objectBytes(oid))
    }}
    @Test fun knownRefsMatchesCloudSchemaIncludingNullableOidAnd65EntryLimit() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("p4r-known");val (oid,_,_)=commit(f)
        fun row()=JSONObject().put("ref","refs/account/current").put("oid",JSONObject.NULL).put("revision","0")
        fun request(known:Any)=JSONObject().put("scope",s.scope).put("knownRefs",known).put("features",JSONArray())
        val valid=row().put("oid",oid).put("revision","18446744073709551615")
        SyncV2LocalObjects(s,store).openWindow(request(JSONArray(listOf(row(),valid))))
        SyncV2LocalObjects(s,store).openWindow(request(JSONArray((0 until 65).map{row()})))
        val invalid=listOf(JSONObject.NULL,JSONObject(),JSONArray(listOf(JSONObject())),JSONArray(listOf(1)),JSONArray(listOf(row().put("extra",0))),
            JSONArray(listOf(row().put("ref",7))),JSONArray(listOf(row().put("oid",1))),JSONArray(listOf(row().put("oid","blob:sha256:"+"ff".repeat(32)))),
            JSONArray(listOf(row().put("revision",0))),JSONArray(listOf(row().put("revision","01"))),JSONArray(listOf(row().put("revision","18446744073709551616"))),JSONArray((0 until 66).map{row()}))
        for(value in invalid)p4Error("invalid_schema"){SyncV2LocalObjects(s,store).openWindow(request(value))}
    }}
    @Test fun firstHelloFeatureIntersectionIsFrozenAndEveryReadRevocationClosesSession() {SyncV2P4Fixture().use{f->
        val supported=mutableListOf<String>();val x=f.session(features=supported);val y=f.session(f.b,f.a,features=listOf("witness"))
        val hx=x.hello();val hy=y.hello();x.receiveHello(hy);y.receiveHello(hx);x.receiveProof(y.ownProof());y.receiveProof(x.ownProof());supported.add("witness")
        p4Error("feature_missing"){SyncV2LocalObjects(x,f.store("p4r-feature")).openWindow(JSONObject().put("scope",x.scope).put("knownRefs",JSONArray()).put("features",JSONArray(listOf("witness"))))}
        for(action in listOf("window","haveWant","objectGet","reference","treeDiff")) {
            var revoked=false;var closed=false
            val a=f.session(member={if(it==f.b && revoked)"revoked" else "active"},close={closed=true});val b=f.session(f.b,f.a)
            val ha=a.hello();val hb=b.hello();a.receiveHello(hb);b.receiveHello(ha);a.receiveProof(b.ownProof());b.receiveProof(a.ownProof())
            val id="77".repeat(16);a.acceptReady(b.prepare(a.beginSwitch(id)));b.commitRequest(JSONObject().put("switchId",id));b.finalAckWritten(id);a.acceptCommit(JSONObject().put("switchId",id).put("committed",true))
            val store=f.store("p4r-revoked-$action");store.save("legacy-cursor",JSONObject().put("received","R").put("applied","A"))
            val port=SyncV2LocalObjects(a,store);val request=JSONObject().put("scope",a.scope).put("windowId",port.openWindow().getString("windowId"));val writes=store.counts.writes;revoked=true
            p4Error("member_revoked"){when(action){"window"->port.openWindow();"haveWant"->port.haveWant(request);"objectGet"->port.objectGet(request);"reference"->port.reference("syncObjectsV2.stateRefsGet",request);else->port.treeDiff(request)}}
            assertFalse(closed);assertFalse(a.isCommitted);a.close();assertTrue(closed);assertEquals(writes,store.counts.writes);revoked=false
            p4Error("authentication_required"){port.haveWant(request)};assertEquals("A",store.load("legacy-cursor")!!.getString("applied"))
        }
    }}
    @Test fun sevenFieldFrameRoundTripRejectsExtensionsAndPayloadHashMismatch() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val (oid,raw)=f.blob(40);val out=ByteArrayOutputStream();SyncV2Wrp2.write(out,s,oid,raw.size,0,raw)
        val decoded=SyncV2Wrp2.read(ByteArrayInputStream(out.toByteArray()),s)
        assertEquals(setOf("scope","sessionId","oid","totalBytes","offset","partBytes","partSha256"),decoded.header.keys().asSequence().toSet());assertArrayEquals(raw,decoded.payload)
        p4Error("invalid_schema"){SyncV2Wrp2.validate(s,JSONObject(decoded.header.toString()).put("transferId",transfer),raw)}
        for(key in decoded.header.keys())p4Error("invalid_schema"){SyncV2Wrp2.validate(s,JSONObject(decoded.header.toString()).apply{remove(key)},raw)}
        p4Error("hash_mismatch"){SyncV2Wrp2.validate(s,decoded.header,raw.copyOf().also{it[0]=(it[0].toInt() xor 1).toByte()})}
    }}
}
