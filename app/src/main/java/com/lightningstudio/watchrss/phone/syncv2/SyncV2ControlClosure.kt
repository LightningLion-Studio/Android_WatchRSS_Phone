package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** Authenticates every control object from the signed root before exposing a current snapshot. */
internal class SyncV2ControlClosure(private val identity: SyncV2Identity, private val authors: SyncV2Authors,
    private val contentKey: (Long)->ByteArray) {
    class Verified private constructor(val commitOid:String, internal val rows:List<JSONObject>, internal val objects:Map<String,ByteArray>) {
        companion object {
            internal fun create(oid:String,rows:List<JSONObject>,objects:Map<String,ByteArray>)=Verified(oid,rows,objects)
        }
    }
    fun verify(envelope:JSONObject,get:(String)->ByteArray,reference:(String)->JSONObject):Verified {
        val commit=authors.header(envelope);val objects=linkedMapOf<String,ByteArray>()
        fun fetch(oid:String):ByteArray = objects[oid] ?: get(oid).copyOf().also { objects[oid]=it }
        val rows=SyncV2Index.read(identity.scope,commit.getString("treeRoot"),::fetch)
        val decoded=mutableListOf<JSONObject>()
        for(row in rows) {
            val entity=row.getString("entityKey")
            if(SyncV2Wire.features(row.getJSONArray("requiredFeatures")).isNotEmpty())SyncV2Codec.fail("unsupported_feature")
            fun open(oid:String):JSONObject {
                val bytes=fetch(oid)
                if(bytes.size<9)SyncV2Codec.fail("invalid_encoding")
                val len=java.nio.ByteBuffer.wrap(bytes,4,4).int
                if(len !in 1..4096 || len+24>bytes.size)SyncV2Codec.fail("invalid_encoding")
                val h=SyncV2Wire.parse(bytes.copyOfRange(8,8+len),4096,true)
                val version=SyncV2Wire.uint(h,"keyVersion",4294967295)
                val value=SyncV2Wire.parse(SyncV2Envelope.open(identity,contentKey(version),oid,bytes,version),262144,true)
                if(value.get("version")!=2 || value.get("entityKey")!=entity)SyncV2Codec.fail("invalid_schema")
                return value
            }
            val meta=open(row.getString("metaOid"));val state=open(row.getString("stateOid"))
            SyncV2Codec.exact(meta,setOf("version","entityKey","kind","logicalId","addedAtSource","requiredFeatures","payload"))
            SyncV2Codec.exact(state,setOf("version","entityKey","requiredFeatures","payload","noteSegments"))
            val kind=SyncV2Wire.string(meta,"kind");val logicalId=SyncV2Wire.string(meta,"logicalId")
            if(identity.entity(kind,logicalId)!=entity || meta.get("addedAtSource") !in setOf("original","unknown"))SyncV2Codec.fail("invalid_schema")
            if(SyncV2Wire.features(meta.getJSONArray("requiredFeatures")).isNotEmpty())SyncV2Codec.fail("unsupported_feature")
            if(kind=="note")SyncV2NoteConflicts.read(state,logicalId,entity)
            else if(SyncV2Wire.features(state.getJSONArray("requiredFeatures")).isNotEmpty())SyncV2Codec.fail("unsupported_feature")
            if(state.getJSONArray("noteSegments").length()!=0)SyncV2Codec.fail("unsupported_feature")
            val mapping=reference(row.getString("stateOid"))
            SyncV2Codec.exact(mapping,setOf("scope","parentOid","refsOid","authorDeviceId","keyId","totalBytes"))
            if(SyncV2Codec.scope(mapping.getJSONObject("scope"))!=identity.scopeJson || mapping.get("parentOid")!=row.get("stateOid"))SyncV2Codec.fail("scope_mismatch")
            val refsOid=SyncV2Wire.oid(SyncV2Wire.string(mapping,"refsOid"),"state-refs")
            val raw=fetch(refsOid)
            if(raw.size.toLong()!=SyncV2Wire.uint(mapping,"totalBytes",262144) || refsOid!="state-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}")SyncV2Codec.fail("hash_mismatch")
            val signed=SyncV2Wire.parse(raw,262144,true);SyncV2Codec.exact(signed,setOf("body","signature"))
            val body=signed.getJSONObject("body");SyncV2Codec.exact(body,setOf("scope","stateOid","segmentOids"))
            if(SyncV2Codec.scope(body.getJSONObject("scope"))!=identity.scopeJson || body.get("stateOid")!=row.get("stateOid") || SyncV2Codec.canonical(body.getJSONArray("segmentOids"))!=SyncV2Codec.canonical(state.getJSONArray("noteSegments")))SyncV2Codec.fail("invalid_schema")
            val pub=authors.publicKey(SyncV2Wire.string(mapping,"authorDeviceId"),SyncV2Wire.string(mapping,"keyId"),true)
            if(!SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/state-refs-sign"),SyncV2Codec.utf8(SyncV2Codec.canonical(body))),SyncV2Wire.string(signed,"signature")))SyncV2Codec.fail("signature_invalid")
            val business=SyncV2Business.join(kind,meta,state)
            if(SyncV2Business.logicalId(kind,business)!=logicalId)SyncV2Codec.fail("invalid_schema")
            if(business.optBoolean("deleted",false)!=row.getBoolean("deleted"))SyncV2Codec.fail("invalid_schema")
            decoded.add(JSONObject().put("row",row).put("meta",meta).put("state",state))
        }
        return Verified.create(SyncV2Codec.oid("commit",commit),decoded,objects)
    }
    companion object {
        /** No worker is created here. A cloud receipt never implies local body availability. */
        fun placeholder(row:JSONObject):String = when {
            row.isNull("bodyRevision") -> "metadata-ready"
            !row.isNull("promiseId") -> "source-pending"
            else -> "download-pending"
        }
    }
}
