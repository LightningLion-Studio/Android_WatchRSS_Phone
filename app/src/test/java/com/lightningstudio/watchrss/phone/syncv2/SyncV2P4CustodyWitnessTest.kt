package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncV2P4CustodyWitnessTest {
    private fun pin(f:SyncV2P4Fixture,descriptor:JSONObject):JSONObject {
        val blobs=JSONArray();val blocks=descriptor.getJSONObject("recipe").getJSONArray("blocks")
        for(i in 0 until blocks.length())blobs.put(blocks.getJSONObject(i).getString("oid"))
        return JSONObject().put("version",2).put("scope",f.identity.scope).put("pinId","66".repeat(16)).put("holderDeviceId",f.a).put("claimantDeviceId",f.b)
            .put("entityKey","11".repeat(32)).put("bodyRevision","22".repeat(16)).put("recipeOid",descriptor.getString("recipeOid")).put("blobOidsHash",SyncV2Codec.hash(blobs)).put("createdAtMillis",100)
    }
    private fun installRefs(f:SyncV2P4Fixture,session:SyncV2LocalSession,store:SyncV2ContentStore,descriptor:JSONObject) {
        val oid=descriptor.getString("recipeOid");val blobs=JSONArray();val blocks=descriptor.getJSONObject("recipe").getJSONArray("blocks")
        for(i in 0 until blocks.length())blobs.put(blocks.getJSONObject(i).getString("oid"))
        val raw=SyncV2LocalProtocol.bytes(SyncV2ContentCodec.refs(f.identity,f.signer,oid,blobs));val refs="recipe-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}"
        val mapping=JSONObject().put("scope",f.identity.scope).put("parentOid",oid).put("refsOid",refs).put("authorDeviceId",f.a).put("keyId",f.signer.keyId).put("totalBytes",raw.size)
        SyncV2LocalObjects(session,store,contentKey={f.key}).registerRefs(f.identity,f.authors(),oid,blobs,mapping,raw)
    }
    @Test fun custodyRequiresCompleteAuthenticatedBodyAndSurvivesRestartWithoutExpiry() {SyncV2P4Fixture().use{f->
        val (holder,claimant)=f.pair();val store=f.store("custody");val descriptor=f.bodyPackage(store);installRefs(f,holder,store,descriptor)
        val body=pin(f,descriptor);val custody=SyncV2Custody(f.identity,store,f.signer,f.authors()){f.key}
        val signed=custody.persist(holder,body);assertEquals(f.a,SyncV2Custody.verify(signed,f.identity.scope,f.authors()).getString("holderDeviceId"))
        val receiver=SyncV2Custody(f.identity,f.store("claimant"),f.peerSigner,f.authors()){f.key};val receipt=receiver.receive(claimant,signed)
        assertEquals("active",receipt.getString("state"));assertEquals(SyncV2Codec.canonical(receipt),SyncV2Codec.canonical(receiver.receive(claimant,signed)))
        holder.close();claimant.close()
        val reopened=SyncV2Custody(f.identity,f.store("custody"),f.signer,f.authors()){f.key};val durable=reopened.read(body.getString("pinId"))
        assertEquals("active",durable.getString("state"));assertFalse(durable.getJSONObject("body").has("expiresAtMillis"))
        p4Error("feature_disabled"){reopened.release(body.getString("pinId"))}
    }}
    @Test fun custodySameBodyIsIdempotentDifferentBodyCannotOverwrite() {SyncV2P4Fixture().use{f->
        val (holder,_)=f.pair();val store=f.store("pin");val descriptor=f.bodyPackage(store);installRefs(f,holder,store,descriptor)
        val body=pin(f,descriptor);val custody=SyncV2Custody(f.identity,store,f.signer,f.authors()){f.key}
        val first=custody.persist(holder,body);assertEquals(SyncV2Codec.canonical(first),SyncV2Codec.canonical(custody.persist(holder,body)))
        p4Error("idempotency_conflict"){custody.persist(holder,JSONObject(body.toString()).put("createdAtMillis",101))}
        assertEquals(100,custody.read(body.getString("pinId")).getJSONObject("body").getInt("createdAtMillis"))
    }}
    @Test fun receiveAckAndMissingOrCorruptBodyNeverCreateCustody() {SyncV2P4Fixture().use{f->
        val (holder,_)=f.pair();val store=f.store("missing");val descriptor=f.bodyPackage(store);val custody=SyncV2Custody(f.identity,store,f.signer,f.authors()){f.key}
        p4Error("closure_incomplete"){custody.persist(holder,pin(f,descriptor))}
        val local=JSONObject().put("kind","local").put("oid",descriptor.get("recipeOid")).put("durableOffset",42).put("totalBytes",42)
        SyncV2LocalObjects(holder,store).localReceipt(local)
        p4Error("object_missing"){custody.read("66".repeat(16))}
        p4Error("invalid_schema"){SyncV2Custody.verify(local,f.identity.scope,f.authors())}
    }}
    @Test fun custodyCrashAfterDurablePinRetainsFirstResultAndChangedPinFails() {SyncV2P4Fixture().use{f->
        val (holder,_)=f.pair();val base=f.store("crashpin");val descriptor=f.bodyPackage(base);installRefs(f,holder,base,descriptor)
        var once=true;val crashing=f.store("crashpin"){if(it=="checkpoint" && once){once=false;throw IllegalStateException("simulated_crash")}}
        val body=pin(f,descriptor);p4Error("simulated_crash"){SyncV2Custody(f.identity,crashing,f.signer,f.authors()){f.key}.persist(holder,body)}
        val recovered=SyncV2Custody(f.identity,base,f.signer,f.authors()){f.key};assertEquals("active",recovered.read(body.getString("pinId")).getString("state"))
        recovered.persist(holder,body);p4Error("idempotency_conflict"){recovered.persist(holder,JSONObject(body.toString()).put("entityKey","33".repeat(32)))}
    }}
    private fun header(f:SyncV2P4Fixture,sequence:String="1",parent:String?=null):JSONObject {
        val b=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("scope",f.identity.scope).put("authorDeviceId",f.a).put("deviceSequence",sequence)
            .put("accountParent",parent?:JSONObject.NULL).put("previousOwnCommit",parent?:JSONObject.NULL).put("mergedParents",JSONArray()).put("rollbackGeneration","0")
            .put("operation",JSONObject().put("kind","normal").put("rollbackTarget",JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
        return f.signer.commit(b)
    }
    private fun wire(f:SyncV2P4Fixture,headers:List<JSONObject>):JSONObject {
        val a=JSONArray();for(h in headers)a.put(JSONObject(h.toString()).put("commitOid",SyncV2Codec.oid("commit",h.getJSONObject("body"))).put("headerOid","commit-header:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(SyncV2LocalProtocol.bytes(h)))}"))
        return JSONObject().put("scope",f.identity.scope).put("head",SyncV2Codec.oid("commit",headers.first().getJSONObject("body"))).put("headers",a)
    }
    @Test fun peerWitnessStoresSeparatelyAndRejectsUnknownForkAndB07ExtraFuture() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair(listOf("witness"));val store=f.store("witness");store.save("accepted",JSONObject().put("unchanged",true))
        val first=header(f);val firstOid=SyncV2Codec.oid("commit",first.getJSONObject("body"));val witness=SyncV2PeerWitness(f.identity,f.authors(),store,true)
        witness.receive(s,wire(f,listOf(first)))
        val second=header(f,"2",firstOid);val extra=header(f,"99",firstOid)
        witness.receive(s,wire(f,listOf(second,first,extra)))
        assertEquals("2",store.load("peer-witness/${f.b}")!!.getJSONObject("knownAuthors").getJSONObject(f.a).getString("sequence"))
        assertTrue(store.load("accepted")!!.getBoolean("unchanged"))
        p4Error("ref_continuity_unavailable"){witness.receive(s,wire(f,listOf(extra,first)))}
    }}
    @Test fun witnessRequiresFeatureAndCurrentAuthentication() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val witness=SyncV2PeerWitness(f.identity,f.authors(),f.store("nogate"),true)
        p4Error("feature_missing"){witness.receive(s,wire(f,listOf(header(f))))}
        val (enabled,_)=f.pair(listOf("witness"));enabled.close();p4Error("authentication_required"){witness.receive(enabled,wire(f,listOf(header(f))))}
    }}
}
