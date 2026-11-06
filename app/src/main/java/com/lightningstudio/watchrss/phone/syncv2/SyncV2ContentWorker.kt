package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Own HTTP client/connection pool, worker and store. No account mutex or business transaction.
 * Execution is opt-in and loopback-only through SyncV2CloudClient. Scheduling into a new OS
 * window belongs to the host; this class never immediately opens another budget window. */
internal class SyncV2ContentWorker(private val client:SyncV2CloudClient,private val identity:SyncV2Identity,
    private val store:SyncV2ContentStore,private val signals:()->SyncV2TransportSignals,
    private val allowChargingCellular:()->Boolean={true},private val enabled:Boolean=BuildConfig.SYNC_OBJECTS_V2_CONTENT,
    private val clock:()->Long={System.nanoTime()/1000000},private val now:()->Long={System.currentTimeMillis()},
    private val yieldControl:()->Unit={Thread.yield()},private val approvedDevice:(String)->Boolean={false},
    private val reserveRequestId:()->String={SyncV2Codec.hex(SyncV2Codec.random(16))}) {
    data class Budget(val millis:Long=300000,val reserveMillis:Long=10000,val bytes:Long=32L*1024*1024) {
        init {require(millis in 1..300000 && reserveMillis in 0 until millis && bytes in 1..32L*1024*1024)}
    }
    data class Result(val status:String,val durableBytes:Long,val completed:Int,val elapsedMillis:Long,val retryAfterMillis:Long?=null)
    private inline fun <T> network(work:()->T):T {
        val at=System.nanoTime();try{return work()}finally{store.counts.networkNanos+=System.nanoTime()-at}
    }
    private val running=AtomicBoolean(false)
    private var demandBase=0L;private var demandReadAt:Long?=null
    private var activeDemands=emptyList<JSONObject>()
    internal fun pollDemands(force:Boolean=false):List<JSONObject> {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        val at=clock();val last=demandReadAt
        if(force || last==null || at-last>=30000) {
            val value=client.demands();SyncV2Codec.exact(value,setOf("version","scope","serverNowMillis","demands"))
            val serverNow=SyncV2Wire.uint(value,"serverNowMillis");val rows=value.getJSONArray("demands")
            if(rows.length()>32)SyncV2Codec.fail("invalid_schema")
            val result=mutableListOf<JSONObject>();val seen=mutableSetOf<String>();var previous:JSONObject?=null
            for(i in 0 until rows.length()) {
                val d=rows.getJSONObject(i);SyncV2Codec.exact(d,setOf("demandId","entityKey","bodyRevision","requesterDeviceId","demandAtMillis","expiresAtMillis"))
                for(k in listOf("demandId","bodyRevision","requesterDeviceId"))SyncV2Codec.unhex(SyncV2Wire.string(d,k),16)
                SyncV2Codec.unhex(SyncV2Wire.string(d,"entityKey"),32)
                val time=SyncV2Wire.uint(d,"demandAtMillis");val expires=SyncV2Wire.uint(d,"expiresAtMillis")
                if(time>serverNow+30000 || expires<=serverNow || expires-serverNow>600000 || !seen.add(d.getString("entityKey")+d.getString("bodyRevision")))SyncV2Codec.fail("invalid_schema")
                val p=previous
                if(p!=null && (p.getLong("demandAtMillis")<time || p.getLong("demandAtMillis")==time &&
                    (p.getString("entityKey")>d.getString("entityKey") || p.getString("entityKey")==d.getString("entityKey") && p.getString("bodyRevision")>=d.getString("bodyRevision"))))SyncV2Codec.fail("invalid_schema")
                previous=d
                if(approvedDevice(d.getString("requesterDeviceId")))result.add(d)
            }
            activeDemands=result;demandBase=serverNow;demandReadAt=at
        }
        val estimated=demandBase+(at-(demandReadAt?:at)).coerceAtLeast(0)
        return activeDemands.filter{it.getLong("expiresAtMillis")>estimated}
    }
    internal fun startWindow() {gate();client.capabilities();pollDemands(true)}
    internal fun gate() {
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        if(Thread.currentThread().isInterrupted)SyncV2Codec.fail("budget_paused")
        if(!signals().eligible(allowChargingCellular()).getBoolean("cloudAllowed"))SyncV2Codec.fail("transport_wait")
    }
    fun sendDemand(entity:String,revision:String,demandId:String,at:Long,expires:Long):JSONObject {
        // demand is a control request. It does not open or bypass the content gate.
        SyncV2Codec.unhex(entity,32);SyncV2Codec.unhex(revision,16);SyncV2Codec.unhex(demandId,16)
        val n=now();if(at<0 || kotlin.math.abs(at-n)>30000 || expires<=n || expires-n>600000)SyncV2Codec.fail("invalid_schema")
        if(!enabled)SyncV2Codec.fail("feature_disabled")
        val response=client.demand(JSONObject().put("scope",identity.scope).put("entityKey",entity).put("bodyRevision",revision)
            .put("requesterDeviceId",client.deviceId).put("demandId",demandId).put("demandAtMillis",at).put("expiresAtMillis",expires))
        pollDemands(true);return response
    }
    data class Upload(val oid:String,val rank:Int=4,val entity:String="",val addedAtMillis:Long=0,val blockIndex:Int=0,val demandAtMillis:Long=0,val revision:String="")
    fun upload(oids:List<String>,budget:Budget=Budget()):Result=uploadScheduled({oids.map{Upload(it)}},budget)
    /** The supplier is sampled at every fine-frame boundary so current demand can preempt.
     * Only completed blobs advance the durable fairness counters. */
    fun uploadScheduled(items:()->List<Upload>,budget:Budget=Budget()):Result = window(budget) { window,remaining,advance ->
        data class Transfer(val total:Int,val reservation:String,var offset:Int)
        val transfers=mutableMapOf<String,Transfer>();val done=mutableSetOf<String>();val exactCache=mutableMapOf<String,Pair<String,JSONObject?>>()
        val renewed=mutableMapOf<String,String>();val forceRenew=mutableSetOf<String>();val expiryRetries=mutableMapOf<String,Int>()
        fun reserve(oid:String,total:Int,fresh:Boolean):String {
            val response=client.reserve(if(fresh)id()else reserveRequestId(),listOf(oid to total),"content")
            response.getJSONArray("objects").singleObject(oid,total)
            if(SyncV2Wire.uint(response,"expiresAtMillis")<=now())throw SyncV2CloudError("reservation_expired")
            return SyncV2Wire.string(response,"reservationId").also{SyncV2Codec.unhex(it,16)}
        }
        fun exact(expected:List<Pair<String,Int>>):Map<String,Pair<String,JSONObject?>> = try {haveBatch(window,expected)}catch(e:SyncV2CloudError) {
            if(e.code!="reservation_expired")throw e
            // C8 cannot settle a full prefix without this actor's live reservation. Renew
            // before querying again, retaining the same OID, bytes and confirmation ledger.
            expected.associate { (oid,total) ->
                renewed[oid]=reserve(oid,total,true)
                oid to haveBatch(window,listOf(oid to total)).getValue(oid)
            }
        }
        store.recoverUploadCompletion()
        store.recoverDownloadAcceptance()
        while(true) {
            gate()
            if(remaining()<=0)return@window "budget_paused"
            val demands=pollDemands()
            val pending=items().filter{it.oid !in done}.map { item ->
                val d=demands.firstOrNull{it.getString("entityKey")==item.entity && it.getString("bodyRevision")==item.revision}
                if(d==null)item else item.copy(rank=0,demandAtMillis=d.getLong("demandAtMillis"))
            }
            val saved=store.load("fairness")
            val fairness=SyncV2ContentPriority.Fairness(saved?.getInt("demanded")?:0,saved?.getInt("nextOrdinary")?:1)
            val rank=fairness.choose(pending.map{it.rank}.toSet())?:break
            val item=pending.filter{it.rank==rank}.sortedWith(compareByDescending<Upload>{if(rank==0)it.demandAtMillis else it.addedAtMillis}.thenBy{it.entity}.thenBy{it.blockIndex}).first()
            val oid=item.oid
            try {
            val transfer=transfers[oid]?:run {
                val total=store.objectSize(oid)
                if(forceRenew.remove(oid))renewed[oid]=reserve(oid,total,true)
                if(oid !in exactCache) {
                    val batch=(listOf(oid)+pending.filter{it.oid !in transfers && it.oid !in exactCache}.map{it.oid}).distinct().take(64)
                    exactCache.putAll(exact(batch.map{it to store.objectSize(it)}))
                }
                var exact=exactCache.getValue(oid)
                var reservationId:String?=null
                if(exact.first!="present") {
                    reservationId=renewed.remove(oid)?:reserve(oid,total,false)
                    // C11: this exact read occurs AFTER reserve, including an idempotent replay.
                    // Never turn reserve.objects[].durableOffset into a transfer position.
                    exact=haveBatch(window,listOf(oid to total)).getValue(oid)
                }
                val offset=reconcilePrefix(oid,total,exact)
                if(exact.first=="present") {
                    if(store.load("upload-start/$oid")!=null) {
                        if(store.completeUpload(oid,exact.second!!,rank))advance(0,true)
                    } else store.save("receipt/$oid",exact.second!!)
                    done.add(oid);yieldControl()
                    null
                } else Transfer(total,requireNotNull(reservationId),offset).also{transfers[oid]=it}
            }
            if(transfer==null)continue
            if(transfer.offset<transfer.total) {
                val n=minOf(65536,transfer.total-transfer.offset)
                if(remaining()<n)return@window "budget_paused"
                val checkpoint=uploadCheckpoint(oid,transfer.total)
                val replay=minOf(n,(checkpoint.offset-transfer.offset).coerceAtLeast(0))
                if(replay>0)persistCheckpoint(oid,transfer.total,checkpoint.offset,replay)
                val bytes=store.objectPart(oid,transfer.offset,n)
                store.save("upload-start/$oid",JSONObject().put("offset",transfer.offset).put("bytes",n).put("partSha256",SyncV2Codec.hex(SyncV2Codec.sha(bytes))))
                val reply=network{client.put(oid,transfer.offset,transfer.reservation,id(),bytes)};store.counts.networkBytes+=bytes.size
                if(reply.get("oid")!=oid || SyncV2Wire.uint(reply,"durableOffset")!=(transfer.offset+n).toLong())SyncV2Codec.fail("offset_conflict")
                client.verifyReceipt(reply.getJSONObject("receipt"),oid,transfer.total.toLong(),(transfer.offset+n).toLong(),now())
                transfer.offset+=n
                persistCheckpoint(oid,transfer.total,transfer.offset);advance(n.toLong(),false);yieldControl()
            }
            if(transfer.offset==transfer.total) {
                gate();val completed=client.complete(oid,transfer.reservation,id(),transfer.total)
                if(completed.get("oid")!=oid)SyncV2Codec.fail("invalid_schema")
                client.verifyReceipt(completed.getJSONObject("receipt"),oid,transfer.total.toLong(),transfer.total.toLong(),now())
                if(store.completeUpload(oid,completed.getJSONObject("receipt"),rank))advance(0,true)
                done.add(oid)
            }
            }catch(e:SyncV2CloudError) {
                if(e.code!="reservation_expired" || (expiryRetries[oid]?:0)>=2)throw e
                expiryRetries[oid]=(expiryRetries[oid]?:0)+1
                transfers.remove(oid);exactCache.remove(oid);forceRenew.add(oid)
            }
        };"complete"
    }
    /** Downloads immutable objects into the v2 cache; original business files never enter this path. */
    fun download(oids:List<Pair<String,Int>>,budget:Budget=Budget()):Result = window(budget) { window,remaining,advance ->
        for((oid,total) in oids) {
            gate();SyncV2Wire.oid(oid)
            if(total !in 1..266264)SyncV2Codec.fail("object_too_large")
            store.recoverDownload(oid)
            if(store.load("download/$oid")!=null)continue
            var offset=store.prefix(oid,total)
            while(offset<total) {
                gate();pollDemands();val n=minOf(65536,total-offset)
                if(remaining()<n)return@window "budget_paused"
                val bytes=network{client.range(oid,window,offset,offset+n-1,total)};store.counts.networkBytes+=bytes.size
                store.append(oid,total,offset,bytes);offset+=n;advance(n.toLong(),false);yieldControl()
            }
            store.finish(oid,total);store.save("download/$oid",JSONObject().put("total",total));advance(0,true)
        };"complete"
    }
    fun authenticate(oid:String,key:(Long)->ByteArray):ByteArray {
        store.recoverDownload(oid)
        try {
        if(store.checked(oid))return store.plain(oid)
        gate();val bytes=store.objectBytes(oid)
        if(bytes.size<24)SyncV2Codec.fail("invalid_encoding")
        val length=java.nio.ByteBuffer.wrap(bytes,4,4).int
        if(length !in 1..4096 || length+24>bytes.size)SyncV2Codec.fail("invalid_encoding")
        val header=SyncV2Wire.parse(bytes.copyOfRange(8,8+length),4096,true)
        val version=SyncV2Wire.uint(header,"keyVersion",4294967295)
        val plain=SyncV2Envelope.open(identity,key(version),oid,bytes,version){stage,nanos->
            if(stage=="authentication")store.counts.authNanos+=nanos else store.counts.decompressNanos+=nanos
        };store.counts.authentications++
        if(header.getString("encoding")=="gzip")store.counts.decompressBytes+=plain.size
        store.savePlain(oid,plain);yieldControl();return plain
        }catch(e:IllegalStateException) {
            if(e.message in setOf("hash_mismatch","aead_invalid","invalid_encoding","fingerprint_mismatch"))store.quarantineDownload(oid,e.message!!)
            throw e
        }
    }
    private fun haveBatch(window:String,expected:List<Pair<String,Int>>):Map<String,Pair<String,JSONObject?>> {
        store.counts.metadataBatches++
        return network{client.verifiedHaveWant(window,expected,now())}.mapValues { (_,row) ->
            row.getString("status") to if(row.isNull("receipt"))null else row.getJSONObject("receipt")
        }
    }
    private data class UploadCheckpoint(val offset:Int,val replayedBytes:Int)
    private fun uploadCheckpoint(oid:String,total:Int):UploadCheckpoint {
        val value=store.load("upload/$oid")?:return UploadCheckpoint(0,0)
        val fields=value.keys().asSequence().toSet()
        if(!fields.containsAll(setOf("offset","total")) || (fields-setOf("offset","total","replayedBytes")).isNotEmpty() || SyncV2Wire.uint(value,"total")!=total.toLong())SyncV2Codec.fail("invalid_schema")
        val offset=SyncV2Wire.uint(value,"offset",total.toLong()).toInt();validateOffset(offset,total)
        return UploadCheckpoint(offset,if(value.has("replayedBytes"))SyncV2Wire.uint(value,"replayedBytes",65536).toInt()else 0)
    }
    private fun persistCheckpoint(oid:String,total:Int,offset:Int,replayedBytes:Int=0) {
        val old=uploadCheckpoint(oid,total);validateOffset(offset,total)
        val replay=old.replayedBytes+replayedBytes
        if(replayedBytes<0 || replay>65536)throw SyncV2CloudError("offset_conflict")
        val confirmed=maxOf(offset,old.offset)
        if(confirmed!=old.offset || replay!=old.replayedBytes)
            store.save("upload/$oid",JSONObject().put("offset",confirmed).put("total",total).put("replayedBytes",replay))
    }
    private fun reconcilePrefix(oid:String,total:Int,remote:Pair<String,JSONObject?>):Int {
        // haveBatch has authenticated the service receipt's scope/OID/length/prefix binding.
        val offset=remote.second?.getJSONObject("body")?.getInt("durableOffset")?:0
        validateOffset(offset,total)
        val checkpoint=uploadCheckpoint(oid,total)
        if(checkpoint.offset-offset>65536)throw SyncV2CloudError("offset_conflict")
        if(remote.first!="present" && offset>checkpoint.offset) {
            // An uncertain fine frame also has a local immutable-byte anchor. Old checkpoints
            // without this additive field still rely on the authenticated prefix receipt.
            val pending=store.load("upload-start/$oid")
            if(pending!=null) {
                SyncV2Codec.exact(pending,if(pending.has("partSha256"))setOf("offset","bytes","partSha256")else setOf("offset","bytes"))
                val at=SyncV2Wire.uint(pending,"offset",total.toLong()).toInt();val size=SyncV2Wire.uint(pending,"bytes",65536).toInt()
                if(at%65536!=0 || size==0 || size>total-at)SyncV2Codec.fail("invalid_schema")
                if(at+size<=offset && pending.has("partSha256")) {
                    val expected=SyncV2Wire.string(pending,"partSha256");SyncV2Codec.unhex(expected,32)
                    if(expected!=SyncV2Codec.hex(SyncV2Codec.sha(store.objectPart(oid,at,size))))SyncV2Codec.fail("hash_mismatch")
                }
            }
        }
        // Never lower the local confirmation high-water mark. Replay budget is persisted
        // before a PUT so another lost response cannot buy a second 64 KiB replay window.
        persistCheckpoint(oid,total,offset)
        return offset
    }
    private fun window(budget:Budget,work:(String,()->Long,(Long,Boolean)->Unit)->String):Result {
        if(!running.compareAndSet(false,true))return Result("library_busy",0,0,0)
        val start=clock();var bytes=0L;var complete=0
        fun finish(status:String,serverDelay:Long?=null):Result {
            if(status=="complete" || bytes>0 || complete>0) {
                runCatching{store.save("retry",JSONObject().put("attempt",0))}
                return Result(status,bytes,complete,clock()-start)
            }
            if(status !in setOf("transport_wait","budget_paused","temporarily_unavailable","cloud_account_busy","library_busy","offset_conflict","window_expired","reservation_expired","quota_exceeded","storage_pressure","rate_limited"))return Result(status,bytes,complete,clock()-start)
            val attempt=((runCatching{store.load("retry")?.optInt("attempt",0)}.getOrNull()?:0)+1).coerceAtMost(7)
            val base=serverDelay?:minOf(60000L,1000L shl (attempt-1))
            val delay=base+(base*java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.0,0.2)).toLong()
            runCatching{store.save("retry",JSONObject().put("attempt",attempt).put("retryAfterMillis",delay))}
            return Result(status,bytes,complete,clock()-start,delay)
        }
        try {
            val status=client.withinBudget(budget.millis-budget.reserveMillis) {
                gate();client.capabilities();gate();pollDemands(true);val negotiated=client.negotiate();val window=negotiated.getString("windowId")
                if(negotiated.getLong("expiresAtMillis")<=now())SyncV2Codec.fail("window_expired")
                work(window,{if(clock()-start>=budget.millis-budget.reserveMillis)0 else budget.bytes-bytes},{n,done->bytes+=n;if(done)complete++})
            }
            return finish(status)
        } catch(e:SyncV2CloudError) {
            return finish(e.code,e.retryAfterMillis)
        } catch(e:IllegalStateException) {
            if(e.message in setOf("feature_disabled","transport_wait","budget_paused","storage_pressure"))return finish(e.message!!)
            throw e
        } catch(_:IOException) {return finish(if(clock()-start>=budget.millis-budget.reserveMillis)"budget_paused" else "temporarily_unavailable")}
        finally {running.set(false)}
    }
    private fun JSONArray.singleObject(oid:String,total:Int):JSONObject {
        if(length()!=1)SyncV2Codec.fail("invalid_schema")
        val row=getJSONObject(0);SyncV2Codec.exact(row,setOf("oid","totalBytes","durableOffset"))
        if(row.get("oid")!=oid || SyncV2Wire.uint(row,"totalBytes")!=total.toLong())SyncV2Codec.fail("invalid_schema")
        SyncV2Wire.uint(row,"durableOffset",total.toLong()) // Schema only; never a resume authority.
        return row
    }
    private fun validateOffset(offset:Int,total:Int) {if(offset !in 0..total || offset!=total && offset%65536!=0)SyncV2Codec.fail("invalid_schema")}
    private fun id()=SyncV2Codec.hex(SyncV2Codec.random(16))
}
