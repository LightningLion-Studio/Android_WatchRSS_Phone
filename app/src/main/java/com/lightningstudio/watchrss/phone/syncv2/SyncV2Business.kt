package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.data.note.MarkdownMergeResult
import com.lightningstudio.watchrss.phone.data.note.MarkdownThreeWayMerge
import org.json.JSONArray
import org.json.JSONObject

/** Frozen wire field sets, independent of Room/legacy codec default filling (spec 14). */
internal object SyncV2Business {
    private fun fields(s: String) = s.split(' ').toSet()
    val metaFields = fields("articleId url title siteName excerpt imageUrl rssSourceUrl rssSourceTitle contentHash importedAt guid sourceUrl sourceId description siteUrl createdAt fingerprint chapterCount kind instanceId bookId articleIdHash chapterIndex noteId folderId name assetId mime originalFilename")
    val articleFields = fields("articleId sourceDeviceId url title siteName excerpt imageUrl contentHash importedAt guid updatedAt independentSaved independentChangedAt independentSortOrder rssSourceUrl rssSourceTitle favoriteSaved favoriteChangedAt favoriteSortOrder watchLaterSaved watchLaterChangedAt watchLaterSortOrder deleted deletedAt readingProgress isRead readingPositionBytes readingPositionContentHash readingPositionChangedAt rssIndexRetired bookIndexRetired registerSources")
    val sourceFields = fields("url sourceId title description siteUrl imageUrl createdAt updatedAt sortOrder isPinned deleted deletedAt sourceDeviceId useOriginalContent continuePlaybackInBackground")
    val bookFields = fields("bookId fingerprint title chapterCount deletedChapters deletionClock deleted deletedAt updatedAt sourceDeviceId sourceUrl kind lifecycle instanceId")
    val readingFields = fields("bookId chapterIndex isRead readingProgress readingPositionBytes readingPositionContentHash readingPositionChangedAt sourceDeviceId favoriteSaved favoriteChangedAt favoriteSortOrder favoriteSourceDeviceId watchLaterSaved watchLaterChangedAt watchLaterSortOrder watchLaterSourceDeviceId")
    // RSS tombstones are part of its frozen schema; book save provenance is not.
    val rssStateFields = fields("sourceId articleIdHash sourceDeviceId isRead readingProgress readingPositionBytes readingPositionContentHash readingPositionChangedAt favoriteSaved favoriteChangedAt favoriteSortOrder watchLaterSaved watchLaterChangedAt watchLaterSortOrder deleted deletedAt")
    val noteFields = fields("noteId folderId title markdown plainText contentHash baseContentHash baseMarkdown pinned createdAt updatedAt modifiedBy deleted deletedAt")
    val folderFields = fields("folderId name sortOrder updatedAt modifiedBy deleted deletedAt")
    val assetFields = fields("assetId mime originalFilename updatedAt sourceDeviceId deleted deletedAt")
    fun allowed(kind: String) = when(kind) {
        "article" -> articleFields; "source" -> sourceFields; "book" -> bookFields
        "book-reading" -> readingFields; "note" -> noteFields + "noteConflicts"; "folder" -> folderFields
        "asset" -> assetFields; "rss-state" -> rssStateFields
        else -> SyncV2Codec.fail("unsupported_feature")
    }
    private val booleans = fields("independentSaved favoriteSaved watchLaterSaved deleted isRead rssIndexRetired bookIndexRetired isPinned useOriginalContent continuePlaybackInBackground pinned")
    private val numbers = fields("importedAt updatedAt independentChangedAt independentSortOrder favoriteChangedAt favoriteSortOrder watchLaterChangedAt watchLaterSortOrder deletedAt readingPositionBytes readingPositionChangedAt createdAt sortOrder chapterCount chapterIndex deletionClock")
    private val nullable = fields("folderId imageUrl rssSourceUrl rssSourceTitle siteUrl")
    fun validate(kind: String, input: JSONObject) {
        if ((input.keys().asSequence().toSet() - allowed(kind)).isNotEmpty()) SyncV2Codec.fail("unsupported_feature")
        for (key in input.keys()) {
            val value = input.get(key)
            when {
                key == "noteConflicts" -> SyncV2NoteConflicts.validate(input.getJSONObject(key),input.getString("noteId"))
                key in booleans -> if(value !is Boolean) SyncV2Codec.fail("invalid_schema")
                key in numbers -> {
                    if(value !is Int && value !is Long) SyncV2Codec.fail("invalid_schema")
                    val n = (value as Number).toLong()
                    if(n !in (if(key.endsWith("SortOrder") || key == "sortOrder") -9_007_199_254_740_991L else 0L)..9_007_199_254_740_991L) SyncV2Codec.fail("invalid_schema")
                    if(key in setOf("chapterCount", "chapterIndex") && n !in 1..100000) SyncV2Codec.fail("invalid_schema")
                }
                key == "readingProgress" -> if(value !is Number || !value.toDouble().isFinite() || value.toDouble() !in 0.0..1.0) SyncV2Codec.fail("invalid_schema")
                key == "lifecycle" -> {
                    val token = value as? JSONObject ?: SyncV2Codec.fail("invalid_schema")
                    SyncV2Codec.exact(token, fields("generation operationId startedAt"))
                    for(f in listOf("generation", "startedAt")) {
                        val n=token.get(f); if(n !is Int && n !is Long) SyncV2Codec.fail("invalid_schema")
                        if((n as Number).toLong() !in (if(f=="generation")1L else 0L)..9_007_199_254_740_991L) SyncV2Codec.fail("invalid_schema")
                    }
                    SyncV2Codec.unhex(token.getString("operationId"),16)
                }
                key == "deletedChapters" -> {
                    val a = value as? JSONArray ?: SyncV2Codec.fail("invalid_schema"); var last=0L
                    for(i in 0 until a.length()) {
                        val n=a.get(i); if(n !is Int && n !is Long) SyncV2Codec.fail("invalid_schema")
                        val current=(n as Number).toLong(); if(current<=last || current>input.getLong("chapterCount")) SyncV2Codec.fail("invalid_schema"); last=current
                    }
                }
                key == "registerSources" -> {
                    val sources=value as? JSONObject ?: SyncV2Codec.fail("invalid_schema")
                    SyncV2Codec.exact(sources,fields("metadata reading independent favorite watchLater deletion"))
                    for(f in sources.keys()) if(sources.get(f) !is String) SyncV2Codec.fail("invalid_schema") else SyncV2Codec.text(sources.getString(f),1024)
                }
                value === JSONObject.NULL && key in nullable -> Unit
                else -> {
                    if(value !is String) SyncV2Codec.fail("invalid_schema")
                    val limit=when {
                        key in setOf("url","sourceUrl","guid","imageUrl","siteUrl","rssSourceUrl") -> 8192
                        key in setOf("title","name","description","excerpt","siteName","markdown","plainText","baseMarkdown") -> 245760
                        else -> 1024
                    }
                    SyncV2Codec.text(value,limit)
                    if(key == "instanceId") SyncV2Codec.unhex(value,16)
                }
            }
        }
        val required=when(kind) {
            "article" -> if(input.has("registerSources")) fields("articleId sourceDeviceId url contentHash updatedAt registerSources") else fields("articleId url")
            "book" -> fields("bookId fingerprint title chapterCount deletedChapters deletionClock deleted deletedAt updatedAt sourceDeviceId")
            "book-reading" -> fields("bookId chapterIndex isRead readingProgress readingPositionBytes readingPositionContentHash readingPositionChangedAt sourceDeviceId")
            "note" -> noteFields; "folder" -> folderFields; "asset" -> assetFields
            "source" -> fields("url")
            "rss-state" -> fields("sourceId articleIdHash")
            else -> emptySet()
        }
        if(required.any { !input.has(it) }) SyncV2Codec.fail("invalid_schema")
        if(kind=="book") {
            val fingerprint=input.getString("fingerprint");val id=input.getString("bookId")
            if(!Regex("[0-9a-f]{64}").matches(fingerprint) || !Regex("[0-9a-f]{64}").matches(id))SyncV2Codec.fail("invalid_schema")
            val instance=input.optString("instanceId","")
            val fields=if(input.has("instanceId"))listOf("watchrss/book/instance-id/v1",fingerprint,instance) else listOf("watchrss/book/id/v1",fingerprint)
            if(SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.lp(*fields.map(SyncV2Codec::utf8).toTypedArray())))!=id)SyncV2Codec.fail("invalid_schema")
        }
    }
    data class PairObjects(val meta: JSONObject, val state: JSONObject)
    /** Frozen business identity, distinct from a signed outer logicalId supplied by a peer. */
    fun logicalId(kind:String,value:JSONObject):String = when(kind) {
        "article" -> SyncV2Wire.string(value,"articleId")
        "source" -> SyncV2Wire.string(value,"url")
        "book" -> SyncV2Wire.string(value,"bookId")
        "book-reading" -> SyncV2Wire.string(value,"bookId")+":"+SyncV2Wire.uint(value,"chapterIndex",100000)
        "note" -> SyncV2Wire.string(value,"noteId")
        "folder" -> SyncV2Wire.string(value,"folderId")
        "asset" -> SyncV2Wire.string(value,"assetId")
        "rss-state" -> SyncV2Wire.string(value,"sourceId")+":"+SyncV2Wire.string(value,"articleIdHash")
        else -> SyncV2Codec.fail("unsupported_feature")
    }
    fun split(kind: String, logicalId: String, entityKey: String, value: JSONObject, addedAt: Long?): PairObjects {
        validate(kind,value)
        // A missing channel is readable unknown data, never permission to rewrite an empty set.
        if(kind=="note" && !value.has("noteConflicts"))SyncV2Codec.fail("unsupported_feature")
        val meta=JSONObject(); val state=JSONObject()
        for(key in value.keys()) {
            if(key=="readingProgress") state.put("readingProgressMicros",kotlin.math.floor(value.getDouble(key)*1000000.0+0.5).toLong())
            else (if(key in metaFields) meta else state).put(key,value.get(key))
        }
        val m=JSONObject().put("version",2).put("entityKey",entityKey).put("kind",kind).put("logicalId",logicalId)
            .put("addedAtSource",if(addedAt==null)"unknown" else "original").put("requiredFeatures",JSONArray()).put("payload",meta)
        val s=JSONObject().put("version",2).put("entityKey",entityKey).put("requiredFeatures",JSONArray()).put("payload",state).put("noteSegments",JSONArray())
        for(obj in listOf(m,s)) if(SyncV2Codec.utf8(SyncV2Codec.canonical(obj)).size>262144) SyncV2Codec.fail("object_too_large")
        s.getJSONObject("payload").remove("noteConflicts")
        return PairObjects(m,if(kind=="note" && value.has("noteConflicts")) SyncV2NoteConflicts.attach(s,logicalId,entityKey,value.getJSONObject("noteConflicts")) else s)
    }
    fun join(kind: String, meta: JSONObject, state: JSONObject): JSONObject {
        val m=meta.getJSONObject("payload"); val s=state.getJSONObject("payload")
        if(m.keys().asSequence().any(s::has)) SyncV2Codec.fail("invalid_schema")
        val out=JSONObject(m.toString())
        for(key in s.keys()) {
            if(key=="readingProgressMicros") {
                if(m.has("readingProgress") || s.has("readingProgress")) SyncV2Codec.fail("invalid_schema")
                val p=s.get(key); if(p !is Int && p !is Long) SyncV2Codec.fail("invalid_schema")
                val n=(p as Number).toLong(); if(n !in 0..1000000) SyncV2Codec.fail("invalid_schema")
                out.put("readingProgress",n/1000000.0)
            } else out.put(key,s.get(key))
        }
        validate(kind,out); return out
    }
    fun mergeBook(a: JSONObject,b: JSONObject): JSONObject {
        validate("book",a); validate("book",b)
        if(listOf("bookId","instanceId","fingerprint","chapterCount").any { SyncV2Codec.canonical(a.opt(it)) != SyncV2Codec.canonical(b.opt(it)) }) SyncV2Codec.fail("invalid_schema")
        val x=a.optJSONObject("lifecycle"); val y=b.optJSONObject("lifecycle")
        val token=compareValues(x?.getLong("generation")?:0,y?.getLong("generation")?:0).takeIf{it!=0}
            ?: compareValues(x?.getString("operationId")?:"",y?.getString("operationId")?:"")
        if(token!=0) return JSONObject((if(token>0)a else b).toString())
        val rank=compareValuesBy(a,b,{it.getLong("updatedAt")},{it.getString("sourceDeviceId")},{it.getString("title")},{it.optString("sourceUrl","")},{it.optString("kind","")})
        return JSONObject((if(rank>=0)a else b).toString()).apply {
            val removed=(listOf(a,b).flatMap {v->v.getJSONArray("deletedChapters").let{arr->(0 until arr.length()).map(arr::getInt)}}).toSortedSet()
            put("deletedChapters",JSONArray(removed.toList())); put("deleted",a.getBoolean("deleted") || b.getBoolean("deleted"))
            for(f in listOf("deletionClock","deletedAt")) put(f,maxOf(a.getLong(f),b.getLong(f)))
        }
    }
    fun mergeNote(base: String,local: String,remote: String): JSONObject = when(val result=MarkdownThreeWayMerge.merge(base,local,remote)) {
        is MarkdownMergeResult.Merged -> JSONObject().put("markdown",result.markdown)
        MarkdownMergeResult.Conflict -> JSONObject().put("conflict",true)
    }
    /** All explicit adjacency is checked, including unchanged/unreachable nodes (W03). */
    fun affected(before: JSONObject,target: JSONObject,complete: Boolean=true): List<String>? {
        if(!complete) return null
        for(g in listOf(before,target)) for(id in g.keys()) {
            val r=g.optJSONObject(id)?:return null
            if(r.has("dependencies")) {
                val deps=r.optJSONArray("dependencies")?:return null
                for(i in 0 until deps.length()) if(deps.opt(i) !is String || deps.getString(i).isEmpty()) return null
            }
        }
        val pending=ArrayDeque<String>(); val found=sortedSetOf<String>()
        (before.keys().asSequence().toSet()+target.keys().asSequence().toSet()).forEach {
            if(SyncV2Codec.canonical(before.opt(it))!=SyncV2Codec.canonical(target.opt(it))) pending.add(it)
        }
        while(pending.isNotEmpty()) {
            val id=pending.removeLast(); if(!found.add(id))continue
            if(!before.has(id) && !target.has(id)) return null
            for(g in listOf(before,target)) if(g.has(id)) {
                val r=g.getJSONObject(id)
                if(r.opt("authenticated")==false || r.opt("dependenciesComplete")==false) return null
                val deps=r.optJSONArray("dependencies")?:JSONArray()
                for(i in 0 until deps.length()) if(deps.getString(i) !in found) pending.add(deps.getString(i))
            }
        }
        return found.toList()
    }
}
