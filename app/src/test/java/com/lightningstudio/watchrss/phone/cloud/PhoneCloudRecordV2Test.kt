package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.account.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneCloudRecordV2Test {
    private val session=PhoneAccountSession("owner","***","token","",Long.MAX_VALUE)
    private val k1=ByteArray(32){1};private val k2=ByteArray(32){2}
    private class Store: CloudRecordState {
        var cursor="0";val rows=linkedMapOf<String,Pair<String,String>>();val quarantine=linkedMapOf<String,QuarantinedCloudRecord>();val raw=linkedMapOf<String,String>()
        override fun cursor(owner:String)=cursor
        override fun record(owner:String,id:String)=rows[id]
        override fun save(owner:String,id:String,revision:String,hash:String){rows[id]=revision to hash}
        override fun advance(owner:String,cursor:String){this.cursor=cursor}
        override fun clear(owner:String){cursor="0";rows.clear();quarantine.clear();raw.clear()}
        override fun quarantine(owner:String,record:QuarantinedCloudRecord){quarantine[record.id]=record}
        override fun quarantined(owner:String)=quarantine.values.toList()
        override fun release(owner:String,id:String){quarantine.remove(id)}
        override fun rawEnvelope(owner:String,id:String)=raw[id]
        override fun saveRawEnvelope(owner:String,id:String,envelope:String){raw[id]=envelope}
    }
    private fun value(id:String)=JSONObject().put("articleId",id).put("url","https://example.com/$id")
    private fun row(v:JSONObject,rev:Int,version:Int=1,stable:Boolean=true):JSONObject {
        val content=if(version==1)k1 else k2;val identity=if(stable)k1 else content
        val id=PhoneCloudRecordCodec.id(identity,session.userId,"article",v)
        return JSONObject().put("recordId",id).put("revision","$rev").put("tombstone",v.optBoolean("deleted"))
            .put("envelope",PhoneCloudRecordCodec.encrypt(content,session.userId,id,version,"article",v,idKey=identity))
    }
    private fun page(rows:JSONArray,cursor:String)=JSONObject().put("formatVersion",1).put("items",rows).put("cursor",cursor).put("headRevision",cursor).put("hasMore",false)
    private fun client(handle:(Request,JSONObject)->Pair<Int,JSONObject>)=PhoneCloudClient(AccountEnvironment(backendBaseUrl="https://test.invalid"),http=OkHttpClient.Builder().addInterceptor { chain ->
        val buffer=okio.Buffer();chain.request().body?.writeTo(buffer)
        val (status,body)=handle(chain.request(),if(buffer.size==0L)JSONObject() else JSONObject(buffer.readUtf8()))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toString().toResponseBody("application/json".toMediaType())).build()
    }.build()).also { it.useEpoch(session, "epoch") }
    private fun customRecord(kind: String, value: JSONObject, rev: Int): JSONObject {
        val id = PhoneCloudRecordCodec.id(k1,session.userId,"article",value)
        val derived = CloudSnapshotCodec.hkdfSha256(k1,session.userId.toByteArray(),"watchrss/cloud/record/v1".toByteArray(),32)
        val n = ByteArray(12) { rev.toByte() }
        val plain = JSONObject().put("format","watchrss-rss-record").put("version",1).put("kind",kind).put("value",value).toString().toByteArray()
        val encrypted = CloudSnapshotCodec.encryptAesGcm(derived,n,plain,"watchrss-cloud-record-v1:${session.userId}:$id:1".toByteArray())
        return JSONObject().put("recordId",id).put("revision","$rev").put("envelope",JSONObject().put("algorithm","A256GCM").put("keyVersion",1)
            .put("nonceBase64",n.base64()).put("ciphertextBase64",encrypted.base64()))
    }
    @Test fun r3AliasRetirementNeverDeletesOrResurrectsAndEntityTombstoneStillApplies() = runBlocking {
        val retired = row(value("retired"),1).put("tombstone",true)
        val deleted = row(value("deleted").put("deleted",true),2)
        val store = Store();val applied = mutableListOf<String>()
        val c=client { _,_->200 to page(JSONArray().put(retired).put(deleted),"2") }
        PhoneCloudRecordSync(c,store,{},{_,_,v->applied += v.getString("articleId")},{1},{_,_->k1.copyOf()},{}).sync(session,false)
        assertEquals(listOf("deleted"),applied);assertEquals("retired",store.rows.getValue(retired.getString("recordId")).second)
    }
    @Test fun quarantinedAliasRetirementKeepsItsOuterFlagWhenMissingKeyIsRecovered() = runBlocking {
        val item = row(value("retired"),1,2).put("tombstone",true);val store=Store();var unlocked=false;var applied=0
        val c=client { req,_->200 to if(req.url.queryParameter("after")=="0")page(JSONArray().put(item),"1") else page(JSONArray(),"1") }
        val sync=PhoneCloudRecordSync(c,store,{},{_,_,_->applied++},{if(unlocked)2 else 1},
            {_,v->if(v==1)k1.copyOf() else if(unlocked)k2.copyOf() else null},{},anchorVersion={1})
        sync.sync(session,false);assertTrue(store.quarantine.values.single().tombstone)
        unlocked=true;sync.sync(session,false);assertEquals(0,applied);assertTrue(store.quarantine.isEmpty())
        assertEquals("retired",store.rows.getValue(item.getString("recordId")).second)
    }
    @Test fun unknownKindsAndInvalidFieldsArePreservedAsQuarantineAndDoNotBlockFollowingRows() = runBlocking {
        val future = customRecord("future-kind",value("future").put("extension","preserved"),1)
        val invalid = customRecord("article",value("invalid").put("updatedAt","bad clock"),2)
        val store = Store();var applied=0
        val c=client { _,_->200 to page(JSONArray().put(future).put(invalid).put(row(value("good"),3)),"3") }
        PhoneCloudRecordSync(c,store,{},{_,_,_->applied++},{1},{_,_->k1.copyOf()},{}).sync(session,false)
        assertEquals("3",store.cursor);assertEquals(1,applied);assertEquals(2,store.quarantine.size)
        assertEquals("unknown_kind",store.quarantine.getValue(future.getString("recordId")).reason)
        assertEquals(future.getJSONObject("envelope").toString(),store.quarantine.getValue(future.getString("recordId")).envelope)
    }
    @Test fun poisonAndUnknownVersionAreQuarantinedWhileGoodRowsAdvance()=runBlocking {
        val store=Store();val bad=row(value("bad"),1);bad.getJSONObject("envelope").put("ciphertextBase64",ByteArray(32).base64())
        val missing=row(value("missing"),2,2);val good=row(value("good"),3);var applied=0;var refreshed=0
        val c=client { _,_->200 to page(JSONArray().put(bad).put(missing).put(good),"3") }
        val sync=PhoneCloudRecordSync(c,store,{},{_,_,_->applied++},{1},{_,v->if(v==1)k1.copyOf() else null},{},anchorVersion={1},refreshKeys={refreshed++})
        sync.sync(session,false)
        assertEquals("3",store.cursor);assertEquals(1,applied);assertEquals(2,store.quarantine.size);assertEquals(1,refreshed)
        assertEquals(setOf("missing_key","invalid_record"),store.quarantine.values.map{it.reason}.toSet())
    }
    @Test fun missingKeyRefreshReplaysQuarantineWithoutRewindingCursor()=runBlocking {
        val store=Store();val item=row(value("new"),1,2);var hasKey=false;var applied=0
        val c=client { req,_->200 to if(req.url.queryParameter("after")=="0")page(JSONArray().put(item),"1") else page(JSONArray(),"1") }
        val sync=PhoneCloudRecordSync(c,store,{},{_,_,_->applied++},{if(hasKey)2 else 1},{_,v->when(v){1->k1.copyOf();2->if(hasKey)k2.copyOf() else null;else->null}},{},anchorVersion={1})
        sync.sync(session,false);assertEquals("1",store.cursor);assertEquals(1,store.quarantine.size);assertEquals(0,applied)
        hasKey=true;sync.sync(session,false);assertEquals("1",store.cursor);assertTrue(store.quarantine.isEmpty());assertEquals(1,applied)
    }
    @Test fun rotationUsesAnchorIdAndRetiresOldAliasInSameCasBatchWithoutDeletingEntity()=runBlocking {
        val store=Store();val v=value("article");val old=row(v,7,2,stable=false);val oldId=old.getString("recordId")
        var posts=0;var applied=0;var nextRev=8
        val c=client { req,body ->
            if(req.method=="GET")200 to if(store.cursor=="0")page(JSONArray().put(old),"7") else page(JSONArray(),store.cursor)
            else {
                posts++;val rows=body.getJSONArray("items");assertEquals(2,rows.length())
                val stable=rows.getJSONObject(0);val retired=rows.getJSONObject(1)
                assertEquals(PhoneCloudRecordCodec.id(k1,session.userId,"article",v),stable.getString("recordId"));assertFalse(stable.getBoolean("tombstone"))
                assertEquals(2,stable.getJSONObject("envelope").getInt("keyVersion"))
                assertEquals(oldId,retired.getString("recordId"));assertTrue(retired.getBoolean("tombstone"));assertEquals("7",retired.getString("expectedRevision"))
                val decoded=PhoneCloudRecordCodec.decrypt(k2,session.userId,oldId,retired.getJSONObject("envelope"))
                assertFalse(decoded.second.optBoolean("deleted"))
                200 to JSONObject().put("items",JSONArray().apply { for(i in 0 until rows.length())put(JSONObject().put("recordId",rows.getJSONObject(i).getString("recordId")).put("revision","${nextRev++}")) })
            }
        }
        val sync=PhoneCloudRecordSync(c,store,{consume->consume("article",v)},{_,_,_->applied++},{2},{_,ver->(if(ver==1)k1 else k2).copyOf()},{},anchorVersion={1})
        sync.sync(session,true);assertEquals(1,posts);assertEquals("retired",store.rows[oldId]!!.second)
        sync.sync(session,true);assertEquals(1,posts);assertEquals(1,applied)
    }
    @Test fun tombstonesAreExplicitAndQuotaErrorsRemainTyped()=runBlocking {
        val store=Store();var sent=false
        val c=client { req,body -> if(req.method=="GET")200 to page(JSONArray(),"0") else {
            assertTrue(body.getJSONArray("items").getJSONObject(0).getBoolean("tombstone"));sent=true
            413 to JSONObject().put("error","cloud_quota_exceeded")
        } }
        val sync=PhoneCloudRecordSync(c,store,{consume->consume("article",value("gone").put("deleted",true))},{_,_,_->},{1},{_,_->k1.copyOf()},{})
        try{sync.sync(session,true);fail("quota failure swallowed")}catch(e:CloudHttpException){assertEquals(413,e.status)}
        assertTrue(sent);assertTrue(store.rows.isEmpty())
    }
}
