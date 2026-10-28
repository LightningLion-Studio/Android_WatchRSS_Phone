package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.book.BookSync
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibrarySyncPayload
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File

/** Batch adapter calls real merge + production encrypt/decrypt, without codec mocks. */
class SyncCorrectnessOracleTest {
    @Test fun exportLocalChaosWires() {
        val root=File(projectRoot(),"..")
        if(!File(root,"backend/r48-env.json").exists())return
        val env=JSONObject(File(root,"backend/r48-env.json").readText())
        // Offline codec consumption also remains valid after our fixture stops.
        check(env.getBoolean("localOnly"))
        val seed=JSONObject(File(env.getString("seedFile")).readText())
        val key=seed.getString("rootKeyHex").chunked(2).map{it.toInt(16).toByte()}.toByteArray()
        val user=seed.getString("userId")
        for(name in listOf("chaos-final-row.json","chaos-phone-row.json")){
            val file=File(root,name)
            if(file.exists()){
                val value=JSONObject(file.readText());val row=value.getJSONObject("row")
                val actual=PhoneCloudRecordCodec.decrypt(key,user,row.getString("recordId"),row.getJSONObject("envelope")).second
                assertEquals(name,PhoneCloudRecordCodec.canonical(value.getJSONObject("expected")),PhoneCloudRecordCodec.canonical(actual))
            }
        }
        val input=JSONObject(File(root,"chaos-inputs.json").readText()).getJSONArray("values")
        val outputs=JSONArray()
        var merged:JSONObject?=null
        for(i in 0 until input.length()){
            val value=BookSync.mergeReading(null,input.getJSONObject(i));merged=BookSync.mergeReading(merged,value)
            val id=PhoneCloudRecordCodec.id(key,user,"book-reading",value)
            val envelope=PhoneCloudRecordCodec.encrypt(key,user,id,1,"book-reading",value)
            assertEquals(PhoneCloudRecordCodec.canonical(value),PhoneCloudRecordCodec.canonical(PhoneCloudRecordCodec.decrypt(key,user,id,envelope).second))
            outputs.put(JSONObject().put("recordId",id).put("envelope",envelope).put("tombstone",false))
        }
        val id=PhoneCloudRecordCodec.id(key,user,"book-reading",merged!!)
        val out=JSONObject().put("values",outputs).put("final",JSONObject().put("recordId",id).put("envelope",PhoneCloudRecordCodec.encrypt(key,user,id,1,"book-reading",merged!!)).put("tombstone",false)).put("expected",merged)
        val file=File(root,"phone-wires.json");file.writeText(out.toString(2)+"\n");file.setReadable(false,false);file.setReadable(true,true)
    }
    @Test fun exportSharedCorpusThroughProductionMergesAndCodecs() {
        val corpusBytes=requireNotNull(javaClass.getResource("/sync-correctness-vectors.json")).readBytes()
        val corpus=JSONObject(corpusBytes.toString(Charsets.UTF_8)).getJSONArray("cases")
        val key=ByteArray(32){41};val user="synthetic-sync-correctness"
        // Pure merge doesn't access repository fields. Avoid introducing DAO mocks.
        val unsafe=Class.forName("sun.misc.Unsafe")
        val field=unsafe.getDeclaredField("theUnsafe").apply{isAccessible=true}
        val repo=unsafe.getMethod("allocateInstance",Class::class.java).invoke(field.get(null),PhoneCompanionRepository::class.java)
        val method=PhoneCompanionRepository::class.java.getDeclaredMethod("mergeArticleByLatest",PhoneArticleEntity::class.java,PhoneArticleEntity::class.java).apply{isAccessible=true}
        fun round(kind:String,value:JSONObject):JSONObject {
            val id=PhoneCloudRecordCodec.id(key,user,kind,value)
            val encrypted=PhoneCloudRecordCodec.encrypt(key,user,id,1,kind,value)
            return PhoneCloudRecordCodec.decrypt(key,user,id,encrypted).second
        }
        fun article(value:JSONObject):PhoneArticleEntity {
            val parsed=LibrarySyncPayload.parseArticles(JSONArray().put(value)).single()
            return parsed.copy(contentText=value.optString("contentText"),contentHtml=value.optString("contentHtml").takeUnless{it=="null"||it.isEmpty()})
        }
        fun merge(kind:String,a:JSONObject,b:JSONObject):JSONObject=round(kind,when(kind){
            "book"->BookSync.merge(a,b)
            "book-reading"->BookSync.mergeReading(a,b)
            else->{val v=method.invoke(repo,article(a),article(b)) as PhoneArticleEntity
                // Body codec and parser remain production implementations.
                val wire=LibrarySyncPayload.buildArticlesRequest("oracle",listOf(v))
                val parsed=LibrarySyncPayload.parseArticles(wire).single()
                assertEquals(v.contentText,parsed.contentText)
                JSONObject().apply { for(k in a.keys()) when(k){
                    "contentHtml"->put(k,v.contentHtml?:JSONObject.NULL)
                    "contentText"->put(k,v.contentText)
                    else->{val getter=PhoneArticleEntity::class.java.methods.firstOrNull{it.name=="get"+k.replaceFirstChar(Char::uppercaseChar)||it.name==k}
                        if(getter!=null)put(k,getter.invoke(v)?:JSONObject.NULL) }
                }}
            }
        })
        val out=JSONObject()
        for(i in 0 until corpus.length()){
            val c=corpus.getJSONObject(i);val kind=c.getString("domain");val vs=c.getJSONArray("values")
            fun normalized(v:JSONObject)=round(kind,if(kind=="book-reading")BookSync.mergeReading(null,v) else v)
            val a=normalized(vs.getJSONObject(0));val b=normalized(vs.getJSONObject(1));val d=normalized(vs.getJSONObject(2))
            val ab=merge(kind,a,b)
            val replay=JSONArray()
            if(kind!="article")for(order in listOf(listOf(a,b,d),listOf(a,d,b),listOf(b,a,d),listOf(b,d,a),listOf(d,a,b),listOf(d,b,a))){
                var state=order[0]
                for(value in order.drop(1)+order)state=round(kind,JSONObject(merge(kind,state,value).toString()))
                replay.put(state)
            }
            out.put(c.getString("id"),JSONObject().put("a",a).put("ab",ab).put("ba",merge(kind,b,a))
                .put("left",merge(kind,ab,d)).put("right",merge(kind,a,merge(kind,b,d)))
                .put("aa",merge(kind,a,a)).put("again",merge(kind,ab,b)).put("replay",replay))
        }
        File(projectRoot(),".codex-tmp/sync-correctness-oracle/phone-results.json").apply{parentFile.mkdirs()}.writeText(out.toString(2)+"\n")
        assertEquals(corpus.length(),out.length())
    }
    private fun projectRoot():File {
        var f=File(System.getProperty("user.dir")).absoluteFile
        while(!File(f,"app/build.gradle.kts").exists())f=f.parentFile?:error("phone project root missing")
        return f
    }
}
