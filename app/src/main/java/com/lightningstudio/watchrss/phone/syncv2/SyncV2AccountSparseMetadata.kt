package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject

/** Current-cache edits under an authenticated root. Untouched control bytes are
 * neither read nor copied. This is not a second business or account journal. */
internal class SyncV2AccountSparseMetadata(private val projection:SyncV2Projection) {
    private val db get()=projection.accountDatabase
    private val scope get()=projection.scope
    fun objects(rows:List<JSONObject>,supplied:Map<String,ByteArray>):Map<String,ByteArray> {
        val out=supplied.toMutableMap()
        for(record in rows) {
            val row=record.getJSONObject("row")
            for(field in listOf("metaOid","stateOid")){val oid=row.getString(field);if(oid !in out)out[oid]=projection.accountObject(oid)}
            val refs=projection.accountCatalog.refs(row.getString("stateOid"))?:SyncV2Codec.fail("closure_incomplete")
            if(refs !in out)out[refs]=projection.accountObject(refs)
        };return out
    }
    fun replace(st:SyncV2Projection.State,account:JSONObject,root:String,ids:Set<String>,rows:List<JSONObject>,
        objects:Map<String,ByteArray>,retiredTrees:Set<String>,serverRevision:String) {
        check(db.inTransaction());SyncV2Codec.d64(serverRevision)
        val catalog=projection.accountCatalog;val byId=rows.associateBy{it.getJSONObject("meta").getString("entityKey")}
        val cache=mutableMapOf<String,ByteArray>();fun read(oid:String)=objects[oid]?:cache.getOrPut(oid){projection.accountObject(oid)}
        for((oid,bytes)in objects)if(oid.startsWith("state-refs:")) {
            val refs=SyncV2Wire.parse(bytes,262144,true);if(SyncV2Codec.oid("state-refs",refs)!=oid)SyncV2Codec.fail("hash_mismatch")
            catalog.putRefs(refs.getJSONObject("body").getString("stateOid"),oid)
        }
        val obsolete=retiredTrees.toMutableSet();var rowDelta=0
        fun retireState(oid:String) {
            val refsOid=catalog.refs(oid)
            if(refsOid!=null) {
                val refs=SyncV2Wire.parse(read(refsOid),262144,true).getJSONObject("body")
                if(refs.getString("stateOid")!=oid)SyncV2Codec.fail("identity_conflict")
                val segments=refs.getJSONArray("segmentOids")
                for(i in 0 until segments.length()){val child=segments.getString(i);obsolete.add(child);catalog.refs(child)?.let{obsolete.add(it)}}
                obsolete.add(refsOid)
            };obsolete.add(oid)
        }
        for(id in ids) {
            val old=projection.cloudRows(setOf(id)).singleOrNull();val next=byId[id]
            if(old==null && next!=null)rowDelta++
            if(old!=null && next==null)rowDelta--
            val row=SyncV2Index.find(JSONObject(scope),root,id,::read)
            if(SyncV2Codec.canonical(row)!=SyncV2Codec.canonical(next?.getJSONObject("row")))SyncV2Codec.fail("hash_mismatch")
            if(old!=null)for(field in listOf("metaOid","stateOid")) {
                val oid=old.getJSONObject("row").getString(field)
                if(next?.getJSONObject("row")?.optString(field)!=oid)if(field=="stateOid")retireState(oid)else obsolete.add(oid)
            }
            if(next==null) {
                db.execSQL("DELETE FROM syncv2_cloud_rows WHERE scope=? AND entity_key=?",arrayOf(scope,id));catalog.remove("cloud",id)
            }else {
                val normalized=JSONObject().put("meta",next.getJSONObject("meta")).put("state",next.getJSONObject("state")).put("row",next.getJSONObject("row"))
                if(catalog.cloudRowHash(id)!=SyncV2Codec.hash(normalized))db.execSQL("INSERT OR REPLACE INTO syncv2_cloud_rows VALUES(?,?,?)",arrayOf(scope,id,projection.accountCacheRow(normalized)))
                catalog.setCloudRow(normalized,catalog.refs(next.getJSONObject("row").getString("stateOid")))
            }
        }
        // The legacy cloud-row envelope purpose stays the same; projection owns it.
        for((oid,bytes)in objects)if(oid.substringBefore(':') in setOf("meta","state","tree","state-refs"))
            db.execSQL("INSERT OR IGNORE INTO syncv2_cloud_objects VALUES(?,?,?)",arrayOf(scope,oid,bytes))
        for(oid in obsolete-objects.keys) {
            db.execSQL("DELETE FROM syncv2_cloud_objects WHERE scope=? AND oid=?",arrayOf(scope,oid))
            db.execSQL("DELETE FROM syncv2_p5_ref_index WHERE scope=? AND (parent_oid=? OR refs_oid=?)",arrayOf(scope,oid,oid))
        }
        st.body.put("metadataTreeRoot",root).put("metadataAccount",account).put("metadataStatus","metadata-ready")
            .put("metadataSchema",SyncV2HeadPins.schema).put("metadataRows",st.body.getInt("metadataRows")+rowDelta).put("metadataServerRevision",serverRevision)
    }
}
