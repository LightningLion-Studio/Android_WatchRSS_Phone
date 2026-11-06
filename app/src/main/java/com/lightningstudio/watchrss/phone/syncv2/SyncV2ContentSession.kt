package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Host-facing bounded content entry points. They never schedule their own next OS window,
 * send applied ACKs, mutate business files, or claim that object transport publishes an account.
 * Construct with a dedicated content client, leaving the P2 control client independent. */
internal class SyncV2ContentSession(private val identity:SyncV2Identity,private val store:SyncV2ContentStore,
    private val worker:SyncV2ContentWorker,private val client:SyncV2CloudClient,private val signer:SyncV2Signing,
    private val exporter:SyncV2ContentExport,private val clock:()->Long={System.nanoTime()/1000000}) {
    /** Original immutable package only. The caller's version comes from its body witness.
     * File size/mtime alone must never be used as authentication or proof of body equality. */
    class FileSource(private val file:File,override val version:String):SyncV2ContentExport.Source {
        private val length=file.length();private val modified=file.lastModified()
        override val size:Long get() {if(file.length()!=length || file.lastModified()!=modified)SyncV2Codec.fail("body_revision_changed");return length}
        override fun read(offset:Long,maxBytes:Int):ByteArray {
            if(offset<0 || maxBytes !in 1..262144 || offset+maxBytes>size)SyncV2Codec.fail("invalid_schema")
            val out=ByteArray(maxBytes);RandomAccessFile(file,"r").use{it.seek(offset);it.readFully(out)}
            size;return out
        }
    }
    data class Prepared(val capturedRow:JSONObject,val descriptor:JSONObject)
    data class Outcome(val status:String,val preparedBytes:Long,val transferredBytes:Long,val objects:Int,val ready:List<Prepared>)
    /** Export missing versions only, then upload their exact sealed bytes. The attach callback
     * must perform the projection's short rowSequence/bodyRevision CAS, outside content IO. */
    fun uploadPage(queue:SyncV2ContentQueue,source:(JSONObject)->SyncV2ContentExport.Source?,
        attach:(JSONObject,String,JSONObject)->Unit,recentUniverse:List<SyncV2ContentPriority.Item>,budget:SyncV2ContentWorker.Budget=SyncV2ContentWorker.Budget()):Outcome {
        return client.withinBudget(budget.millis-budget.reserveMillis) {uploadWindow(queue,source,attach,recentUniverse,budget)}
    }
    private fun uploadWindow(queue:SyncV2ContentQueue,source:(JSONObject)->SyncV2ContentExport.Source?,attach:(JSONObject,String,JSONObject)->Unit,
        recentUniverse:List<SyncV2ContentPriority.Item>,budget:SyncV2ContentWorker.Budget):Outcome {
        val start=clock();val before=store.counts.sourceBytes;val ready=mutableListOf<Prepared>()
        worker.startWindow()
        val jobs=queue.page().filter{!it.getBoolean("complete") && !it.getJSONObject("row").getBoolean("deleted")}
        val incomplete=jobs.toMutableList();val sources=mutableMapOf<String,SyncV2ContentExport.Source>()
        var sourcePending=false;var preparationPaused=false
        while(incomplete.isNotEmpty()) {
            worker.gate();val demands=worker.pollDemands()
            val ranked=queue.localPriorities(incomplete,recentUniverse,System.currentTimeMillis(),includeDemands=false)
            val job=ranked.sortedWith(compareBy<Pair<JSONObject,Int>>{ entry ->
                val row=entry.first.getJSONObject("row")
                if(demands.any{it.get("entityKey")==row.get("entityKey") && it.get("bodyRevision")==row.get("bodyRevision")})0 else entry.second
            }.thenByDescending{entry ->
                val row=entry.first.getJSONObject("row")
                demands.firstOrNull{it.get("entityKey")==row.get("entityKey") && it.get("bodyRevision")==row.get("bodyRevision")}?.getLong("demandAtMillis")?:entry.first.getLong("addedAtMillis")
            }.thenBy{it.first.getJSONObject("row").getString("entityKey")}).first().first
            val row=job.getJSONObject("row");val binding=SyncV2ContentExport.Binding(row.getString("entityKey"),row.getString("bodyRevision"),exporter.authenticationBinding)
            var descriptor=exporter.descriptor(binding)
            if(descriptor==null) {
                val original=sources[binding.key()]?:source(job)?.also{sources[binding.key()]=it}
                if(original==null){sourcePending=true;incomplete.remove(job);continue}
                if(clock()-start>=budget.millis-budget.reserveMillis || store.counts.sourceBytes-before>=budget.bytes) {preparationPaused=true;break}
                exporter.step(binding,original);Thread.yield();descriptor=exporter.descriptor(binding)
            }
            if(descriptor!=null){ready.add(Prepared(row,descriptor));incomplete.remove(job)}
        }
        val remaining=budget.millis-(clock()-start)
        if(remaining<=budget.reserveMillis)return Outcome("budget_paused",store.counts.sourceBytes-before,0,0,ready)
        val tasks=mutableListOf<SyncV2ContentWorker.Upload>()
        val priority=queue.localPriorities(jobs,recentUniverse,System.currentTimeMillis(),includeDemands=false).associate{it.first.getJSONObject("row").getString("entityKey") to it.second}
        val added=jobs.associate{it.getJSONObject("row").getString("entityKey") to it.getLong("addedAtMillis")}
        for(p in ready) {
            val recipe=p.descriptor.getJSONObject("recipe");val blocks=recipe.getJSONArray("blocks");val entity=p.capturedRow.getString("entityKey");val rank=priority[entity]?:4
            for(i in 0 until blocks.length())tasks.add(SyncV2ContentWorker.Upload(blocks.getJSONObject(i).getString("oid"),rank,entity,addedAtMillis=added.getValue(entity),blockIndex=i,revision=p.capturedRow.getString("bodyRevision")))
            tasks.add(SyncV2ContentWorker.Upload(p.descriptor.getString("recipeOid"),rank,entity,addedAtMillis=added.getValue(entity),blockIndex=blocks.length(),revision=p.capturedRow.getString("bodyRevision")))
        }
        val result=worker.uploadScheduled({tasks},budget.copy(millis=remaining,reserveMillis=budget.reserveMillis.coerceAtMost(remaining-1)))
        if(result.status!="complete")return Outcome(result.status,store.counts.sourceBytes-before,result.durableBytes,result.completed,ready)
        for(p in ready) {
            worker.gate();val recipe=p.descriptor.getJSONObject("recipe");val blocks=recipe.getJSONArray("blocks");val oids=JSONArray()
            for(i in 0 until blocks.length())oids.put(blocks.getJSONObject(i).getString("oid"))
            val oid=p.descriptor.getString("recipeOid");val refsKey="recipe-refs/$oid"
            val refs=store.load(refsKey)?:SyncV2ContentCodec.refs(identity,signer,oid,oids).also{store.save(refsKey,it)}
            client.recipeRefs(refs)
            attach(p.capturedRow,oid,recipe)
            queue.complete(p.capturedRow.getString("entityKey"),p.capturedRow.getString("bodyRevision"))
        }
        return Outcome(if(preparationPaused)"budget_paused" else if(sourcePending)"source_pending" else "objects-complete",store.counts.sourceBytes-before,result.durableBytes,result.completed,ready)
    }
    data class DownloadOutcome(val status:String,val transferredBytes:Long,val completeBodies:Int)
    /** Classification stays local. Unknown classifications default to rank4. Supply the full
     * recent import universe (including cached imports) to preserve the two recent slots. */
    fun downloadPage(rows:List<JSONObject>,receiver:SyncV2ContentReceive,now:()->Long,
        currentRow:(String)->JSONObject?,cacheReady:(SyncV2ContentReceive.Verified)->Unit,
        budget:SyncV2ContentWorker.Budget=SyncV2ContentWorker.Budget(),priorities:List<SyncV2ContentPriority.Item> = emptyList()):DownloadOutcome {
        return client.withinBudget(budget.millis-budget.reserveMillis) {downloadWindow(rows,receiver,now,currentRow,cacheReady,budget,priorities)}
    }
    private fun downloadWindow(rows:List<JSONObject>,receiver:SyncV2ContentReceive,now:()->Long,currentRow:(String)->JSONObject?,
        cacheReady:(SyncV2ContentReceive.Verified)->Unit,budget:SyncV2ContentWorker.Budget,priorities:List<SyncV2ContentPriority.Item>):DownloadOutcome {
        require(rows.size<=64);val start=clock();var transferred=0L;var complete=0
        data class Job(val row:JSONObject,var recipe:SyncV2ContentReceive.Recipe?=null)
        val jobs=rows.filter{!it.getBoolean("deleted") && !it.isNull("bodyRevision") && !it.isNull("recipeOid")}.map{Job(it)}.toMutableList()
        var sourcePending=rows.any{!it.getBoolean("deleted") && !it.isNull("bodyRevision") && it.isNull("recipeOid")}
        worker.startWindow();store.recoverUploadCompletion();store.recoverDownloadAcceptance();val window=client.negotiate().getString("windowId")
        val known=priorities.associateBy{it.entityKey};val sizeCache=mutableMapOf<String,Int>()
        fun left():SyncV2ContentWorker.Budget? {
            val millis=budget.millis-(clock()-start);val bytes=budget.bytes-transferred
            if(millis<=budget.reserveMillis || bytes<=0)return null
            return budget.copy(millis=millis,bytes=minOf(bytes,65536))
        }
        fun frame(oid:String):SyncV2ContentWorker.Result? {
            val allowance=left()?:return null
            val total=sizeCache[oid]?:sizes(window,listOf(oid),now()).single().second.also{sizeCache[oid]=it}
            return worker.download(listOf(oid to total),allowance).also{transferred+=it.durableBytes}
        }
        while(jobs.isNotEmpty()) {
            worker.gate();val demand=worker.pollDemands()
            val local=jobs.map{j->val entity=j.row.getString("entityKey")
                (known[entity]?:SyncV2ContentPriority.Item(entity,"unknown",0)).copy(complete=false,deleted=false,demandAtMillis=null,demandExpiresAtMillis=null)}
            val universe=priorities.filter{p->local.none{it.entityKey==p.entityKey}}+local
            val ranks=SyncV2ContentPriority.order(universe,now()).associate{it.first.entityKey to it.second}
            val active=jobs.associateWith{j->demand.firstOrNull{it.get("entityKey")==j.row.get("entityKey") && it.get("bodyRevision")==j.row.get("bodyRevision")}}
            val assigned=jobs.associateWith{if(active[it]!=null)0 else ranks.getValue(it.row.getString("entityKey"))}
            val saved=store.load("fairness");val fairness=SyncV2ContentPriority.Fairness(saved?.getInt("demanded")?:0,saved?.getInt("nextOrdinary")?:1)
            val rank=fairness.choose(assigned.values.toSet())!!
            val job=jobs.filter{assigned[it]==rank}.sortedWith(compareByDescending<Job>{if(rank==0)active[it]!!.getLong("demandAtMillis")else known[it.row.getString("entityKey")]?.addedAtMillis?:0}
                .thenBy{it.row.getString("entityKey")}).first()
            val row=job.row
            try {
                if(job.recipe==null) {
                    val result=frame(row.getString("recipeOid"))?:return DownloadOutcome("budget_paused",transferred,complete)
                    if(result.status !in setOf("complete","budget_paused"))return DownloadOutcome(result.status,transferred,complete)
                    if(result.status=="complete")job.recipe=receiver.recipe(row,window)
                    else if(result.durableBytes==0L)return DownloadOutcome("budget_paused",transferred,complete)
                    continue
                }
                val recipe=job.recipe!!;val blocks=JSONObject(recipe.value).getJSONArray("blocks")
                val accepted=store.load("receive/${row.getString("recipeOid")}")
                val index=accepted?.getInt("index")?:0
                if(index<blocks.length()) {
                    val oid=blocks.getJSONObject(index).getString("oid")
                    val result=frame(oid)?:return DownloadOutcome("budget_paused",transferred,complete)
                    if(result.status !in setOf("complete","budget_paused"))return DownloadOutcome(result.status,transferred,complete)
                    if(result.status=="complete") {
                        store.beginDownloadAcceptance(row.getString("recipeOid"),oid,index,rank)
                        receiver.step(row,recipe);store.recoverDownloadAcceptance()
                    }else if(result.durableBytes==0L)return DownloadOutcome("budget_paused",transferred,complete)
                    // Re-select demand/rank after exactly one fine frame. Partial blobs retain
                    // their durable offset; only authenticated complete blobs affect fairness.
                    continue
                }
                val verified=receiver.step(row,recipe)?:SyncV2Codec.fail("closure_incomplete")
                val current=currentRow(row.getString("entityKey"))?:SyncV2Codec.fail("body_revision_changed")
                verified.bind(current){cacheReady(verified)};complete++;jobs.remove(job)
            }catch(e:IllegalStateException) {
                if(e.message in setOf("source_pending","source_unavailable")){sourcePending=true;jobs.remove(job)}else throw e
            }
        }
        return DownloadOutcome(if(sourcePending)"source_pending" else "complete",transferred,complete)
    }
    /** Resolve signed object sizes without interpreting a partial/promise as an empty body. */
    fun sizes(window:String,oids:List<String>,now:Long):List<Pair<String,Int>> {
        if(oids.size>64 || oids.distinct().size!=oids.size)SyncV2Codec.fail("invalid_schema")
        val sorted=oids.sorted();val response=client.haveWant(window,sorted)
        if(response.get("windowId")!=window)SyncV2Codec.fail("invalid_schema")
        val rows=response.getJSONArray("objects");if(rows.length()!=sorted.size)SyncV2Codec.fail("invalid_schema")
        return sorted.mapIndexed { i,oid ->
            val r=rows.getJSONObject(i);SyncV2Codec.exact(r,setOf("oid","status","totalBytes","durableOffset","receipt"))
            if(r.get("oid")!=oid)SyncV2Codec.fail("invalid_schema")
            if(r.get("status")!="present")SyncV2Codec.fail("source_pending")
            val total=SyncV2Wire.uint(r,"totalBytes",266264);if(total==0L || SyncV2Wire.uint(r,"durableOffset")!=total)SyncV2Codec.fail("invalid_schema")
            client.verifyReceipt(r.getJSONObject("receipt"),oid,total,total,now);oid to total.toInt()
        }
    }
}
