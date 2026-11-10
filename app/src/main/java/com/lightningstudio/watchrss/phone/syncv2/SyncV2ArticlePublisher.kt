package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.io.File

/** Bounded actual v1 body -> durable exporter -> content upload -> refs -> index row CAS. */
internal class SyncV2ArticlePublisher(private val projection:SyncV2Projection,private val identity:SyncV2Identity,
    private val keys:SyncV2ContentKeys,private val signer:SyncV2Signing,private val client:SyncV2CloudClient,
    private val store:SyncV2ContentStore,private val worker:SyncV2ContentWorker,private val files:File,
    private val check:()->Unit,private val monotonic:()->Long=android.os.SystemClock::elapsedRealtime) {
    fun runPage(budgetMillis:Long=280000):Boolean {
        val started=monotonic();val version=keys.newestVersion
        val exporter=SyncV2ContentExport(identity,keys.read(version),version,store)
        val cursor=store.load("p6/article-cursor")?.optString("after","")?:""
        val page=projection.accountDatabase.controlQuery("SELECT entity_key,logical_id FROM syncv2_candidates WHERE scope=? AND kind='article' AND entity_key>? ORDER BY entity_key LIMIT 17",arrayOf(projection.scope,cursor)).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
        var remaining=32L*1024*1024
        for((entity,id)in page.take(16)) {
            check();if(monotonic()-started>=budgetMillis || remaining<=0)return false
            var row=projection.wireContentRow(entity)?:SyncV2Codec.fail("projection_incomplete")
            if(!row.getBoolean("deleted")) {
                val revision=projection.state().revision
                val source=SyncV2ArticleBody.source(projection.accountDatabase,files,id) {
                    check();if(projection.state().revision!=revision)SyncV2Codec.fail("body_revision_changed")
                }
                if(source!=null) {
                    row=projection.bindArticleRevision(row,SyncV2ArticleBody.revision(entity,source.sha256))
                    if(row.isNull("recipeOid")) {
                        val binding=SyncV2ContentExport.Binding(entity,row.getString("bodyRevision"),exporter.authenticationBinding)
                        while(exporter.descriptor(binding)==null) {
                            check();if(monotonic()-started>=budgetMillis || remaining<=0)return false
                            val before=store.counts.sourceBytes;exporter.step(binding,source);remaining-=store.counts.sourceBytes-before
                        }
                        val prepared=exporter.descriptor(binding)!!;val recipe=prepared.getJSONObject("recipe")
                        if(recipe.getString("plaintextSha256")!=source.sha256)SyncV2Codec.fail("body_revision_changed")
                        val blocks=recipe.getJSONArray("blocks")
                        val oids=(0 until blocks.length()).map{blocks.getJSONObject(it).getString("oid")}+prepared.getString("recipeOid")
                        val timeLeft=budgetMillis-(monotonic()-started)
                        if(timeLeft<1000)return false
                        val result=worker.upload(oids,SyncV2ContentWorker.Budget(timeLeft.coerceAtMost(300000),0,remaining.coerceIn(1,32L*1024*1024)))
                        if(result.status!="complete")SyncV2Codec.fail(result.status)
                        check()
                        val recipeOid=prepared.getString("recipeOid")
                        val refsName="recipe-refs/$recipeOid"
                        val refs=store.load(refsName)?:SyncV2ContentCodec.refs(identity,signer,recipeOid,org.json.JSONArray(oids.dropLast(1))).also{store.save(refsName,it)}
                        client.recipeRefs(refs)
                        projection.attachContentRecipe(row,recipeOid,recipe)
                        remaining-=result.durableBytes
                    }
                }
            }
            store.save("p6/article-cursor",JSONObject().put("after",entity))
        }
        if(page.size<=16){store.save("p6/article-cursor",JSONObject().put("after",""));return true}
        return false
    }
}
