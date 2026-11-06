package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Dedicated content directory. No business paths or account writer are accepted here.
 * Atomic encrypted descriptors are small; immutable envelopes and durable prefixes are per OID.
 * One content worker owns a store. Neither directory scans nor whole-cache reads are needed. */
internal class SyncV2ContentStore(private val root:File,private val scope:String,private val localKey:ByteArray,
    val counts:Counts=Counts(),private val fault:(String)->Unit={}) {
    data class Counts(var readBytes:Long=0,var writeBytes:Long=0,var reads:Long=0,var writes:Long=0,
        var checkpoints:Long=0,var sourceBytes:Long=0,var exports:Long=0,var seals:Long=0,
        var authentications:Long=0,var decompressBytes:Long=0,var networkBytes:Long=0,var hashBytes:Long=0) {
        var metadataBatches=0L;var metadataStats=0L
        var networkNanos=0L;var authNanos=0L;var decompressNanos=0L;var preparationNanos=0L;var checkpointNanos=0L;var fileNanos=0L
        fun json()=JSONObject().put("fileReadBytes",readBytes).put("fileWriteBytes",writeBytes).put("fileReads",reads).put("fileWrites",writes)
            .put("checkpoints",checkpoints).put("sourceReadBytes",sourceBytes).put("exports",exports).put("seals",seals)
            .put("authentications",authentications).put("decompressBytes",decompressBytes).put("networkBytes",networkBytes).put("hashBytes",hashBytes)
            .put("networkMillis",networkNanos/1000000).put("authenticationMillis",authNanos/1000000).put("decompressionMillis",decompressNanos/1000000)
            .put("preparationMillis",preparationNanos/1000000).put("checkpointMillis",checkpointNanos/1000000).put("fileIOMillis",fileNanos/1000000).put("metadataBatches",metadataBatches).put("metadataStats",metadataStats)
    }
    init {SyncV2Codec.scope(JSONObject(scope));require(localKey.size==32);if(!root.isDirectory && !root.mkdirs())SyncV2Codec.fail("storage_pressure")}
    private fun file(key:String,ext:String):File=File(root,SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8("$scope/$key")))+ext)
    private fun read(file:File,offset:Int=0,size:Int=file.length().toInt()):ByteArray {
        if(size !in 0..1048576 || offset<0 || offset.toLong()+size>file.length())SyncV2Codec.fail("invalid_schema")
        val at=System.nanoTime();val out=ByteArray(size);RandomAccessFile(file,"r").use{it.seek(offset.toLong());it.readFully(out)}
        counts.reads++;counts.readBytes+=size;counts.fileNanos+=System.nanoTime()-at;return out
    }
    private fun syncDirectory() {FileChannel.open(root.toPath(),StandardOpenOption.READ).use{it.force(true)}}
    private fun atomic(file:File,bytes:ByteArray) {
        if(bytes.size>1048576)SyncV2Codec.fail("object_too_large")
        val at=System.nanoTime();val pending=File(file.path+".next")
        FileOutputStream(pending).use{it.write(bytes);it.fd.sync()};counts.writes++;counts.writeBytes+=bytes.size
        if(!pending.renameTo(file))SyncV2Codec.fail("storage_pressure")
        syncDirectory();counts.fileNanos+=System.nanoTime()-at
    }
    fun load(key:String):JSONObject? {
        val f=file(key,".meta");if(!f.exists())return null
        return SyncV2Wire.parse(SyncV2Codec.utf8(SyncV2Codec.openLocal(localKey,scope,"content/$key",read(f))),1048576,true)
    }
    fun save(key:String,value:JSONObject) {
        val at=System.nanoTime();atomic(file(key,".meta"),SyncV2Codec.sealLocal(localKey,scope,"content/$key",value));counts.checkpoints++;counts.checkpointNanos+=System.nanoTime()-at;fault("checkpoint")
    }
    fun putObject(oid:String,bytes:ByteArray) {
        SyncV2Wire.oid(oid)
        if(bytes.size>266264 || oid!="${oid.substringBefore(':')}:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes))}")SyncV2Codec.fail("hash_mismatch")
        val f=file(oid,".object")
        if(f.exists())return // immutable, accepted only through this verified write path
        atomic(f,bytes);fault("object")
    }
    fun objectBytes(oid:String):ByteArray=read(file(oid,".object"))
    fun objectPart(oid:String,start:Int,length:Int):ByteArray {
        if(length !in 1..65536)SyncV2Codec.fail("invalid_schema")
        return read(file(oid,".object"),start,length)
    }
    fun objectSize(oid:String):Int {counts.metadataStats++;SyncV2Wire.oid(oid);val size=file(oid,".object").length();if(size !in 1..266264)SyncV2Codec.fail("object_missing");return size.toInt()}
    fun prefix(oid:String,total:Int):Int {
        recoverDownload(oid)
        val meta=load("prefix/$oid")?:return 0
        if(meta.getInt("total")!=total)SyncV2Codec.fail("invalid_schema")
        val offset=meta.getInt("offset");val file=file(oid,".part")
        if(offset !in 0..total || offset!=total && offset%65536!=0 || file.length()<offset)SyncV2Codec.fail("hash_mismatch")
        return offset
    }
    fun append(oid:String,total:Int,offset:Int,bytes:ByteArray) {
        SyncV2Wire.oid(oid)
        if(total !in 1..266264 || offset!=prefix(oid,total) || offset%65536!=0 || bytes.size!=minOf(65536,total-offset))SyncV2Codec.fail("offset_conflict")
        val f=file(oid,".part")
        RandomAccessFile(f,"rw").use{it.setLength(offset.toLong());it.seek(offset.toLong());it.write(bytes);it.fd.sync()}
        counts.writes++;counts.writeBytes+=bytes.size;fault("frame")
        save("prefix/$oid",JSONObject().put("total",total).put("offset",offset+bytes.size))
    }
    fun finish(oid:String,total:Int) {
        if(prefix(oid,total)!=total)SyncV2Codec.fail("closure_incomplete")
        try {putObject(oid,read(file(oid,".part")))}catch(e:IllegalStateException) {
            if(e.message=="hash_mismatch")quarantineDownload(oid,e.message!!,QuarantineTarget.PART)
            throw e
        }
    }
    enum class QuarantineTarget { PART, OBJECT, CHECKED }
    /** Failure of a new prefix says nothing about an existing authenticated copy. Persist
     * the failed representation before invalidation; recovery must not widen that scope. */
    fun quarantineDownload(oid:String,reason:String,target:QuarantineTarget=QuarantineTarget.PART) {
        SyncV2Wire.oid(oid)
        save("quarantine/$oid",JSONObject().put("pending",true).put("reason",reason).put("target",target.name))
        fault("quarantine");recoverDownload(oid)
    }
    fun recoverDownload(oid:String) {
        val intent=load("quarantine/$oid")?:return
        if(!intent.getBoolean("pending"))return
        // Old unscoped intents prove only that the in-flight prefix failed. They cannot
        // authorize moving a previously authenticated object or plaintext out of its path.
        val target=QuarantineTarget.entries.singleOrNull{it.name==intent.optString("target","PART")}?:SyncV2Codec.fail("invalid_schema")
        val extensions=when(target) {
            QuarantineTarget.PART->listOf(".part")
            QuarantineTarget.OBJECT->listOf(".part",".object")
            QuarantineTarget.CHECKED->listOf(".checked")
        }
        val keys=when(target) {
            QuarantineTarget.PART,QuarantineTarget.OBJECT->listOf("prefix/$oid","download/$oid")
            QuarantineTarget.CHECKED->listOf("checked/$oid")
        }
        for(ext in extensions) {
            val source=file(oid,ext)
            if(source.exists() && !source.renameTo(file(oid,"$ext.bad")))SyncV2Codec.fail("storage_pressure")
        }
        for(key in keys) {
            val metadata=file(key,".meta")
            if(metadata.exists() && !metadata.delete())SyncV2Codec.fail("storage_pressure")
        }
        syncDirectory();save("quarantine/$oid",intent.put("pending",false))
    }
    fun savePlain(oid:String,plain:ByteArray) {
        val nonce=SyncV2Codec.random(12)
        atomic(file(oid,".checked"),nonce+SyncV2Codec.encrypt(localKey,nonce,SyncV2Codec.utf8("$scope/$oid"),plain));fault("authenticated")
        save("checked/$oid",JSONObject().put("bytes",plain.size))
    }
    fun checked(oid:String):Boolean {
        if(load("checked/$oid")!=null)return true
        if(!file(oid,".checked").exists())return false
        // The checked file is atomically published only after successful remote authentication.
        val bytes=plain(oid);save("checked/$oid",JSONObject().put("bytes",bytes.size));return true
    }
    fun plain(oid:String):ByteArray {
        val bytes=read(file(oid,".checked"));return SyncV2Codec.decrypt(localKey,bytes.copyOfRange(0,12),SyncV2Codec.utf8("$scope/$oid"),bytes.copyOfRange(12,bytes.size))
    }
    /** One recoverable commit records both successful-blob fairness and its exact receipt.
     * A crash cannot count a blob twice or drop the eighth completion on reconnect. */
    fun recoverUploadCompletion() {
        val intent=load("completion-intent")?:return
        if(intent.optBoolean("applied",false))return
        val current=load("fairness")?:JSONObject().put("revision","0").put("demanded",0).put("nextOrdinary",1)
        val before=intent.getString("beforeRevision");val after=intent.getJSONObject("fairness")
        when(current.optString("revision","0")) {
            before->save("fairness",after)
            after.getString("revision")->Unit
            else->SyncV2Codec.fail("replay_detected")
        }
        val oid=intent.getString("oid")
        if(intent.optString("kind","upload")=="download")save("download-fairness/$oid",JSONObject().put("complete",true))
        else {save("receipt/$oid",intent.getJSONObject("receipt"));save("upload-complete/$oid",JSONObject().put("complete",true))}
        save("completion-intent",JSONObject().put("applied",true))
    }
    fun completeUpload(oid:String,receipt:JSONObject,rank:Int):Boolean {
        recoverUploadCompletion()
        if(load("upload-complete/$oid")!=null)return false
        val state=load("fairness")?:JSONObject().put("revision","0").put("demanded",0).put("nextOrdinary",1)
        val revision=state.optString("revision","0")
        val fairness=SyncV2ContentPriority.Fairness(state.getInt("demanded"),state.getInt("nextOrdinary"))
        if(oid.startsWith("blob:"))fairness.completed(rank)
        val after=JSONObject().put("revision",SyncV2Codec.next(revision)).put("demanded",fairness.demanded).put("nextOrdinary",fairness.nextOrdinary)
        save("completion-intent",JSONObject().put("beforeRevision",revision).put("fairness",after).put("oid",oid).put("receipt",receipt))
        recoverUploadCompletion();return true
    }
    fun completeDownload(oid:String,rank:Int):Boolean {
        if(!oid.startsWith("blob:"))return false
        recoverUploadCompletion()
        if(load("download-fairness/$oid")!=null)return false
        val state=load("fairness")?:JSONObject().put("revision","0").put("demanded",0).put("nextOrdinary",1)
        val fairness=SyncV2ContentPriority.Fairness(state.getInt("demanded"),state.getInt("nextOrdinary"));fairness.completed(rank)
        val after=JSONObject().put("revision",SyncV2Codec.next(state.getString("revision"))).put("demanded",fairness.demanded).put("nextOrdinary",fairness.nextOrdinary)
        save("completion-intent",JSONObject().put("kind","download").put("oid",oid).put("beforeRevision",state.getString("revision")).put("fairness",after))
        recoverUploadCompletion();return true
    }
    fun beginDownloadAcceptance(recipe:String,oid:String,index:Int,rank:Int) {
        save("download-accept-intent",JSONObject().put("recipe",recipe).put("oid",oid).put("index",index).put("rank",rank).put("applied",false))
    }
    fun recoverDownloadAcceptance() {
        val intent=load("download-accept-intent")?:return
        if(intent.getBoolean("applied"))return
        val accepted=load("receive/${intent.getString("recipe")}")?:return
        if(accepted.getInt("index")<=intent.getInt("index"))return
        completeDownload(intent.getString("oid"),intent.getInt("rank"))
        save("download-accept-intent",intent.put("applied",true))
    }
    /** Bounded metadata batch. Native Kotlin has no cross-bridge calls to claim as savings. */
    fun metadata(keys:List<String>):List<JSONObject?> {
        if(keys.size>64 || keys.sumOf{SyncV2Codec.utf8(it).size}>65536)SyncV2Codec.fail("object_too_large")
        val result=keys.map(::load)
        if(result.sumOf{if(it==null)0 else SyncV2Codec.utf8(SyncV2Codec.canonical(it)).size}>65536)SyncV2Codec.fail("object_too_large")
        return result
    }
}
