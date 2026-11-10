package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/**
 * Authenticates every control object from the signed root before exposing a current snapshot.
 *
 * F2/R4: with a pull-side stager every segmented entity is processed on its own. Its segment
 * ciphertext and its reassembled body leave [verify] immediately through [StagedRow], so one
 * round only ever retains OIDs, hashes and binding metadata across entities. Without a stager
 * (rollback, vectors) the round keeps the pre-C7 whole-snapshot behaviour.
 */
internal class SyncV2ControlClosure(private val identity: SyncV2Identity, private val authors: SyncV2Authors,
    private val contentKey: (Long)->ByteArray, private val noteSegmentsRead: Boolean = false) {
    class Verified private constructor(val commitOid:String, internal val rows:List<JSONObject>, internal val objects:Map<String,ByteArray>) {
        companion object {
            internal fun create(oid:String,rows:List<JSONObject>,objects:Map<String,ByteArray>)=Verified(oid,rows,objects)
        }
    }
    /** One authenticated entity handed to the pull-side stager: sealed cloud-row body plus its
     * hash-verified raw objects. Cross-entity state never keeps these bytes (F2/R4). */
    class StagedRow internal constructor(internal val entityKey:String, internal val body:JSONObject, internal val objects:Map<String,ByteArray>)
    private fun emptyState(entity:String)=JSONObject().put("version",2).put("entityKey",entity)
        .put("requiredFeatures",JSONArray()).put("payload",JSONObject()).put("noteSegments",JSONArray())
    fun verify(envelope:JSONObject,get:(String)->ByteArray,stage:((StagedRow)->Unit)?=null,reference:(String)->JSONObject):Verified {
        val commit=authors.header(envelope);val objects=linkedMapOf<String,ByteArray>()
        fun fetch(oid:String):ByteArray = objects[oid] ?: get(oid).copyOf().also { objects[oid]=it }
        val rows=SyncV2Index.read(identity.scope,commit.getString("treeRoot"),::fetch)
        val decoded=mutableListOf<JSONObject>()
        for(row in rows) {
            val entity=row.getString("entityKey")
            // F2/R4: segmented bytes live only inside this entity's iteration.
            val rowBytes=linkedMapOf<String,ByteArray>()
            fun fetchRow(oid:String):ByteArray = rowBytes[oid] ?: get(oid).copyOf().also{rowBytes[oid]=it}
            fun openWith(read:(String)->ByteArray,oid:String):JSONObject {
                val bytes=read(oid)
                if(bytes.size<9)SyncV2Codec.fail("invalid_encoding")
                val len=java.nio.ByteBuffer.wrap(bytes,4,4).int
                if(len !in 1..4096 || len+24>bytes.size)SyncV2Codec.fail("invalid_encoding")
                val h=SyncV2Wire.parse(bytes.copyOfRange(8,8+len),4096,true)
                val version=SyncV2Wire.uint(h,"keyVersion",4294967295)
                val value=SyncV2Wire.parse(SyncV2Envelope.open(identity,contentKey(version),oid,bytes,version),262144,true)
                if(value.get("version")!=2 || value.get("entityKey")!=entity)SyncV2Codec.fail("invalid_schema")
                return value
            }
            fun open(oid:String)=openWith(::fetch,oid)
            // Shared step: the signed state-refs mapping and the business identity of one row.
            fun businessRow(wrapper:JSONObject,state:JSONObject,wire:JSONObject,kind:String,logicalId:String,row:JSONObject,read:(String)->ByteArray) {
                wrapper.put("state",state)
                val mapping=reference(row.getString("stateOid"))
                SyncV2Codec.exact(mapping,setOf("scope","parentOid","refsOid","authorDeviceId","keyId","totalBytes"))
                if(SyncV2Codec.scope(mapping.getJSONObject("scope"))!=identity.scopeJson || mapping.get("parentOid")!=row.get("stateOid"))SyncV2Codec.fail("scope_mismatch")
                val refsOid=SyncV2Wire.oid(SyncV2Wire.string(mapping,"refsOid"),"state-refs")
                val raw=read(refsOid)
                if(raw.size.toLong()!=SyncV2Wire.uint(mapping,"totalBytes",262144) || refsOid!="state-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}")SyncV2Codec.fail("hash_mismatch")
                val signed=SyncV2Wire.parse(raw,262144,true);SyncV2Codec.exact(signed,setOf("body","signature"))
                val body=signed.getJSONObject("body");SyncV2Codec.exact(body,setOf("scope","stateOid","segmentOids"))
                if(SyncV2Codec.scope(body.getJSONObject("scope"))!=identity.scopeJson || body.get("stateOid")!=row.get("stateOid") || SyncV2Codec.canonical(body.getJSONArray("segmentOids"))!=SyncV2Codec.canonical(wire.getJSONArray("noteSegments")))SyncV2Codec.fail("invalid_schema")
                val pub=authors.publicKey(SyncV2Wire.string(mapping,"authorDeviceId"),SyncV2Wire.string(mapping,"keyId"),true)
                if(!SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/state-refs-sign"),SyncV2Codec.utf8(SyncV2Codec.canonical(body))),SyncV2Wire.string(signed,"signature")))SyncV2Codec.fail("signature_invalid")
                val business=SyncV2Business.join(kind,wrapper.getJSONObject("meta"),state)
                if(SyncV2Business.logicalId(kind,business)!=logicalId)SyncV2Codec.fail("invalid_schema")
                if(business.optBoolean("deleted",false)!=row.getBoolean("deleted"))SyncV2Codec.fail("invalid_schema")
            }
            val meta=open(row.getString("metaOid"))
            SyncV2Codec.exact(meta,setOf("version","entityKey","kind","logicalId","addedAtSource","requiredFeatures","payload"))
            val rowKind=SyncV2Wire.string(meta,"kind")
            // 25.8: row and commit may declare note-segments.v1. A row-level feature set equal to
            // {note-segments.v1} on a note row reads normally (25.8 row-level pass).
            val rowFeatures=SyncV2Wire.features(row.getJSONArray("requiredFeatures"))
            if(rowFeatures.isNotEmpty()&&!(rowKind=="note"&&rowFeatures==listOf(SyncV2NoteSegments.feature)))SyncV2Codec.fail("unsupported_feature")
            val kind=SyncV2Wire.string(meta,"kind");val logicalId=SyncV2Wire.string(meta,"logicalId")
            if(identity.entity(kind,logicalId)!=entity || meta.get("addedAtSource") !in setOf("original","unknown"))SyncV2Codec.fail("invalid_schema")
            if(SyncV2Wire.features(meta.getJSONArray("requiredFeatures")).isNotEmpty())SyncV2Codec.fail("unsupported_feature")
            val wrapper=JSONObject().put("row",row).put("meta",meta)
            if(kind!="note") {
                // Baseline frozen transport guard, restored (review issue 9): a non-note entity may
                // never transport noteSegments nor declare any feature. Any such wire fails the
                // whole round with unsupported_feature; it is never per-entity isolation material.
                val wire=open(row.getString("stateOid"))
                SyncV2Codec.exact(wire,setOf("version","entityKey","requiredFeatures","payload","noteSegments"))
                if(wire.getJSONArray("noteSegments").length()!=0 || SyncV2Wire.features(wire.getJSONArray("requiredFeatures")).isNotEmpty())SyncV2Codec.fail("unsupported_feature")
                businessRow(wrapper,wire,wire,kind,logicalId,row,::fetch)
                decoded.add(wrapper);continue
            }
            var wire:JSONObject?=null
            var ordinary:JSONObject?=null
            var segmented=false
            val isolated=runCatching{
                if(rowFeatures.isNotEmpty()&&!noteSegmentsRead)throw IllegalStateException("unsupported_feature")
                // The parent wire is opened from bytes that stay inside this iteration only.
                val read=openWith(if(stage!=null)::fetchRow else ::fetch,row.getString("stateOid"))
                wire=read
                SyncV2Codec.exact(read,setOf("version","entityKey","requiredFeatures","payload","noteSegments"))
                if(SyncV2Wire.features(read.getJSONArray("requiredFeatures")).isNotEmpty()&&!noteSegmentsRead)throw IllegalStateException("unsupported_feature")
                segmented = read.optJSONObject("payload")?.has("segmentedNote")==true ||
                    SyncV2NoteSegments.feature in SyncV2Wire.features(read.getJSONArray("requiredFeatures")) ||
                    read.getJSONArray("noteSegments").length()!=0
                if(segmented) {
                    // F1/W3: an unsupported or over-budget remote note is isolated per entity. The
                    // authenticated raw bytes and the local business body both survive; other
                    // entities continue to apply and the cursor advances (25.8, ruling F1).
                    if(!noteSegmentsRead) throw IllegalStateException("unsupported_feature")
                    val ctx=SyncV2NoteWiring.context(identity,authors,contentKey)
                    val stateOid=row.getString("stateOid")
                    val rowRead=if(stage!=null)::fetchRow else ::fetch
                    val closed=SyncV2NoteClosure.authenticate(ctx,read,stateOid,rowRead(stateOid),
                        {id->runCatching{rowRead(id)}.getOrNull()},
                        {parent->
                            val info=runCatching{reference(parent)}.getOrNull()?:return@authenticate null
                            val refsOid=info.optString("refsOid")
                            if(!Regex("state-refs:sha256:[0-9a-f]{64}").matches(refsOid))return@authenticate null
                            val raw=runCatching{rowRead(refsOid)}.getOrNull()?:return@authenticate null
                            SyncV2NoteClosure.Mapping(info,raw)
                        },setOf(SyncV2NoteConflicts.feature,SyncV2NoteSegments.feature))
                    ordinary=closed.ordinary
                    wrapper.put(SyncV2NoteWiring.SEGMENTS_KEY,JSONArray(closed.segments.map{it.oid}))
                        .put(SyncV2NoteWiring.REFS_MAPS_KEY,JSONObject().also{maps->for(seg in closed.segments)maps.put(seg.oid,seg.mapping.info)})
                        .put(SyncV2NoteWiring.REVISION_KEY,read.getJSONObject("payload").get("noteRevision"))
                    SyncV2NoteConflicts.read(closed.ordinary,logicalId,entity,setOf(SyncV2NoteConflicts.feature))
                } else {
                    SyncV2NoteConflicts.read(read,logicalId,entity)
                    if(read.getJSONArray("noteSegments").length()!=0)SyncV2Codec.fail("invalid_schema")
                }
                null
            }.getOrElse{e->
                // Issue 7: a note entity's read-side object_too_large isolates that entity like
                // storage_pressure does; a non-whitelisted failure still ends the whole round.
                if(e !is IllegalStateException||e.message !in setOf("unsupported_feature","storage_pressure","object_too_large"))throw e
                val failed=wire
                // Issue 8: best-effort preservation of everything already authenticated this round.
                // A failure here must never turn into an install-time hard prerequisite.
                runCatching{val bytes=if(stage!=null)fetchRow(row.getString("stateOid")) else fetch(row.getString("stateOid"))
                    if(stage!=null&&row.getString("stateOid").substringAfter(":sha256:")==SyncV2Codec.hex(SyncV2Codec.sha(bytes)))rowBytes[row.getString("stateOid")]=bytes}
                runCatching{val info=reference(row.getString("stateOid"));val refsOid=SyncV2Wire.oid(info.getString("refsOid"),"state-refs")
                    val bytes=if(stage!=null)fetchRow(refsOid) else fetch(refsOid)
                    if(stage!=null&&refsOid.substringAfter(":sha256:")==SyncV2Codec.hex(SyncV2Codec.sha(bytes)))rowBytes[refsOid]=bytes}
                wrapper.put("state",failed?:emptyState(entity)).put(SyncV2NoteWiring.ISOLATED_KEY,e.message?:"storage_pressure")
                    .put(SyncV2NoteWiring.SEGMENTS_KEY,JSONArray(failed?.optJSONArray("noteSegments")?.let{SyncV2Wire.strings(it,SyncV2NoteSegments.MAX,false)}?:emptyList<String>()))
                    .put(SyncV2NoteWiring.REFS_MAPS_KEY,JSONObject())
                    .put(SyncV2NoteWiring.REVISION_KEY,failed?.optJSONObject("payload")?.opt("noteRevision")?:JSONObject.NULL)
                if(stage!=null) {
                    wrapper.put(SyncV2NoteWiring.PULL_STAGED_KEY,true)
                    stage(StagedRow(entity,JSONObject(wrapper.toString()).put("state",failed?:emptyState(entity)),rowBytes.toMap()))
                    rowBytes.clear()
                }
                decoded.add(wrapper);continue
            }
            if(isolated!=null)SyncV2Codec.fail("invalid_schema")
            if(stage!=null&&!segmented)for((oid,bytes)in rowBytes)objects[oid]=bytes // ordinary notes keep the round map
            businessRow(wrapper,ordinary?:wire!!,wire!!,kind,logicalId,row,if(stage!=null&&segmented)::fetchRow else ::fetch)
            if(stage!=null&&segmented) {
                // F2/R4: the reassembled body and the segment ciphertexts are handed off per
                // entity; the round keeps only the verbatim parent wire plus metadata.
                val body=JSONObject(wrapper.toString()).put("state",ordinary!!)
                wrapper.put(SyncV2NoteWiring.PULL_STAGED_KEY,true)
                stage(StagedRow(entity,body,rowBytes.toMap()));rowBytes.clear()
            }
            wrapper.put("state",ordinary?:wire!!)
            if(stage!=null&&segmented)wrapper.put("state",wire) // the round carries the parent wire verbatim only
            decoded.add(wrapper)
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
