package com.lightningstudio.watchrss.phone.diagnostics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Every serialized object is constructed from constants; never serialize wire JSON or exceptions. */
object DiagnosticFields {
    const val HISTORY_LIMIT=12
    const val ITEM_LIMIT=256
    private val codes=setOf("none","unknown","sync_failed","peer_rejected","ack_missing","book_manifest_invalid","book_package_invalid","book_request_invalid","book_sync_not_negotiated","book_reading_delta_not_negotiated","library_busy","library_exchange_failed","unsupported_action","account_required","binding_required","body_missing","package_missing","package_hash_mismatch","metadata_invalid","read_failed","staged","registration_pending")
    fun code(value:String)=value.takeIf{it in codes}?:"unknown"
    fun id(value:String?)=value?.takeIf{it.matches(Regex("[0-9a-f]{64}"))}
    fun title(value:String)=value.replace(Regex("[\\x00-\\x1f\\x7f]")," ").replace(Regex("(?i)[a-z][a-z0-9+.-]{1,31}://[^\\s]+"),"[redacted]").take(256)
    fun number(value:Long)=value.coerceAtLeast(0)
    fun item(kind:String,id:String?,result:String,code:String)=JSONObject()
        .put("kind",kind.takeIf{it in setOf("book","article","note","asset","reader","library","account","unknown")}?:"unknown")
        .put("id",if(kind in setOf("book","asset"))id(id)?:JSONObject.NULL else if(kind in setOf("article","note"))itemId(id)?:JSONObject.NULL else JSONObject.NULL).put("result",if(result=="applied")"applied" else "rejected").put("reasonCode",code(code))
    private fun itemId(value:String?)=value?.takeIf{it.matches(Regex("[0-9a-f]{64}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))}
    fun requestItems(request:JSONObject):List<Pair<String,String?>> {
        val kind=when(request.optString("action")){"syncBooks"->"book";"syncNotes"->"note";"syncNoteAsset"->"asset";"syncReader"->"reader";"syncAccount"->"account";else->"library"}
        val rows=if(kind=="note")request.optJSONArray("notes")?.takeIf{it.length()>0}?:request.optJSONArray("noteManifest")else null
        val ids=if(rows!=null&&rows.length()>0)(0 until minOf(rows.length(),ITEM_LIMIT+1)).map{rows.optJSONObject(it)?.optString("noteId")}else listOf(request.optString(when(kind){"book"->"bookId";"asset"->"sha256";else->"noteId"}))
        return ids.map{kind to if(kind in setOf("book","asset"))id(it)else if(kind=="note")itemId(it)else null}
    }
    fun book(id:String,title:String,chapters:Int,bytes:Long,sha:String?,status:String,reason:String,readable:Boolean,manifest:JSONObject?=null):JSONObject=JSONObject()
        .put("bookId",id(id)?:JSONObject.NULL).put("title",title(title)).put("chapterCount",number(chapters.toLong()))
        .put("packageBytes",number(bytes)).put("packageSha256",id(sha)?:JSONObject.NULL)
        .put("status",status.takeIf{it in setOf("ready","staged","failed")}?:"failed").put("reasonCode",code(reason)).put("readable",readable)
        .apply { if(manifest!=null)put("manifest",JSONObject().put("fingerprint",id(manifest.optString("fingerprint"))?:JSONObject.NULL).put("chapterCount",number(manifest.optLong("chapterCount"))).put("deleted",manifest.optBoolean("deleted"))) }
    fun summary(channels:Long=0,articles:Long=0,favorites:Long=0,watchLater:Long=0,memos:Long=0,books:Long=0)=JSONObject()
        .put("channels",number(channels)).put("articles",number(articles)).put("favorites",number(favorites)).put("watchLater",number(watchLater)).put("memos",number(memos)).put("books",number(books))
    fun record(raw:JSONObject):JSONObject=JSONObject().put("startedAt",number(raw.optLong("startedAt")))
        .put("endedAt",if(raw.isNull("endedAt"))JSONObject.NULL else number(raw.optLong("endedAt")))
        .put("channel",if(raw.optString("channel")=="IP")"IP" else "RFCOMM")
        .put("result",raw.optString("result").takeIf{it in setOf("running","success","partial","failed")}?:"failed")
        .put("totalBytes",number(raw.optLong("totalBytes"))).put("bytesPerSecond",number(raw.optLong("bytesPerSecond")))
        .put("reconnectCount",number(raw.optLong("reconnectCount")))
        .put("items",JSONArray().apply { val items=raw.optJSONArray("items")?:JSONArray();repeat(minOf(items.length(),ITEM_LIMIT)){i->items.optJSONObject(i)?.let{put(item(it.optString("kind"),it.optString("id"),it.optString("result"),it.optString("reasonCode")))}} })
        .put("itemsTruncated",raw.optBoolean("itemsTruncated")||(raw.optJSONArray("items")?.length()?:0)>ITEM_LIMIT)
    fun selection(args:Array<out String>):Pair<String,Int> {
        if(args.isEmpty()||args.size==1&&args[0].matches(Regex("[0-9]+")))return "all" to lastCount(args)
        require(args[0] in setOf("library.books","library.summary","sync.last")){"invalid_argument"}
        if(args[0]=="sync.last")return args[0] to lastCount(args.drop(1).toTypedArray())
        require(args.size==1){"invalid_argument"}
        return args[0] to 3
    }
    fun lastCount(args:Array<out String>):Int {
        require(args.isEmpty()||args.size==1&&args[0].matches(Regex("[1-9][0-9]?"))&&args[0].toInt()<=HISTORY_LIMIT){"invalid_argument"}
        return args.firstOrNull()?.toInt()?:3
    }
}

interface DiagnosticStorage {fun read():String?;fun write(value:String)}
class SyncDiagnostics(private val storage:DiagnosticStorage,private val now:()->Long=System::currentTimeMillis,private val monotonic:()->Long=android.os.SystemClock::elapsedRealtime) {
    companion object {
        fun open(context:Context):SyncDiagnostics {
            val prefs=context.getSharedPreferences("sync_readonly_diagnostics",Context.MODE_PRIVATE)
            return SyncDiagnostics(object:DiagnosticStorage {override fun read()=prefs.getString("history",null);override fun write(value:String){check(prefs.edit().putString("history",value).commit())}})
        }
    }
    @Synchronized fun last(n:Int=3):JSONArray {
        val raw=runCatching{JSONArray(storage.read()?:"[]")}.getOrDefault(JSONArray())
        return JSONArray().apply{for(i in raw.length()-1 downTo maxOf(0,raw.length()-DiagnosticFields.HISTORY_LIMIT)) {if(length()>=n)break;raw.optJSONObject(i)?.let{put(DiagnosticFields.record(it))}}}
    }
    @Synchronized private fun save(raw:JSONObject) {
        runCatching {
            val previous=last(DiagnosticFields.HISTORY_LIMIT);val rows=mutableListOf<JSONObject>()
            for(i in previous.length()-1 downTo 0)previous.getJSONObject(i).let{if(!(it.optLong("startedAt")==raw.optLong("startedAt")&&it.isNull("endedAt")))rows+=it}
            rows+=DiagnosticFields.record(raw)
            storage.write(JSONArray(rows.takeLast(DiagnosticFields.HISTORY_LIMIT)).toString())
        }
    }
    fun bookRejections():Map<String,String> {
        val seen=hashSetOf<String>();val reasons=linkedMapOf<String,String>();val sessions=last(DiagnosticFields.HISTORY_LIMIT)
        for(i in 0 until sessions.length()){val items=sessions.getJSONObject(i).getJSONArray("items");for(j in items.length()-1 downTo 0){val item=items.getJSONObject(j);val id=item.optString("id");if(item.optString("kind")!="book"||DiagnosticFields.id(id)==null||!seen.add(id))continue;if(item.optString("result")=="rejected")reasons[id]=item.getString("reasonCode")}}
        return reasons
    }
    fun begin()=Trace()
    inner class Trace {
        private val at=now();private val tick=monotonic();private var bytes=0L;private var reconnects=0;private var channel="RFCOMM";private var finished=false
        private val items=JSONArray();private var truncated=false
        init {persist("running",null)}
        @Synchronized fun bytes(value:Long){bytes+=value.coerceAtLeast(0)}
        @Synchronized fun channel(ip:Boolean){channel=if(ip)"IP" else "RFCOMM"}
        @Synchronized fun reconnect(){reconnects++}
        @Synchronized fun item(kind:String,id:String?,result:String,code:String){if(items.length()<DiagnosticFields.ITEM_LIMIT)items.put(DiagnosticFields.item(kind,id,result,code)) else truncated=true}
        private fun persist(result:String,end:Long?)=save(JSONObject().put("startedAt",at).put("endedAt",end?:JSONObject.NULL).put("channel",channel).put("result",result).put("totalBytes",bytes).put("bytesPerSecond",if(end==null)0 else bytes*1000/maxOf(1,monotonic()-tick)).put("reconnectCount",reconnects).put("items",items).put("itemsTruncated",truncated))
        @Synchronized fun finish(success:Boolean){if(finished)return;finished=true;persist(if(!success)"failed" else if((0 until items.length()).any{items.getJSONObject(it).optString("result")=="rejected"})"partial" else "success",now())}
    }
}
