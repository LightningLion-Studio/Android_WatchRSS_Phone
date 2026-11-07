package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncV2P4ControlTest {
    private fun account()=JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")
    @Test fun signedLocalDeviceRefUsesClosureCasAndDurableIdempotencyWithoutApplyingAccount() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("control");val authors=f.authors();val tree=SyncV2Index.build(f.identity.scope,emptyList());tree.objects.forEach{(oid,raw)->store.putObject(oid,raw)}
        val body=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("scope",f.identity.scope).put("authorDeviceId",f.b).put("deviceSequence","1").put("treeRoot",tree.root)
            .put("previousOwnCommit",JSONObject.NULL).put("accountParent",JSONObject.NULL).put("mergedParents",JSONArray()).put("rollbackGeneration","0")
            .put("operation",JSONObject().put("kind","normal").put("rollbackTarget",JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
        val signed=f.peerSigner.commit(body);val oid=SyncV2Codec.oid("commit",body);store.putObject(oid,SyncV2LocalProtocol.bytes(signed)){authors.header(it,false)}
        val request=SyncV2CloudClient.refUpdate(f.peerSigner,f.identity.scope,"refs/devices/${f.b}/index",null,"0",oid,"77".repeat(16),100)
        val control=SyncV2LocalControl(s,f.identity,authors,store,{f.key},{account()},{100})
        val first=control.refUpdate(request);assertEquals("1",first.getString("refRevision"));assertEquals(SyncV2Codec.canonical(first),SyncV2Codec.canonical(control.refUpdate(request)))
        val resumed=SyncV2LocalControl(s,f.identity,authors,f.store("control"),{f.key},{account()},{500000})
        assertEquals(SyncV2Codec.canonical(first),SyncV2Codec.canonical(resumed.refUpdate(request)))
        val conflict=f.peerSigner.signed("wrss.sync.v2/ref-update-sign",JSONObject(request.getJSONObject("body").toString()).put("issuedAtMillis",101))
        p4Error("idempotency_conflict"){control.refUpdate(conflict)}
        assertTrue(control.snapshot().getJSONObject("account").isNull("commitOid"));assertEquals(1,control.snapshot().getJSONArray("refs").length())
        p4Error("relay_denied"){control.refUpdate(JSONObject(request.toString()).put("relayGrant",JSONObject()))}
    }}
    @Test fun localRank0DemandBindsPeerExpiresAndRejectsConflictingRetry() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();var time=100L;val control=SyncV2LocalControl(s,f.identity,f.authors(),f.store("demand"),{f.key},{account()},{time})
        val body=JSONObject().put("scope",f.identity.scope).put("entityKey","11".repeat(32)).put("bodyRevision","22".repeat(16)).put("requesterDeviceId",f.b)
            .put("demandId","33".repeat(16)).put("demandAtMillis",time).put("expiresAtMillis",200)
        control.demand(body);control.demand(body);assertEquals(1,control.demands().size)
        p4Error("idempotency_conflict"){control.demand(JSONObject(body.toString()).put("expiresAtMillis",201))}
        p4Error("authentication_required"){control.demand(JSONObject(body.toString()).put("requesterDeviceId",f.a))}
        p4Error("invalid_schema"){control.demand(JSONObject(body.toString()).put("rank",1))}
        time=200;assertTrue(control.demands().isEmpty());p4Error("invalid_schema"){control.demand(body)}
    }}
}
