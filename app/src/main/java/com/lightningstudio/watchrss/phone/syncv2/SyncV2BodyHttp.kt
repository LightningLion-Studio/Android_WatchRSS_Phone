package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Durable ciphertext checkpoints. No legacy business routes or business ACK API. */
internal class SyncV2BodyHttp(private val client:SyncV2CloudClient,private val check:()->Unit) {
    private val scope get()=client.scope
    private fun json(method:String,path:String,body:JSONObject?=null,query:Map<String,String> = emptyMap()):JSONObject {
        check();val raw=client.bodyBytes(method,path,body?.let{SyncV2Codec.utf8(SyncV2Codec.canonical(it))},query=query).first
        val v=SyncV2Wire.parse(raw,1048576)
        if(v.get("version")!=1 || v.get("purpose")!=SyncV2BodyCodec.PURPOSE || SyncV2Codec.scope(v.getJSONObject("scope"))!=SyncV2Codec.scope(scope))SyncV2Codec.fail("invalid_schema")
        return v
    }
    fun list(entity:String,revision:String,after:String=""):JSONObject {
        SyncV2Codec.unhex(entity,32);SyncV2Codec.unhex(revision,16);if(after.isNotEmpty())SyncV2Codec.unhex(after,16)
        val v=json("GET","carriers",query=mapOf("entityKey" to entity,"bodyRevision" to revision,"limit" to "20")+if(after.isEmpty())emptyMap()else mapOf("after" to after))
        SyncV2Codec.exact(v,setOf("version","purpose","scope","carriers","nextAfter"))
        val a=v.getJSONArray("carriers");if(a.length()>20)SyncV2Codec.fail("invalid_schema");var previous=after
        for(i in 0 until a.length()) {val c=a.getJSONObject(i);SyncV2Codec.exact(c,setOf("carrierId","descriptor"));val b=SyncV2BodyCodec.validate(c.getJSONObject("descriptor"),scope)
            val id=c.getString("carrierId");if(id<=previous || id!=b.getString("carrierId") || b.getString("entityKey")!=entity || b.getString("bodyRevision")!=revision)SyncV2Codec.fail("invalid_schema");previous=id}
        if(!v.isNull("nextAfter")&&(v.getString("nextAfter")!=previous || previous==after))SyncV2Codec.fail("invalid_schema")
        return v
    }
    fun upload(descriptor:JSONObject,directory:File) {
        val b=SyncV2BodyCodec.validate(descriptor,scope);val id=b.getString("carrierId")
        val expected=SyncV2BodyCodec.objects(b).associate{it.getString("sha256") to it.getInt("sizeBytes")}
        val r=json("POST","reserve",descriptor);SyncV2Codec.exact(r,setOf("version","purpose","scope","carrierId","state","expiresAtMillis","objects"))
        if(r.getString("carrierId")!=id || r.getString("state") !in setOf("reserved","ready"))SyncV2Codec.fail("invalid_schema")
        SyncV2Wire.uint(r,"expiresAtMillis");if(r.getString("state")=="ready")return
        val a=r.getJSONArray("objects");val seen=hashSetOf<String>()
        if(a.length()!=expected.size)SyncV2Codec.fail("closure_incomplete")
        for(i in 0 until a.length()) {val o=a.getJSONObject(i);SyncV2Codec.exact(o,setOf("sha256","sizeBytes","offset"));val hash=o.getString("sha256");val size=expected[hash]?:SyncV2Codec.fail("closure_incomplete")
            if(!seen.add(hash)||SyncV2Wire.uint(o,"sizeBytes")!=size.toLong())SyncV2Codec.fail("closure_incomplete")
            val raw=File(directory,hash).readBytes();if(raw.size!=size||SyncV2BodyCodec.hash(raw)!=hash)SyncV2Codec.fail("hash_mismatch")
            var at=SyncV2Wire.uint(o,"offset",size.toLong()).toInt()
            while(at<size) {check();val part=raw.copyOfRange(at,minOf(size,at+65536));val s=scope
                client.bodyBytes("PUT","carriers/$id/objects/$hash/parts/$at",part,mapOf("x-watchrss-sync-part-sha256" to SyncV2BodyCodec.hash(part),
                    "x-watchrss-sync-repo-id" to s.getString("repoId"),"x-watchrss-sync-library-epoch" to s.getString("libraryEpoch"),"x-watchrss-sync-content-generation" to s.getString("contentGeneration")))
                at+=part.size
            }
        }
        val done=json("POST","carriers/$id/complete",JSONObject().put("scope",scope));SyncV2Codec.exact(done,setOf("version","purpose","scope","carrierId","state"))
        if(done.getString("carrierId")!=id || done.getString("state")!="ready")SyncV2Codec.fail("closure_incomplete")
    }
    fun download(id:String,hash:String,size:Int,directory:File):ByteArray {
        SyncV2Codec.unhex(id,16);SyncV2Codec.unhex(hash,32);if(size !in 1..16777216)SyncV2Codec.fail("invalid_schema")
        directory.mkdirs();val target=File(directory,hash)
        if(target.isFile && target.length()==size.toLong())target.readBytes().let{if(SyncV2BodyCodec.hash(it)==hash)return it}
        val partial=File(directory,"$hash.part");if(partial.length()>size)atomic(partial,byteArrayOf())
        var at=partial.length().toInt()
        while(at<size) {check();val (bytes,headers)=client.bodyBytes("GET","carriers/$id/objects/$hash",query=mapOf("offset" to at.toString()),raw=true)
            fun header(k:String)=headers.entries.firstOrNull{it.key.equals(k,true)}?.value
            if(header("x-watchrss-body-total-bytes")!=size.toString() || header("x-watchrss-body-offset")!=at.toString() || bytes.size!=minOf(65536,size-at))SyncV2Codec.fail("invalid_range")
            FileOutputStream(partial,true).use{it.write(bytes);it.fd.sync()};at+=bytes.size
        }
        val raw=partial.readBytes();if(raw.size!=size || SyncV2BodyCodec.hash(raw)!=hash){atomic(partial,byteArrayOf());SyncV2Codec.fail("hash_mismatch")}
        atomic(target,raw);return raw
    }
    companion object {
        fun atomic(file:File,raw:ByteArray) {
            file.parentFile!!.mkdirs();val tmp=File.createTempFile(".body-",".tmp",file.parentFile)
            try{FileOutputStream(tmp).use{it.write(raw);it.fd.sync()};java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);java.nio.channels.FileChannel.open(file.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}}finally{tmp.delete()}
        }
    }
}
