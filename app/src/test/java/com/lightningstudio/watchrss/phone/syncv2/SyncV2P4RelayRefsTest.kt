package com.lightningstudio.watchrss.phone.syncv2

import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SyncV2P4RelayRefsTest {
    private class Tables(val f:SyncV2P4Fixture,val store:SyncV2ContentStore,change:String="") {
        val data=SyncV2P4RelayJobData(f,store)
        val signed=linkedMapOf<String,JSONObject>()
        val original=linkedMapOf<String,ByteArray>()
        val request:JSONObject
        init {
            val state=SyncV2Envelope.seal(f.identity,f.key,1,"state",byteArrayOf(1))
            val segment=SyncV2Envelope.seal(f.identity,f.key,1,"state",byteArrayOf(2))
            val recipe=SyncV2Envelope.seal(f.identity,f.key,1,"recipe",byteArrayOf(3))
            val (blob,bytes)=f.blob(40)
            for((oid,raw)in listOf(state.oid to state.bytes,segment.oid to segment.bytes,recipe.oid to recipe.bytes,blob to bytes)) {
                store.putObject(oid,raw);data.raw[oid]=raw
            }
            for(type in listOf("state","recipe")) {
                val parent=if(type=="state")state.oid else recipe.oid
                // Recipe order and duplicate references must survive byte-for-byte forwarding.
                val children=if(type=="state")listOf(segment.oid)else listOf(blob,blob)
                val field=if(type=="state")"segmentOids" else "blobOids"
                val body=JSONObject().put("scope",f.identity.scope).put("${type}Oid",parent).put(field,JSONArray(children))
                if(change=="$type-scope")body.put("scope",JSONObject(f.identity.scopeJson).put("contentGeneration","999"))
                val signer=if(change=="$type-signer")f.signer else f.peerSigner
                val table=signer.signed("wrss.sync.v2/$type-refs-sign",body)
                val raw=SyncV2LocalProtocol.bytes(table);val oid="$type-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}"
                signed[oid]=table;original[oid]=raw;store.putObject(oid,raw);data.raw[oid]=raw
            }
            val permitted=data.raw.keys.filterNot{when(change){"state-parent"->it==state.oid;"state-child"->it==segment.oid;"recipe-parent"->it==recipe.oid;"recipe-child"->it==blob;else->false}}.sorted()
            val grant=f.peerSigner.signed("wrss.sync.v2/relay-grant-sign",JSONObject(data.request.getJSONObject("relayGrant").getJSONObject("body").toString()).put("objectOids",JSONArray(permitted)))
            request=JSONObject(data.request.toString()).put("relayGrant",grant)
        }
    }
    private class Cloud(val tables:Tables):AutoCloseable {
        val fixture=SyncV2P3Fixture();val client=fixture.client()
        val paths=mutableListOf<String>();val registered=linkedMapOf<String,String>();var failRecipe=false;var missing=false;var published:JSONObject?=null
        init {fixture.objects.putAll(tables.data.raw);fixture.route={r->
            assertEquals("Bearer synthetic",r.getHeader("Authorization"));assertEquals("synthetic-phone",r.getHeader("x-watchrss-cloud-device-id"))
            val path=r.requestUrl!!.encodedPath.substringAfter("/functions/v1/sync/v2/");paths.add(path)
            when {
                path=="capabilities"->fixture.response(fixture.dto().put("minVersion",2).put("maxVersion",2).put("maxPartBytes",65536).put("historyDays",7).put("features",JSONArray())
                    .put("receiptKeyId",fixture.bootstrap.get("receiptKeyId")).put("receiptSigningPublicKey",fixture.bootstrap.get("receiptSigningPublicKey"))
                    .put("flags",JSONObject().put("read",true).put("contentWrite",true).put("relay",true)))
                path.startsWith("requests/")->fixture.response(fixture.dto().put("status",if(published==null)"unknown" else "succeeded").put("response",published?:JSONObject.NULL))
                path=="objects/have-want"->{val body=SyncV2Wire.parse(r.body.readByteArray());val rows=JSONArray();val oids=body.getJSONArray("oids")
                    for(i in 0 until oids.length()){val oid=oids.getString(i);val size=tables.data.raw.getValue(oid).size;val offset=if(missing)fixture.prefix[oid]?.size?:0 else size
                        rows.put(JSONObject().put("oid",oid).put("status",if(offset==size)"present" else if(offset==0)"missing" else "partial").put("totalBytes",if(offset==0)JSONObject.NULL else size).put("durableOffset",offset).put("receipt",if(offset==0)JSONObject.NULL else fixture.receipt(oid,size,offset)))}
                    fixture.response(fixture.dto().put("windowId",body.get("windowId")).put("objects",rows))}
                path=="objects/reserve"->{val request=SyncV2Wire.parse(r.body.readByteArray());val rows=request.getJSONArray("objects")
                    for(i in 0 until rows.length())assertFalse(rows.getJSONObject(i).getString("oid") in tables.signed)
                    fixture.response(fixture.dto().put("reservationId","77".repeat(16)))}
                path in setOf("state-refs","recipe-refs")->{
                    if(missing)assertTrue(tables.data.raw.filterKeys{it !in tables.signed}.all{(oid,raw)->raw.contentEquals(fixture.prefix[oid])})
                    val body=SyncV2Wire.parse(r.body.readByteArray(),262144,true);SyncV2Codec.exact(body,setOf("body","signature","relayGrant"))
                    val signed=JSONObject().put("body",body.get("body")).put("signature",body.get("signature"))
                    val oid="$path:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(SyncV2LocalProtocol.bytes(signed)))}"
                    assertEquals(SyncV2Codec.canonical(tables.signed.getValue(oid)),SyncV2Codec.canonical(signed))
                    assertEquals(SyncV2Codec.canonical(tables.request.get("relayGrant")),SyncV2Codec.canonical(body.get("relayGrant")))
                    assertEquals(tables.f.b,body.getJSONObject("relayGrant").getJSONObject("body").getString("authorDeviceId"))
                    if(path=="recipe-refs" && failRecipe)fixture.response(JSONObject().put("error",JSONObject().put("code","relay_denied").put("retryable",false).put("retryAfterMillis",JSONObject.NULL).put("details",JSONObject()))).setResponseCode(403)
                    else {val value=SyncV2Codec.canonical(signed);registered[oid]?.let{assertEquals(it,value)};registered[oid]=value;fixture.response(fixture.dto().put("accepted",true))}
                }
                path=="account/publish"->{assertEquals(2,registered.size);assertEquals(SyncV2Codec.canonical(tables.request),r.body.readUtf8())
                    published=fixture.dto().put("account",JSONObject().put("commitOid",tables.data.oid).put("revision","1").put("rollbackGeneration","0"))
                        .put("device",JSONObject().put("ref","refs/devices/${tables.f.b}/index").put("commitOid",tables.data.oid).put("revision","1")).put("serverRevision","1")
                    fixture.response(published!!)}
                path=="refs/cas"->{assertEquals(2,registered.size);fixture.response(fixture.dto().put("accepted",true))}
                else->null
            }
        }}
        fun relay()=SyncV2Relay(tables.f.identity,tables.f.authors(),client,tables.store,true)
        override fun close()=fixture.close()
    }
    @Test fun c32QueuedPublicationRegistersOriginalAuthorTablesEvenWhenAllRawObjectsArePresent() {SyncV2P4Fixture().use{f->
        val store=f.store("c32");val tables=Tables(f,store);val (s,_)=f.pair()
        SyncV2RelayJobs(f.identity,f.authors(),store,f.a,true,{100000}).enqueue(s,tables.request);s.close()
        Cloud(tables).use{cloud->
            val queue=SyncV2RelayJobs(f.identity,f.authors(),f.store("c32"),f.a,true,{100000})
            assertEquals("cloud-published",queue.resumeNext(cloud.relay())!!.getString("kind"));assertEquals(0,queue.pending())
            assertEquals(1,cloud.paths.count{it=="state-refs"});assertEquals(1,cloud.paths.count{it=="recipe-refs"})
            assertTrue(cloud.paths.indexOf("account/publish")>maxOf(cloud.paths.indexOf("state-refs"),cloud.paths.indexOf("recipe-refs")))
            assertTrue(cloud.paths.none{it=="objects/reserve" || "/parts/" in it})
            for((oid,raw)in tables.original)assertArrayEquals(raw,store.objectBytes(oid))
        }
    }}
    @Test fun c32StageAlsoForwardsSameGrantAndImmutableSignaturesOnRetry() {SyncV2P4Fixture().use{f->
        val tables=Tables(f,f.store("c32-stage"));Cloud(tables).use{cloud->
            repeat(2){cloud.relay().forward(tables.data.commit,tables.request.getJSONObject("device"),tables.request.getJSONObject("relayGrant"),null,100000)}
            assertEquals(2,cloud.registered.size);assertEquals(2,cloud.paths.count{it=="refs/cas"})
            assertEquals(2,cloud.paths.count{it=="state-refs"});assertEquals(2,cloud.paths.count{it=="recipe-refs"});assertFalse(cloud.paths.contains("account/publish"))
        }
    }}
    @Test fun c32UploadsEveryParentAndChildBeforeRegisteringEitherTable() {SyncV2P4Fixture().use{f->
        val tables=Tables(f,f.store("c32-upload"));Cloud(tables).use{cloud->
            cloud.missing=true
            SyncV2Relay(f.identity,f.authors(),cloud.client,tables.store,true,signals={SyncV2TransportSignals(wifi=true,internet=true,cellular=false)}).forwardQueued(tables.request,100000)
            assertEquals(tables.data.raw.size-tables.signed.size,cloud.fixture.completed);assertEquals(2,cloud.registered.size)
            for((oid,raw)in tables.data.raw.filterKeys{it !in tables.signed})assertArrayEquals(raw,cloud.fixture.prefix[oid])
            assertTrue(cloud.fixture.putOids.none{it in tables.signed})
            val finalObject=cloud.paths.indexOfLast{it.endsWith("/complete")}
            assertTrue(cloud.paths.indexOf("state-refs")>finalObject);assertTrue(cloud.paths.indexOf("recipe-refs")>finalObject)
        }
    }}
    @Test fun c32WrongSignerScopeOrUncoveredParentAndChildCannotReachCloudOrWriteJob() {
        for(change in listOf("state-signer","recipe-signer","state-scope","recipe-scope","state-parent","state-child","recipe-parent","recipe-child"))SyncV2P4Fixture().use{f->
            val store=f.store("c32-reject");val tables=Tables(f,store,change);Cloud(tables).use{cloud->
                val writes=store.counts.writes
                val error=if(change.endsWith("signer"))"signature_invalid" else if(change.endsWith("scope"))"scope_mismatch" else "relay_denied"
                p4Error(error){cloud.relay().forwardQueued(tables.request,100000)}
                assertEquals(writes,store.counts.writes);assertTrue(cloud.paths.isEmpty());assertTrue(cloud.registered.isEmpty())
            }
        }
    }
    @Test fun c32RegistrationRefusalCannotPublishAndReopenedQueueRetriesOriginalTables() {SyncV2P4Fixture().use{f->
        val store=f.store("c32-retry");val tables=Tables(f,store);val (s,_)=f.pair()
        val queue=SyncV2RelayJobs(f.identity,f.authors(),store,f.a,true,{100000});queue.enqueue(s,tables.request)
        Cloud(tables).use{cloud->
            cloud.failRecipe=true
            try{queue.resumeNext(cloud.relay());fail("expected relay_denied")}catch(e:SyncV2CloudError){assertEquals("relay_denied",e.code)}
            assertEquals(1,queue.pending());assertFalse(cloud.paths.contains("account/publish"))
            cloud.failRecipe=false
            val restarted=SyncV2RelayJobs(f.identity,f.authors(),f.store("c32-retry"),f.a,true,{100000})
            restarted.resumeNext(cloud.relay());assertEquals(0,restarted.pending());assertEquals(2,cloud.registered.size)
            assertEquals(1,cloud.paths.count{it=="account/publish"})
        }
    }}
}
