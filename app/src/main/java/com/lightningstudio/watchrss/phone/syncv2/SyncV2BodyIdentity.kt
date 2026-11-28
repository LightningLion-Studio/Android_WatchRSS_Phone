package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject

/** Only immutable body identity; titles, MIME/display names and edit clocks are metadata. */
internal object SyncV2BodyIdentity {
    val originalKinds = setOf("article", "book", "asset", "note")
    val fields=listOf("bodyRevision","promiseId","recipeOid","opaqueContentId","contentBytes")
    fun witness(meta:JSONObject):String {
        val kind=meta.getString("kind");val payload=meta.getJSONObject("payload")
        return SyncV2Codec.hash(JSONObject().put("kind",kind).put("id",meta.getString("logicalId"))
            .put("body",when(kind){"article"->payload.opt("contentHash");"book"->JSONObject().put("fingerprint",payload.get("fingerprint")).put("chapterCount",payload.get("chapterCount")).put("instanceId",payload.opt("instanceId")?:JSONObject.NULL);"asset"->payload.get("assetId");else->JSONObject.NULL})
            )
    }
    fun same(meta:JSONObject,other:JSONObject?):Boolean=other!=null&&witness(meta)==witness(other.getJSONObject("meta"))
    /** Articles require an uploaded recipe. Independent bodies use the authenticated revision. */
    fun reusable(meta: JSONObject, prior: JSONObject?): Boolean {
        val row = prior?.optJSONObject("row") ?: return false
        if (row.getBoolean("deleted") || !same(meta, prior)) return false
        return when (meta.getString("kind")) {
            "article" -> !row.isNull("recipeOid") && meta.getJSONObject("payload").optString("contentHash").isNotBlank()
            "book", "asset" -> !row.isNull("bodyRevision")
            else -> false
        }
    }
    fun carry(row:JSONObject,source:JSONObject?){for(field in fields)row.put(field,source?.get(field)?:JSONObject.NULL)}
}
