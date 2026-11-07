package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncV2P4ControlTest {
    private fun account()=JSONObject().put("commitOid",JSONObject.NULL).put("revision","0").put("rollbackGeneration","0")
    @Test fun refUpdateRejectsPreC29ShapeWithoutChangingAcceptedSnapshot() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val store=f.store("control")
        val queue=SyncV2RelayJobs(f.identity,f.authors(),store,f.a,true,{100})
        val control=SyncV2LocalControl(s,f.identity,f.authors(),store,{account()},{100},queue)
        p4Error("invalid_schema"){control.refUpdate(JSONObject().put("body",JSONObject()).put("signature","old-shape"))}
        assertTrue(control.snapshot().getJSONObject("account").isNull("commitOid"));assertEquals(0,control.snapshot().getJSONArray("refs").length());assertEquals(0,queue.pending())
    }}
    @Test fun localRank0DemandBindsPeerExpiresAndRejectsConflictingRetry() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();var time=100L;val control=SyncV2LocalControl(s,f.identity,f.authors(),f.store("demand"),{account()},{time})
        val body=JSONObject().put("scope",f.identity.scope).put("entityKey","11".repeat(32)).put("bodyRevision","22".repeat(16)).put("requesterDeviceId",f.b)
            .put("demandId","33".repeat(16)).put("demandAtMillis",time).put("expiresAtMillis",200)
        control.demand(body);control.demand(body);assertEquals(1,control.demands().size)
        p4Error("idempotency_conflict"){control.demand(JSONObject(body.toString()).put("expiresAtMillis",201))}
        p4Error("authentication_required"){control.demand(JSONObject(body.toString()).put("requesterDeviceId",f.a))}
        p4Error("invalid_schema"){control.demand(JSONObject(body.toString()).put("rank",1))}
        time=200;assertTrue(control.demands().isEmpty());p4Error("invalid_schema"){control.demand(body)}
    }}
}
