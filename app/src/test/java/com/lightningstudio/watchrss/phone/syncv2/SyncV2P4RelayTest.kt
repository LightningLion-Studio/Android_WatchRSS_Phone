package com.lightningstudio.watchrss.phone.syncv2

import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncV2P4RelayTest {
    private fun commit(f:SyncV2P4Fixture):JSONObject {
        val body=JSONObject(SyncV2Fixtures.envelope().getJSONObject("body").toString()).put("scope",f.identity.scope).put("authorDeviceId",f.b)
            .put("deviceSequence","1").put("previousOwnCommit",JSONObject.NULL).put("accountParent",JSONObject.NULL).put("mergedParents",JSONArray()).put("rollbackGeneration","0")
            .put("operation",JSONObject().put("kind","normal").put("rollbackTarget",JSONObject.NULL).put("resolvesGeneration",JSONObject.NULL))
        return f.peerSigner.commit(body)
    }
    private fun grant(f:SyncV2P4Fixture,commit:JSONObject,now:Long,oids:List<String> = emptyList()):JSONObject = f.peerSigner.signed("wrss.sync.v2/relay-grant-sign",JSONObject()
        .put("version",2).put("scope",f.identity.scope).put("authorDeviceId",f.b).put("relayDeviceId",f.a).put("ref","refs/devices/${f.b}/index")
        .put("commitOid",SyncV2Codec.oid("commit",commit.getJSONObject("body"))).put("objectOids",JSONArray(oids.sorted())).put("expiresAtMillis",now+600000).put("grantId","55".repeat(16)))
    private fun update(f:SyncV2P4Fixture,c:JSONObject,now:Long)=SyncV2CloudClient.refUpdate(f.peerSigner,f.identity.scope,"refs/devices/${f.b}/index",null,"0",SyncV2Codec.oid("commit",c.getJSONObject("body")),"66".repeat(16),now)
    @Test fun statelessGrantBindsScopeRefCommitTimeAndOriginalAuthorSignature() {SyncV2P4Fixture().use{f->SyncV2P3Fixture().use{cloud->
        val relay=SyncV2Relay(f.identity,f.authors(),cloud.client(),f.store("relay"),true);val c=commit(f);val g=grant(f,c,cloud.now);val u=update(f,c,cloud.now)
        relay.validate(g,c,u,cloud.now)
        fun changed(field:String,value:Any):JSONObject=f.peerSigner.signed("wrss.sync.v2/relay-grant-sign",JSONObject(g.getJSONObject("body").toString()).put(field,value))
        p4Error("relay_denied"){relay.validate(changed("expiresAtMillis",cloud.now-30001),c,u,cloud.now)}
        p4Error("relay_denied"){relay.validate(changed("expiresAtMillis",cloud.now+630001),c,u,cloud.now)}
        p4Error("relay_denied"){relay.validate(changed("ref","refs/devices/${f.a}/index"),c,u,cloud.now)}
        p4Error("relay_denied"){relay.validate(changed("commitOid","commit:sha256:"+"ff".repeat(32)),c,u,cloud.now)}
        p4Error("scope_mismatch"){relay.validate(changed("scope",JSONObject(f.identity.scopeJson).put("contentGeneration","999")),c,u,cloud.now)}
        p4Error("signature_invalid"){relay.validate(JSONObject(g.toString()).put("signature",f.signer.sign(byteArrayOf(1))),c,u,cloud.now)}
        p4Error("invalid_schema"){relay.validate(changed("objectOids",JSONArray(listOf("blob:sha256:"+"ff".repeat(32),"blob:sha256:"+"00".repeat(32)))),c,u,cloud.now)}
    }}}
    @Test fun revokedAuthorAndRelayAndUnapprovedLocalOnlyAuthorCannotRelay() {SyncV2P4Fixture().use{f->SyncV2P3Fixture().use{cloud->
        val authors=f.authors();val relay=SyncV2Relay(f.identity,authors,cloud.client(),f.store("revoked"),true);val c=commit(f);val g=grant(f,c,cloud.now);val u=update(f,c,cloud.now)
        f.active.remove(f.b);p4Error("device_revoked"){relay.validate(g,c,u,cloud.now)}
        f.active.add(f.b);f.active.remove(f.a);p4Error("device_revoked"){relay.validate(g,c,u,cloud.now)}
        f.active.add(f.a)
        val localOnly=SyncV2Authors(f.identity){true};val blocked=SyncV2Relay(f.identity,localOnly,cloud.client(),f.store("unapproved"),true)
        p4Error("authentication_required"){blocked.validate(g,c,u,cloud.now)}
        p4Error("feature_disabled"){SyncV2Relay(f.identity,authors,cloud.client(),f.store("closed")).validate(g,c,u,cloud.now)}
    }}}
    @Test fun relayUploadsOriginalCiphertextAndSignedCommitUsingOwnSessionAndSkipsConfirmedObjects() {SyncV2P4Fixture().use{f->SyncV2P3Fixture().use{cloud->
        val client=cloud.client();val c=commit(f);val oid=SyncV2Codec.oid("commit",c.getJSONObject("body"));val (blob,raw)=f.blob()
        val source=f.store("upload");source.putObject(blob,raw)
        val expected=mapOf(oid to SyncV2LocalProtocol.bytes(c),blob to raw);val prefixes=mutableMapOf<String,ByteArray>();val puts=mutableListOf<String>();var stages=0
        val g=grant(f,c,cloud.now,listOf(blob));val u=update(f,c,cloud.now)
        cloud.route={r->
            assertEquals("Bearer synthetic",r.getHeader("Authorization"));assertEquals("synthetic-phone",r.getHeader("x-watchrss-cloud-device-id"))
            val path=r.requestUrl!!.encodedPath.substringAfter("/functions/v1/sync/v2/")
            when {
                path=="capabilities"->cloud.response(cloud.dto().put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
                    .put("receiptKeyId",cloud.bootstrap.get("receiptKeyId")).put("receiptSigningPublicKey",cloud.bootstrap.get("receiptSigningPublicKey")).put("flags",JSONObject().put("read",true).put("contentWrite",true).put("relay",true)))
                path=="objects/have-want"->{val body=SyncV2Wire.parse(r.body.readByteArray());val rows=JSONArray();val oids=body.getJSONArray("oids")
                    for(i in 0 until oids.length()){val id=oids.getString(i);val total=expected.getValue(id).size;val off=prefixes[id]?.size?:0
                        rows.put(JSONObject().put("oid",id).put("status",if(off==total)"present" else if(off>0)"partial" else "missing").put("totalBytes",if(off==0)JSONObject.NULL else total).put("durableOffset",off).put("receipt",if(off==0)JSONObject.NULL else cloud.receipt(id,total,off)))}
                    cloud.response(cloud.dto().put("windowId",body.get("windowId")).put("objects",rows))}
                path=="objects/reserve"->cloud.response(cloud.dto().put("reservationId","77".repeat(16)))
                path.contains("/parts/")->{val id=path.substringAfter("objects/").substringBefore("/parts/");val offset=path.substringAfter("/parts/").toInt();assertEquals(prefixes[id]?.size?:0,offset)
                    val part=r.body.readByteArray();prefixes[id]=(prefixes[id]?:byteArrayOf())+part;puts.add(id)
                    cloud.response(cloud.dto().put("oid",id).put("durableOffset",prefixes[id]!!.size).put("receipt",cloud.receipt(id,expected.getValue(id).size,prefixes[id]!!.size)))}
                path.endsWith("/complete")-> {val id=path.substringAfter("objects/").substringBefore("/complete");assertArrayEquals(expected.getValue(id),prefixes[id]);cloud.response(cloud.dto().put("oid",id).put("receipt",cloud.receipt(id,expected.getValue(id).size,expected.getValue(id).size)))}
                path=="refs/cas"->{val request=SyncV2Wire.parse(r.body.readByteArray());assertEquals(SyncV2Codec.canonical(u.getJSONObject("body")),SyncV2Codec.canonical(request.getJSONObject("body")))
                    assertEquals(u.get("signature"),request.get("signature"));assertEquals(SyncV2Codec.canonical(g),SyncV2Codec.canonical(request.getJSONObject("relayGrant")));stages++;cloud.response(cloud.dto().put("accepted",true))}
                else->null
            }
        }
        p4Error("transport_wait"){SyncV2Relay(f.identity,f.authors(),client,source,true).forward(c,u,g,null,cloud.now)}
        assertEquals(0,puts.size);assertEquals(0,stages)
        val relay=SyncV2Relay(f.identity,f.authors(),client,source,true,signals={SyncV2TransportSignals(wifi=true,internet=true,cellular=false)})
        p4Error("budget_paused"){relay.forward(c,u,g,null,cloud.now,SyncV2ContentWorker.Budget(bytes=65536))}
        assertEquals(65536,prefixes[blob]!!.size);assertEquals(0,stages)
        relay.forward(c,u,g,null,cloud.now);val before=puts.size
        relay.forward(c,u,g,null,cloud.now);assertEquals(before,puts.size);assertEquals(2,stages)
        assertArrayEquals(raw,prefixes[blob]);assertArrayEquals(SyncV2LocalProtocol.bytes(c),prefixes[oid]);assertEquals(0L,source.counts.seals)
    }}}
}
