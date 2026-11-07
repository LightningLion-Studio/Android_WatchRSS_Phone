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
    private data class DurablePrefix(val total:Int,val offset:Int)
    // One store has one writer. Cache only offsets already made durable, never network data or
    // plaintext. Eviction/reopen revalidates disk records. Every payload read still checks AEAD/SHA.
    private val durablePrefixes=object:LinkedHashMap<String,DurablePrefix>(128,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,DurablePrefix>?)=size>128
    }
    private val names=object:LinkedHashMap<String,String>(128,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>?)=size>128
    }
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
    private fun file(key:String,ext:String):File=File(root,(names[key]?:SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8("$scope/$key"),"sha256.filename")).also{names[key]=it})+ext)
    private fun read(file:File,offset:Int=0,size:Int=file.length().toInt()):ByteArray {
        if(size !in 0..1048576 || offset<0 || offset.toLong()+size>file.length())SyncV2Codec.fail("invalid_schema")
        val at=System.nanoTime();val out=ByteArray(size);SyncV2LocalProfile.time("file.read",size.toLong()){RandomAccessFile(file,"r").use{it.seek(offset.toLong());it.readFully(out)}}
        counts.reads++;counts.readBytes+=size;counts.fileNanos+=System.nanoTime()-at;return out
    }
    private fun syncDirectory() {SyncV2LocalProfile.time("fsync.directory"){FileChannel.open(root.toPath(),StandardOpenOption.READ).use{it.force(true)}}}
    private fun atomic(file:File,bytes:ByteArray) {
        if(bytes.size>1048576)SyncV2Codec.fail("object_too_large")
        val at=System.nanoTime();val pending=File(file.path+".next")
        FileOutputStream(pending).use{out->SyncV2LocalProfile.time("file.write",bytes.size.toLong()){out.write(bytes)};SyncV2LocalProfile.time("fsync.file"){out.fd.sync()}};counts.writes++;counts.writeBytes+=bytes.size
        if(!SyncV2LocalProfile.time("file.rename"){pending.renameTo(file)})SyncV2Codec.fail("storage_pressure")
        syncDirectory();counts.fileNanos+=System.nanoTime()-at
    }
    fun load(key:String):JSONObject? {
        val f=file(key,".meta");if(!f.exists())return null
        return SyncV2Wire.parse(SyncV2Codec.utf8(SyncV2Codec.openLocal(localKey,scope,"content/$key",read(f))),1048576,true)
    }
    fun save(key:String,value:JSONObject) {
        if(key.startsWith("prefix/") || key.startsWith("quarantine/"))durablePrefixes.remove(key.substringAfter('/'))
        val at=System.nanoTime();atomic(file(key,".meta"),SyncV2Codec.sealLocal(localKey,scope,"content/$key",value));counts.checkpoints++;counts.checkpointNanos+=System.nanoTime()-at;fault("checkpoint")
    }
    fun putObject(oid:String,bytes:ByteArray,verifyCommit:((JSONObject)->Unit)?=null) {
        SyncV2Wire.oid(oid)
        if(bytes.size>266264)SyncV2Codec.fail("object_too_large")
        if(oid.startsWith("commit:")) {
            val value=SyncV2Wire.parse(bytes,262144,true)
            SyncV2Codec.exact(value,setOf("body","signature"))
            SyncV2HeadPins.validateBody(value.getJSONObject("body"))
            if(SyncV2Codec.oid("commit",value.getJSONObject("body"))!=oid)SyncV2Codec.fail("hash_mismatch")
            (verifyCommit?:SyncV2Codec.fail("authentication_required"))(value)
        }else if(oid!="${oid.substringBefore(':')}:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(bytes,"sha256.object"))}")SyncV2Codec.fail("hash_mismatch")
        val f=file(oid,".object")
        if(f.exists())return // immutable, accepted only through this verified write path
        atomic(f,bytes);fault("object")
    }
    fun objectBytes(oid:String):ByteArray {val f=file(oid,".object");if(!f.exists())SyncV2Codec.fail("object_missing");return read(f)}
    fun objectPart(oid:String,start:Int,length:Int):ByteArray {
        if(length !in 1..65536)SyncV2Codec.fail("invalid_schema")
        return read(file(oid,".object"),start,length)
    }
    fun partialBytes(oid:String,start:Int,length:Int):ByteArray {
        SyncV2Wire.oid(oid)
        if(length !in 1..65536)SyncV2Codec.fail("invalid_schema")
        localTotal(oid)?.let{total->val bytes=prefixBytes(oid,total);if(start<0 || start+length>bytes.size)SyncV2Codec.fail("invalid_range");return bytes.copyOfRange(start,start+length)}
        return read(file(oid,".part"),start,length)
    }
    fun objectSize(oid:String):Int {counts.metadataStats++;SyncV2Wire.oid(oid);val size=file(oid,".object").length();if(size !in 1..266264)SyncV2Codec.fail("object_missing");return size.toInt()}
    fun prefix(oid:String,total:Int):Int {
        recoverDownload(oid)
        durablePrefixes[oid]?.let{if(it.total!=total)SyncV2Codec.fail("invalid_schema");return it.offset}
        var offset=oldPrefix(oid,total)
        while(offset<total) {
            val part=localPart(oid,offset)?:break
            if(part.first.getInt("total")!=total)SyncV2Codec.fail("part_conflict")
            offset+=part.second.size
        }
        if(offset>0)durablePrefixes[oid]=DurablePrefix(total,offset)
        return offset
    }
    private fun oldPrefix(oid:String,total:Int):Int {
        val meta=load("prefix/$oid")?:return 0
        if(meta.getInt("total")!=total)SyncV2Codec.fail("invalid_schema")
        val offset=meta.getInt("offset");val file=file(oid,".part")
        if(offset !in 0..total || offset!=total && offset%65536!=0 || file.length()<offset)SyncV2Codec.fail("hash_mismatch")
        return offset
    }
    /** Local fine frames co-locate bytes and the authenticated receive checkpoint. One atomic
     * fsync publishes both. At most five records exist for a bounded wire object; no directory
     * scan, plaintext body, JSON/base64 payload, or independent receipt write is necessary. */
    private fun localPart(oid:String,offset:Int):Pair<JSONObject,ByteArray>? {
        val current=file(oid,".local2-$offset");val legacy=file(oid,".local-$offset")
        val header:JSONObject;val payload:ByteArray
        if(current.exists()) {
            if(legacy.exists())SyncV2Codec.fail("part_conflict")
            val raw=read(current);if(raw.size<32)SyncV2Codec.fail("invalid_encoding")
            val length=java.nio.ByteBuffer.wrap(raw,0,4).int
            if(length !in 30..4124 || 4+length>raw.size)SyncV2Codec.fail("invalid_encoding")
            val metadata=SyncV2Codec.decrypt(localKey,SyncV2LocalProfile.copy(raw,4,16),SyncV2Codec.utf8("$scope/local-part-v2/$oid/$offset"),SyncV2LocalProfile.copy(raw,16,4+length))
            header=SyncV2Wire.parse(metadata,4096,true);payload=SyncV2LocalProfile.copy(raw,4+length,raw.size)
        }else {
            if(!legacy.exists())return null
            // P4 v1 records remain readable. Never rewrite a retained custody copy to migrate it.
            val encrypted=read(legacy);if(encrypted.size<28)SyncV2Codec.fail("invalid_encoding")
            val raw=SyncV2Codec.decrypt(localKey,SyncV2LocalProfile.copy(encrypted,0,12),SyncV2Codec.utf8("$scope/local-part/$oid/$offset"),SyncV2LocalProfile.copy(encrypted,12,encrypted.size))
            if(raw.size<4)SyncV2Codec.fail("invalid_encoding")
            val length=java.nio.ByteBuffer.wrap(raw,0,4).int
            if(length !in 2..4096 || 4+length>raw.size)SyncV2Codec.fail("invalid_encoding")
            header=SyncV2Wire.parse(SyncV2LocalProfile.copy(raw,4,4+length),4096,true);payload=SyncV2LocalProfile.copy(raw,4+length,raw.size)
        }
        val total=SyncV2Wire.uint(header,"total",266264).toInt()
        if(offset<0 || offset>=total || payload.size!=minOf(65536,total-offset) || header.get("offset")!=offset || header.get("hash")!=SyncV2Codec.hex(SyncV2Codec.sha(payload,"sha256.record")))SyncV2Codec.fail("hash_mismatch")
        return header to payload
    }
    fun localTotal(oid:String):Int? {
        SyncV2Wire.oid(oid)
        recoverDownload(oid)
        durablePrefixes[oid]?.let{return it.total}
        for(offset in 0..262144 step 65536)localPart(oid,offset)?.let{return it.first.getInt("total")}
        return load("local-upload/$oid")?.getInt("total")
    }
    private fun prefixBytes(oid:String,total:Int):ByteArray {
        val base=oldPrefix(oid,total);val out=java.io.ByteArrayOutputStream()
        if(base>0)out.write(read(file(oid,".part"),0,base))
        var offset=base
        while(offset<total) {val part=localPart(oid,offset)?:break;if(part.first.getInt("total")!=total)SyncV2Codec.fail("part_conflict");SyncV2LocalProfile.count("payload.stream.copy",part.second.size.toLong());out.write(part.second);offset+=part.second.size}
        SyncV2LocalProfile.count("payload.stream.copy",out.size().toLong());return out.toByteArray()
    }
    fun appendLocal(oid:String,total:Int,offset:Int,bytes:ByteArray,receipt:JSONObject) {
        SyncV2Wire.oid(oid)
        if(total !in 1..266264 || offset!=prefix(oid,total) || offset%65536!=0 || bytes.size!=minOf(65536,total-offset))SyncV2Codec.fail("offset_conflict")
        val header=SyncV2LocalProtocol.bytes(JSONObject().put("total",total).put("offset",offset).put("hash",SyncV2Codec.hex(SyncV2Codec.sha(bytes,"sha256.record"))).put("receipt",receipt))
        // Payload already is the exact end-to-end object ciphertext (as in .object files).
        // Authenticate its SHA/offset/total/receipt in a small encrypted metadata envelope;
        // retaining raw ciphertext avoids encrypting/decrypting the whole payload a second time.
        val nonce=SyncV2Codec.random(12)
        val metadata=SyncV2Codec.encrypt(localKey,nonce,SyncV2Codec.utf8("$scope/local-part-v2/$oid/$offset"),header)
        val raw=java.nio.ByteBuffer.allocate(4+nonce.size+metadata.size+bytes.size).putInt(nonce.size+metadata.size).put(nonce).put(metadata).put(bytes).array()
        SyncV2LocalProfile.count("payload.copy",bytes.size.toLong())
        SyncV2LocalProfile.count("record.metadata.copy",nonce.size.toLong());SyncV2LocalProfile.count("record.metadata.copy",metadata.size.toLong())
        fault("frame")
        durablePrefixes.remove(oid)
        atomic(file(oid,".local2-$offset"),raw)
        durablePrefixes[oid]=DurablePrefix(total,offset+bytes.size)
        fault("checkpoint")
    }
    fun append(oid:String,total:Int,offset:Int,bytes:ByteArray) {
        SyncV2Wire.oid(oid)
        if(total !in 1..266264 || offset!=prefix(oid,total) || offset%65536!=0 || bytes.size!=minOf(65536,total-offset))SyncV2Codec.fail("offset_conflict")
        durablePrefixes.remove(oid)
        // One materialization at a local -> cloud handoff retains the exact sealed prefix.
        if(oldPrefix(oid,total)<offset) {
            val prefix=prefixBytes(oid,total)
            atomic(file(oid,".part"),prefix)
            save("prefix/$oid",JSONObject().put("total",total).put("offset",prefix.size))
        }
        val f=file(oid,".part")
        RandomAccessFile(f,"rw").use{it.setLength(offset.toLong());it.seek(offset.toLong());it.write(bytes);it.fd.sync()}
        counts.writes++;counts.writeBytes+=bytes.size;fault("frame")
        save("prefix/$oid",JSONObject().put("total",total).put("offset",offset+bytes.size))
        durablePrefixes[oid]=DurablePrefix(total,offset+bytes.size)
    }
    fun finish(oid:String,total:Int,verifyCommit:((JSONObject)->Unit)?=null) {
        if(prefix(oid,total)!=total)SyncV2Codec.fail("closure_incomplete")
        try {putObject(oid,prefixBytes(oid,total),verifyCommit)}catch(e:IllegalStateException) {
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
        durablePrefixes.remove(oid)
        // Old unscoped intents prove only that the in-flight prefix failed. They cannot
        // authorize moving a previously authenticated object or plaintext out of its path.
        val target=QuarantineTarget.entries.singleOrNull{it.name==intent.optString("target","PART")}?:SyncV2Codec.fail("invalid_schema")
        val extensions=when(target) {
            QuarantineTarget.PART->listOf(".part")
            QuarantineTarget.OBJECT->listOf(".part",".object")
            QuarantineTarget.CHECKED->listOf(".checked")
        }
        val keys=when(target) {
            QuarantineTarget.PART,QuarantineTarget.OBJECT->listOf("prefix/$oid","download/$oid","local-upload/$oid")
            QuarantineTarget.CHECKED->listOf("checked/$oid")
        }
        for(ext in extensions+if(target!=QuarantineTarget.CHECKED)(0..262144 step 65536).flatMap{listOf(".local-$it",".local2-$it")}else emptyList()) {
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
