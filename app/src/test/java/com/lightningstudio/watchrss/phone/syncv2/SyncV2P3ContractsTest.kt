package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncV2P3ContractsTest {
    @get:Rule val temp=TemporaryFolder()
    private fun store(f:SyncV2P3Fixture)=SyncV2ContentStore(temp.newFolder(),f.identity.scopeJson,SyncV2Fixtures.localKey)
    private fun worker(f:SyncV2P3Fixture,s:SyncV2ContentStore)=SyncV2ContentWorker(f.client(),f.identity,s,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={f.now})
    @Test fun fixedC8FullPrefixAndPartialOffsetsFollowExpectedWithoutReplayingConfirmedBytes() {
        val v=SyncV2Fixtures.vector("have-want-complete-c8");val source=SyncV2Fixtures.vector(v.getString("sourceVector").removeSuffix(".json")).getJSONObject("input").getString("bytesHex")
        val bytes=SyncV2Codec.unhex(source,source.length/2);val oid="blob:sha256:"+SyncV2Codec.hex(SyncV2Codec.sha(bytes))
        val cases=v.getJSONArray("cases")
        for(index in 0..1)SyncV2P3Fixture().use { f ->
            val c=cases.getJSONObject(index);val expected=c.getJSONObject("expected");val s=store(f);s.putObject(oid,bytes);f.objects[oid]=bytes;f.prefix[oid]=bytes.copyOf(c.getInt("durableOffset"))
            assertEquals(c.getInt("totalBytes"),bytes.size)
            val client=f.client();client.capabilities();val response=client.haveWant("12".repeat(16),listOf(oid)).getJSONArray("objects").getJSONObject(0)
            assertEquals(expected.getString("status"),response.getString("status"));assertEquals(expected.getLong("durableOffset"),response.getLong("durableOffset"))
            assertEquals("complete",worker(f,s).upload(listOf(oid)).status)
            if(expected.getString("status")=="present") {assertTrue(f.putOffsets.isEmpty());assertEquals(0,f.completed)}
            else assertEquals(listOf(expected.getInt("durableOffset")),f.putOffsets)
            assertArrayEquals(bytes,s.objectBytes(oid))
        }
    }
    @Test fun fixedC8SettlementErrorsStopTheRoundAndRetainTheLocalObject() {
        val cases=SyncV2Fixtures.vector("have-want-complete-c8").getJSONArray("cases")
        for(index in 2..3)SyncV2P3Fixture().use { f ->
            val expected=cases.getJSONObject(index).getJSONObject("expected");val s=store(f)
            val sealed=SyncV2Envelope.seal(f.identity,ByteArray(32),1,"blob",byteArrayOf(1));s.putObject(sealed.oid,sealed.bytes)
            f.route={r->if(r.requestUrl!!.encodedPath.endsWith("/objects/have-want"))f.response(JSONObject().put("error",JSONObject().put("code",expected.getString("error")).put("retryable",index==2).put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(if(index==2)410 else 422)else null}
            assertEquals(expected.getString("error"),worker(f,s).upload(listOf(sealed.oid)).status);assertTrue(f.putOffsets.isEmpty());assertArrayEquals(sealed.bytes,s.objectBytes(sealed.oid))
        }
    }
    @Test fun absentContentFlagPreservesP2ReadsAndCannotOpenAContentReservation() {
        SyncV2P3Fixture().use { f ->
            f.route={r->if(r.requestUrl!!.encodedPath.endsWith("/capabilities"))f.response(f.dto().put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
                .put("receiptKeyId",f.bootstrap.getString("receiptKeyId")).put("receiptSigningPublicKey",f.bootstrap.getString("receiptSigningPublicKey")))else null}
            val c=f.client();c.capabilities();assertNotNull(c.negotiate());val before=f.requests
            try{c.reserve("11".repeat(16),listOf("blob:sha256:"+"22".repeat(32) to 1),"content");fail()}catch(e:IllegalStateException){assertEquals("feature_disabled",e.message)}
            assertEquals(before,f.requests)
        }
    }
    @Test fun fixedC9ActiveOrderScopeAndClosedGateUseUnchangedExpected() {
        SyncV2P3Fixture().use { f ->
            val v=SyncV2Fixtures.vector("demand-c9");val expected=v.getJSONObject("expected");val base=v.getLong("clockBaseMillis");val input=v.getJSONArray("rows")
            val byId=(0 until input.length()).map { i ->val row=JSONObject(input.getJSONObject(i).toString())
                row.put("demandAtMillis",base+row.getLong("demandAtOffsetMillis")).put("expiresAtMillis",base+row.getLong("expiresOffsetMillis"));row.remove("demandAtOffsetMillis");row.remove("expiresOffsetMillis");row.getString("demandId") to row
            }.toMap()
            val ids=expected.getJSONArray("activeDemandIds");var values=JSONArray((0 until ids.length()).map{byId.getValue(ids.getString(it))});var wrongScope=false
            f.route={r->if(r.method=="GET" && r.requestUrl!!.encodedPath.endsWith("/demand")) {
                assertEquals(setOf("repoId","libraryEpoch","contentGeneration"),r.requestUrl!!.queryParameterNames)
                f.response(f.dto().put("scope",if(wrongScope)JSONObject(f.identity.scopeJson).put("repoId","ff".repeat(16))else f.identity.scope).put("serverNowMillis",base).put("demands",values))
            }else null}
            val s=store(f);val c=f.client();c.capabilities();val w=SyncV2ContentWorker(c,f.identity,s,{SyncV2TransportSignals(wifi=true,internet=true,cellular=false)},enabled=true,now={base},approvedDevice={true})
            val actual=JSONArray(w.pollDemands(true).map{it.getString("demandId")});assertEquals(SyncV2Codec.canonical(ids),SyncV2Codec.canonical(actual))
            assertEquals(expected.getInt("maxActive"),32)
            values=JSONArray().put(byId.getValue(ids.getString(1))).put(byId.getValue(ids.getString(0)))
            try{w.pollDemands(true);fail()}catch(e:IllegalStateException){assertEquals("invalid_schema",e.message)}
            wrongScope=true;try{w.pollDemands(true);fail()}catch(e:IllegalStateException){assertEquals("scope_mismatch",e.message)}
            val closed=SyncV2ContentWorker(c,f.identity,s,{SyncV2TransportSignals()},enabled=false)
            try{closed.pollDemands(true);fail()}catch(e:IllegalStateException){assertEquals(expected.getString("closedGateError"),e.message)}
        }
    }
}
