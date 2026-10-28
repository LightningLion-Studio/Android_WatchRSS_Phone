package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.floor

/** The known projection is separate from the authenticated raw document. This is not a writer gate. */
internal object ArticleRegisterProvenance {
    const val FEATURE = "article-register-provenance.v1"
    const val readV1 = true // Production AEAD, shared vectors and durable native reader accepted; writer remains off.
    const val writeV1 = false
    const val MAX_INTEGER = 9_007_199_254_740_991L
    val registers = listOf("metadata", "reading", "independent", "favorite", "watchLater", "deletion")
    private val saves = listOf("independent", "favorite", "watchLater")
    private val metadata = listOf("url","title","siteName","excerpt","imageUrl","contentHash","importedAt","rssSourceUrl","rssSourceTitle")
    private val reading = listOf("isRead","readingProgress","readingPositionBytes","readingPositionContentHash","readingPositionChangedAt")
    val fields = (metadata + reading + listOf("articleId","sourceDeviceId","updatedAt","deleted","deletedAt","registerSources") +
        saves.flatMap { p -> listOf(p+"Saved",p+"ChangedAt",p+"SortOrder") }).toSet()
    private val bodyFields = setOf("contentText","contentHtml","contentTextGzip","contentHtmlGzip","body","chapters")
    data class Record(val value:JSONObject, val original:CloudRawDocument) {
        fun canonicalUtf8():String = canonical(envelope(value))
        fun sha256():String = CloudSnapshotCodec.sha256(canonicalUtf8().toByteArray(Charsets.UTF_8))
        fun positionStatus():String = when(val hash=value.getString("readingPositionContentHash")) {
            "" -> "unresolved"
            value.getString("contentHash") -> "applicable"
            else -> "requires_relocation"
        }
    }
    fun marked(document:CloudRawDocument):Boolean = document.json().optJSONObject("features")?.optJSONArray("roCompat")
        ?.let { a -> (0 until a.length()).any { a.opt(it)==FEATURE } } == true
    fun decode(bytes:ByteArray, allowReadOnlyExtensions:Boolean=false):Record {
        require(bytes.size<=65520) { "article_record_too_large" }
        return decode(CloudRawDocument.parse(bytes),allowReadOnlyExtensions)
    }
    fun decode(document:CloudRawDocument,allowReadOnlyExtensions:Boolean=false):Record = decodeBounded(document,allowReadOnlyExtensions,65520)
    // Six independently selected valid groups can exceed one wire record. Keep their reader
    // projection durable without relaxing the wire limit or granting a writer permission.
    fun decodeRetained(document:CloudRawDocument):Record=decodeBounded(document,true,6*65520+16384)
    private fun decodeBounded(document:CloudRawDocument,allowReadOnlyExtensions:Boolean,maxBytes:Int):Record {
        require(document.source.toByteArray(Charsets.UTF_8).size<=maxBytes)
        val root=document.json()
        require(root.opt("format")=="watchrss-rss-record" && root.opt("kind")=="article")
        require(integer(document,listOf("version"),null)==1L)
        val f=root.getJSONObject("features")
        require(f.keys().asSequence().toSet()==setOf("incompat","roCompat"))
        val all=hashSetOf<String>()
        fun names(key:String):List<String> {
            val a=f.getJSONArray(key);require(a.length()<=16)
            return (0 until a.length()).map { index ->
                val token=a.get(index);require(token is String && token.matches(Regex("[a-z0-9][a-z0-9.-]{0,47}")) && all.add(token));token
            }
        }
        val incompatible=names("incompat");val ro=names("roCompat")
        require(FEATURE in ro) { "article_provenance_missing_marker" }
        if(incompatible.isNotEmpty())throw CloudFormatUpgradeRequired("article_provenance_opaque")
        if(!allowReadOnlyExtensions && ro.any{it!=FEATURE})throw CloudFormatUpgradeRequired("article_provenance_read_only")
        val v=root.getJSONObject("value");require(bodyFields.none(v::has))
        val sources=v.getJSONObject("registerSources");require(sources.keys().asSequence().toSet()==registers.toSet())
        val sourceValues=JSONObject().also { out -> registers.forEach{out.put(it,string(sources.get(it)))} }
        val out=JSONObject().put("articleId",string(v.get("articleId"))).put("sourceDeviceId",string(v.get("sourceDeviceId")))
            .put("registerSources",sourceValues)
        require(!blank(out.getString("articleId")) && !blank(string(v.get("url"))))
        require(out.getString("sourceDeviceId")==sourceValues.getString("metadata"))
        listOf("url","title","siteName","excerpt","contentHash","readingPositionContentHash").forEach { key ->
            val default=if(key in setOf("url","contentHash"))null else ""
            out.put(key,string(if(v.has(key))v.get(key) else requireNotNull(default)))
        }
        listOf("imageUrl","rssSourceUrl","rssSourceTitle").forEach { key -> out.put(key,
            if(!v.has(key)||v.isNull(key))JSONObject.NULL else string(v.get(key))) }
        (listOf("importedAt","updatedAt","readingPositionBytes","readingPositionChangedAt","deletedAt")+saves.map{it+"ChangedAt"}).forEach { key ->
            out.put(key,integer(document,listOf("value",key),if(key=="updatedAt")null else 0L))
        }
        saves.forEach { out.put(it+"SortOrder",integer(document,listOf("value",it+"SortOrder"),0L,signed=true)) }
        (listOf("isRead","deleted")+saves.map{it+"Saved"}).forEach { key ->
            val b=if(v.has(key))v.get(key) else false;require(b is Boolean);out.put(key,b)
        }
        val progress=document.raw(listOf("value","readingProgress"))?.let { raw ->
            require(raw.matches(NUMBER));val d=BigDecimal(raw);require(d>=BigDecimal.ZERO && d<=BigDecimal.ONE)
            d.toDouble().also{require(it.isFinite())}
        } ?: 0.0
        out.put("readingProgress",floor(progress*1_000_000.0+0.5).toLong()/1_000_000.0)
        require(!out.getBoolean("deleted") || out.getLong("deletedAt")>0)
        return Record(out,document)
    }
    /** Complete old records only. Callers must exclude body-only/default-reading projections. */
    fun seedLegacy(raw:String):Record {
        val old=CloudRawDocument.parse(raw);require(old.node(listOf("registerSources"))==null)
        val source=old.json().opt("sourceDeviceId")?.let(::string) ?: ""
        var value=old.patch(listOf("sourceDeviceId"),quote(source))
        value=value.patch(listOf("registerSources"),canonical(JSONObject().also { out->registers.forEach{out.put(it,source)} }))
        if(integer(old,listOf("readingPositionChangedAt"),0L)==0L)
            value=value.patch(listOf("readingPositionChangedAt"),integer(old,listOf("updatedAt"),null).toString())
        return decode(CloudRawDocument.parse("{\"format\":\"watchrss-rss-record\",\"version\":1,\"kind\":\"article\",\"features\":{\"incompat\":[],\"roCompat\":[\"$FEATURE\"]},\"value\":${value.source}}"))
    }
    fun envelope(value:JSONObject):JSONObject=JSONObject().put("format","watchrss-rss-record").put("version",1).put("kind","article")
        .put("features",JSONObject().put("incompat",JSONArray()).put("roCompat",JSONArray().put(FEATURE))).put("value",value)
    fun merge(a:Record,b:Record):Record {
        require(a.value.getString("articleId")==b.value.getString("articleId"))
        val metadataOrder=compare(a.value,b.value,"metadata")
        if(metadataOrder==0 && extensions(a.original)!=extensions(b.original))
            throw CloudFormatUpgradeRequired("article_provenance_extension_conflict")
        val out=JSONObject().put("articleId",a.value.getString("articleId"));val sources=JSONObject()
        for(r in registers) {
            val win=if(compare(a.value,b.value,r)>=0)a.value else b.value
            val keys=when(r){"metadata"->metadata+"updatedAt";"reading"->reading;"deletion"->listOf("deleted","deletedAt");else->listOf(r+"Saved",r+"ChangedAt",r+"SortOrder")}
            keys.forEach{out.put(it,win.get(it))};sources.put(r,win.getJSONObject("registerSources").getString(r))
        }
        out.put("registerSources",sources).put("sourceDeviceId",sources.getString("metadata"))
        val base=if(metadataOrder>=0)a.original else b.original
        val raw=base.merge(listOf("value"),out)
        return Record(out,raw)
    }
    private fun extensions(doc:CloudRawDocument):List<String> = listOf(emptyList<String>() to PhoneCloudRecordCodec.rootFields,listOf("value") to fields)
        .flatMap { (path,known)->doc.node(path)?.properties.orEmpty().filter{it.key !in known}.map{doc.source.substring(it.keyStart,it.value.end)} }.sorted()
    private fun compare(a:JSONObject,b:JSONObject,r:String):Int {
        val clock=when(r){"metadata"->"updatedAt";"reading"->"readingPositionChangedAt";"deletion"->"deletedAt";else->r+"ChangedAt"}
        a.getLong(clock).compareTo(b.getLong(clock)).takeIf{it!=0}?.let{return it}
        a.getJSONObject("registerSources").getString(r).compareTo(b.getJSONObject("registerSources").getString(r)).takeIf{it!=0}?.let{return it}
        fun value(v:JSONObject):List<Any?> = when(r) {
            "metadata"->metadata.map{if(v.isNull(it))null else v.get(it)}
            "reading"->listOf(if(v.getBoolean("isRead"))0L else 1L,v.getLong("readingPositionBytes"),floor(v.getDouble("readingProgress")*1e6+0.5).toLong(),v.getString("readingPositionContentHash"))
            "deletion"->listOf(if(v.getBoolean("deleted"))1L else 0L)
            else->listOf(if(v.getBoolean(r+"Saved"))0L else 1L,v.getLong(r+"SortOrder"))
        }
        for((x,y) in value(a).zip(value(b))) {
            val c=when { x==null && y==null->0;x==null->-1;y==null->1;x is String && y is String->x.compareTo(y);else->(x as Number).toLong().compareTo((y as Number).toLong()) }
            if(c!=0)return c
        };return 0
    }
    private val NUMBER=Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private fun integer(doc:CloudRawDocument,path:List<String>,default:Long?,signed:Boolean=false):Long {
        val raw=doc.raw(path)?:return requireNotNull(default)
        require(raw.matches(NUMBER));val d=BigDecimal(raw)
        require(d.stripTrailingZeros().scale()<=0 && d.abs()<=BigDecimal.valueOf(MAX_INTEGER) && (signed||d.signum()>=0))
        return d.longValueExact()
    }
    private fun string(v:Any):String {
        require(v is String);var i=0
        while(i<v.length){val c=v[i++];if(c.isHighSurrogate())require(i<v.length&&v[i++].isLowSurrogate()) else require(!c.isLowSurrogate())};return v
    }
    private fun blank(s:String):Boolean=s.all{it in '\u0009'..'\u000d'||it in '\u001c'..'\u0020'||it=='\u00a0'||it=='\u1680'||it in '\u2000'..'\u200a'||it in listOf('\u2028','\u2029','\u202f','\u205f','\u3000')}
    private fun quote(s:String):String=buildString {
        append('"');for(c in string(s))append(when(c){'"'->"\\\"";'\\'->"\\\\";'\b'->"\\b";'\t'->"\\t";'\n'->"\\n";'\u000c'->"\\f";'\r'->"\\r";else->if(c<' ')"\\u"+c.code.toString(16).padStart(4,'0') else c.toString()});append('"')
    }
    fun canonical(v:Any?,key:String?=null):String=when(v) {
        null,JSONObject.NULL->"null"
        is JSONObject->v.keys().asSequence().toList().sorted().joinToString(",","{","}"){quote(it)+":"+canonical(v.get(it),it)}
        is JSONArray->(0 until v.length()).joinToString(",","[","]"){canonical(v.get(it))}
        is String->quote(v)
        is Boolean->v.toString()
        is Number->if(key=="readingProgress") {
            val u=floor(v.toDouble()*1e6+0.5).toLong()
            if(u%1_000_000L==0L)(u/1_000_000L).toString() else "0."+(u%1_000_000L).toString().padStart(6,'0').trimEnd('0')
        } else BigDecimal(v.toString()).longValueExact().toString()
        else->error("article_value_invalid")
    }
}
