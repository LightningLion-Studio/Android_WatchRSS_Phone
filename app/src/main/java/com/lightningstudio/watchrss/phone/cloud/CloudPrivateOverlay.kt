package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONArray
import org.json.JSONObject

/** Authenticated private-body membership only. Reading/favorite clocks remain authoritative records. */
data class CloudPrivateOverlayEntry(val kind:String,val id:String,val objectName:String?,val changedAt:Long,val sourceDeviceId:String) {
    val key:Pair<String,String> get()=kind to id
    fun json():JSONObject=JSONObject().put("kind",kind).put("id",id).put("object",objectName ?: JSONObject.NULL).put("changedAt",changedAt).put("sourceDeviceId",sourceDeviceId)
}
object CloudPrivateOverlay {
    const val NAME="private/overlay-v1.json"
    const val FEATURE="r48.private-overlay.v1"
    fun articleName(id:String)="private/articles/"+CloudSnapshotCodec.sha256(id.toByteArray())+".wrss"
    fun encode(entries:List<CloudPrivateOverlayEntry>):ByteArray {
        if(entries.size>8192)throw CloudFormatUpgradeRequired("overlay_entry_limit")
        require(entries.map{it.key}.distinct().size==entries.size)
        val bytes=JSONObject().put("format","watchrss-private-overlay").put("version",1).put("entries",JSONArray(entries.sortedWith(compareBy({it.kind},{it.id})).map{it.json()})).toString().toByteArray()
        if(bytes.size>4_194_304)throw CloudFormatUpgradeRequired("overlay_byte_limit")
        decode(bytes);return bytes
    }
    fun decode(bytes:ByteArray):List<CloudPrivateOverlayEntry> {
        require(bytes.size<=4_194_304)
        val root=CloudRawDocument.parse(bytes).json();CloudMigrationCrypto.exact(root,"format","version","entries")
        require(root.get("format")=="watchrss-private-overlay" && CloudMigrationCrypto.integer(root,"version")==1L)
        val rows=root.getJSONArray("entries");require(rows.length()<=8192)
        val result=(0 until rows.length()).map { i ->
            val r=rows.getJSONObject(i);CloudMigrationCrypto.exact(r,"kind","id","object","changedAt","sourceDeviceId")
            require(listOf("kind","id","sourceDeviceId").all{r.get(it) is String})
            require(r.isNull("object") || r.get("object") is String)
            val kind=r.getString("kind");val id=r.getString("id");require(kind in setOf("article","book") && id.isNotBlank() && id.toByteArray().size<=1024)
            val source=r.getString("sourceDeviceId");require(source.isNotBlank() && source.toByteArray().size<=128 && source.none{it<' ' || it=='\u007f'})
            val name=if(r.isNull("object"))null else r.getString("object")
            require(name==null || name==if(kind=="article")articleName(id) else "books/$id.jsonl")
            if(kind=="book")CloudMigrationCrypto.hash(id)
            CloudPrivateOverlayEntry(kind,id,name,CloudMigrationCrypto.integer(r,"changedAt"),source)
        }
        require(result.map{it.key}.distinct().size==result.size && result==result.sortedWith(compareBy({it.kind},{it.id})))
        return result
    }
    fun merge(rows:List<CloudPrivateOverlayEntry>):List<CloudPrivateOverlayEntry> = rows.groupBy{it.key}.values.map { values ->
        val winner=values.maxWith(compareBy({it.changedAt},{it.sourceDeviceId}))
        val tied=values.filter{it.changedAt==winner.changedAt && it.sourceDeviceId==winner.sourceDeviceId}
        if(tied.any{it.objectName==null})winner.copy(objectName=null)
        else { require(tied.map{it.objectName}.distinct().size==1) { "overlay_body_conflict" };winner }
    }.sortedWith(compareBy({it.kind},{it.id}))
    fun mark(manifest:CloudSnapshotManifest):CloudSnapshotManifest {
        val raw=manifest.rawDocument ?: CloudRawDocument.parse(manifest.toRawString())
        val features=raw.json().optJSONObject("features") ?: JSONObject()
        val list=features.optJSONArray("incompat") ?: JSONArray()
        val values=(0 until list.length()).map{list.getString(it)}
        features.put("incompat",JSONArray((values+FEATURE).distinct())).put("roCompat",features.optJSONArray("roCompat") ?: JSONArray())
        return manifest.copy(rawDocument=raw.patch(listOf("features"),features.toString()))
    }
}
