package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal fun p4Error(code:String,block:()->Unit) {val error=runCatching(block).exceptionOrNull();assertNotNull("expected $code",error);assertEquals(code,error!!.message)}
class SyncV2P4ProtocolTest {
    @Test fun negotiationFrozenMatrixBothDirections() {
        val cases=SyncV2Fixtures.vector("negotiation").getJSONArray("cases")
        for(i in 0 until cases.length()) {val c=cases.getJSONObject(i)
            for((a,b)in listOf("a" to "b","b" to "a"))assertEquals(c.getString("expected"),SyncV2LocalProtocol.select(c.getJSONObject(a),c.getJSONObject(b),c.getBoolean("scopeEqual"),c.getBoolean("safeLegacy")))
        }
    }
    @Test fun c25FrozenProofAndFivePreciseErrors() {
        val v=SyncV2Fixtures.vector("local-proof-c25");val key=SyncV2Codec.unhex(v.getJSONObject("derivation").getString("repoKeyHex"),32)
        assertEquals(v.getJSONObject("derivation").getString("proofKeyHex"),SyncV2Codec.hex(SyncV2Codec.hkdf(key,ByteArray(32),SyncV2Codec.utf8("wrss.sync.v2/local-proof"))))
        val cases=v.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val input=c.getJSONObject("input");val expected=c.getJSONObject("expected")
            val features=SyncV2Wire.strings(input.getJSONArray("supportedFeatures"),32)
            val session=SyncV2LocalSession(input.getJSONObject("expectedScope"),input.getString("sessionId"),input.getString("peerDeviceId"),{key.copyOf()},
                {input.getString("memberStatus")},{true},{},SyncV2LocalProtocol.Gates(true),SyncV2Wire.strings(input.getJSONArray("requiredFeatures"),32),features)
            val operation={
                session.hello(input.getString("peerNonce"))
                val hello=JSONObject().put("scope",input.get("scope")).put("deviceId",input.get("selfDeviceId")).put("nonce",input.get("selfNonce")).put("features",JSONArray(features))
                session.receiveHello(hello)
                if(input.getJSONArray("seenNonces").length()>0)session.receiveHello(hello)
                session.receiveProof(input.getString("proofBase64"))
            }
            if(expected.getBoolean("accepted")) {operation();session.requireProof()
                assertEquals(input.getString("proofBase64"),SyncV2LocalProtocol.proof(key,input.getJSONObject("scope"),input.getString("sessionId"),input.getString("selfDeviceId"),input.getString("peerDeviceId"),input.getString("selfNonce"),input.getString("peerNonce")))
            }else p4Error(expected.getString("error"),operation)
            session.close()
        }
    }
    @Test fun frozenFramingAndB08NeverEnableFromCommitAckAlone() {
        val cases=SyncV2Fixtures.vector("rc3-contracts").getJSONArray("framing")
        for(i in 0 until cases.length()){val c=cases.getJSONObject(i);assertEquals(c.getBoolean("expected"),SyncV2LocalProtocol.framingAllowed(c.getJSONObject("input")))}
        val cases2=SyncV2Fixtures.vector("rc3-review").getJSONArray("cases")
        val b08=(0 until cases2.length()).map{cases2.getJSONObject(it)}.single{it.getString("id")=="B08"}
        assertEquals(b08.getJSONObject("expected").getBoolean("enabled"),SyncV2LocalProtocol.framingAllowed(b08.getJSONObject("input").getJSONObject("state")))
    }
    @Test fun allMalformedSwitchIdsAndOldSessionAckFail() {SyncV2P4Fixture().use{f->
        val s=f.session();p4Error("proof_invalid"){s.beginSwitch("11".repeat(16))}
        val (x,_)=f.pair();x.close();p4Error("authentication_required"){x.acceptCommit(JSONObject().put("switchId","dd".repeat(16)).put("committed",true))}
        val base=JSONObject().put("authenticated",true).put("scopeMatched",true).put("ready",true).put("commitAck",true).put("sessionCommitted",true)
        for(id in listOf(null,"","a","AA".repeat(16),"gg".repeat(16))) {
            val value=JSONObject(base.toString());if(id!=null)value.put("switchId",id).put("ackSwitchId",id)
            assertFalse(SyncV2LocalProtocol.framingAllowed(value))
        }
        assertFalse(SyncV2LocalProtocol.framingAllowed(JSONObject(base.toString()).put("switchId","11".repeat(16)).put("ackSwitchId","22".repeat(16))))
    }}
    @Test fun gateFalseAndMissingCapabilitiesEmitZeroBytes() {SyncV2P4Fixture().use{f->
        val output=ByteArrayOutputStream();val s=SyncV2LocalSession(f.identity.scope,f.sid,f.a,{f.key.copyOf()},{"active"},{true},{})
        val c=SyncV2LocalConnection(s,ByteArrayInputStream(byteArrayOf()),output)
        val cap=SyncV2LocalProtocol.capability(f.identity.scope,SyncV2LocalProtocol.Gates(true))
        p4Error("feature_disabled"){c.initiate(cap,cap)};assertEquals(0,output.size())
        val enabled=SyncV2LocalConnection(f.session(),ByteArrayInputStream(byteArrayOf()),output)
        p4Error("upgrade_required"){enabled.initiate(cap,JSONObject())};assertEquals(0,output.size())
    }}
    @Test fun wrp2RejectsHashScopeAndOldSessionBeforePersistence() {SyncV2P4Fixture().use{f->
        val (s,_)=f.pair();val (oid,raw)=f.blob();val out=ByteArrayOutputStream();SyncV2Wrp2.write(out,s,oid,raw.size,0,raw.copyOf(65536))
        val good=out.toByteArray();val corrupt=good.copyOf().also{it[it.lastIndex]=(it.last()+1).toByte()}
        p4Error("hash_mismatch"){SyncV2Wrp2.read(ByteArrayInputStream(corrupt),s)}
        s.close();p4Error("authentication_required"){SyncV2Wrp2.read(ByteArrayInputStream(good),s)}
        val fresh=f.session();p4Error("proof_invalid"){SyncV2Wrp2.read(ByteArrayInputStream(good),fresh)}
    }}
    @Test fun proofCannotReplayAcrossSessionOrPeer() {SyncV2P4Fixture().use{f->
        val x=f.session();val y=f.session(f.b,f.a,session="ee".repeat(16));val hx=x.hello();val hy=y.hello();x.receiveHello(hy);y.receiveHello(hx)
        p4Error("proof_invalid"){x.receiveProof(y.ownProof())}
        p4Error("proof_invalid"){x.receiveHello(hy)}
    }}
    @Test fun realFramingStateRequiresReadyAckCommitRequestAndFinalAck() {SyncV2P4Fixture().use{f->
        val x=f.session();val y=f.session(f.b,f.a);val hx=x.hello();val hy=y.hello();x.receiveHello(hy);y.receiveHello(hx);x.receiveProof(y.ownProof());y.receiveProof(x.ownProof())
        val prepare=x.beginSwitch("ff".repeat(16));val ready=y.prepare(prepare)
        val ack=JSONObject().put("switchId","ff".repeat(16)).put("committed",true)
        p4Error("invalid_schema"){x.acceptCommit(ack)};p4Error("invalid_schema"){y.finalAckWritten("ff".repeat(16))}
        assertFalse(x.isCommitted);assertFalse(y.isCommitted)
        x.acceptReady(ready);y.commitRequest(JSONObject().put("switchId","ff".repeat(16)));y.finalAckWritten("ff".repeat(16));x.acceptCommit(ack)
        assertTrue(x.isCommitted);assertTrue(y.isCommitted)
    }}
    @Test fun proofDoesNotEraseInstalledRepoKeyOrAcceptScopeMutation() {SyncV2P4Fixture().use{f->
        val shared=f.key.copyOf();val scope=JSONObject(f.identity.scopeJson)
        val x=SyncV2LocalSession(scope,f.sid,f.a,{shared},{"active"},{true},{},SyncV2LocalProtocol.Gates(true));val y=f.session(f.b,f.a)
        val hx=x.hello();val hy=y.hello();x.receiveHello(hy);y.receiveHello(hx);x.receiveProof(y.ownProof());y.receiveProof(x.ownProof())
        assertArrayEquals(f.key,shared);scope.put("contentGeneration","999");assertEquals(f.identity.scopeJson,SyncV2Codec.scope(x.scope))
    }}

}
