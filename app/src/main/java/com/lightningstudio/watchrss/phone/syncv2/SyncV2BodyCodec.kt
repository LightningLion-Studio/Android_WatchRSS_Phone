package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

/** Independent P6 byte carrier. No v2 object, ref, sequence or business ACK is created here. */
internal object SyncV2BodyCodec {
    const val PURPOSE="p6-body.v1"
    const val DOMAIN="wrss.cloud.body.v1/carrier-sign"
    data class Binding(val kind:String,val logicalId:String,val entityKey:String,val bodyRevision:String)
    data class Prepared(val descriptor:JSONObject,val objects:Map<String,ByteArray>)
    fun hash(raw:ByteArray)=SyncV2Codec.hex(SyncV2Codec.sha(raw))
    fun revision(kind:String,entity:String,hash:String):String {
        if(kind !in setOf("asset","book"))SyncV2Codec.fail("source_identity_missing")
        SyncV2Codec.unhex(entity,32);SyncV2Codec.unhex(hash,32)
        return SyncV2Codec.hash(JSONArray().put("wrss.cloud.body.v1/$kind").put(entity).put(hash)).take(32)
    }
    fun name(kind:String,id:String):String=when(kind) {
        "asset"->{if(!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}").matches(id))SyncV2Codec.fail("source_identity_missing");"notes/assets/$id"}
        "book"->{SyncV2Codec.unhex(id,32);"books/$id.jsonl"}
        else->SyncV2Codec.fail("source_identity_missing")
    }
    fun uuid(id:String):String {SyncV2Codec.unhex(id,16);return "${id.take(8)}-${id.substring(8,12)}-${id.substring(12,16)}-${id.substring(16,20)}-${id.substring(20)}"}
    fun validate(d:JSONObject,scope:JSONObject):JSONObject {
        SyncV2Codec.exact(d,setOf("body","signature"));val b=d.getJSONObject("body")
        SyncV2Codec.exact(b,setOf("version","purpose","scope","carrierId","authorDeviceId","entityKey","bodyRevision","keyVersion","anchor","sourceSnapshotId","manifest","chunks"))
        if(b.get("version")!=1 || b.get("purpose")!=PURPOSE || SyncV2Codec.utf8(SyncV2Codec.canonical(d)).size>262144)SyncV2Codec.fail("invalid_schema")
        if(SyncV2Codec.scope(b.getJSONObject("scope"))!=SyncV2Codec.scope(scope))SyncV2Codec.fail("scope_mismatch")
        for(k in listOf("carrierId","authorDeviceId","bodyRevision"))SyncV2Codec.unhex(SyncV2Wire.string(b,k),16)
        SyncV2Codec.unhex(SyncV2Wire.string(b,"entityKey"),32);SyncV2Codec.decode64(d.getString("signature"),64)
        if(SyncV2Wire.uint(b,"keyVersion",4294967295)==0L)SyncV2Codec.fail("invalid_schema")
        val a=b.getJSONObject("anchor");SyncV2Codec.exact(a,setOf("commitOid","revision","rollbackGeneration"));SyncV2Codec.commitOid(a.getString("commitOid"))
        if(SyncV2Codec.d64(a.getString("revision")).signum()==0)SyncV2Codec.fail("invalid_schema");SyncV2Codec.d64(a.getString("rollbackGeneration"))
        if(!b.isNull("sourceSnapshotId")&&!Regex("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}").matches(b.getString("sourceSnapshotId")))SyncV2Codec.fail("invalid_schema")
        val chunks=b.getJSONArray("chunks");if(chunks.length()>512)SyncV2Codec.fail("object_too_large")
        var total=0L;val hashes=hashSetOf<String>()
        for((i,o)in objects(b).withIndex()) {
            SyncV2Codec.exact(o,setOf("sha256","sizeBytes"));SyncV2Codec.unhex(o.getString("sha256"),32)
            val size=SyncV2Wire.uint(o,"sizeBytes",if(i==0)1048576 else 16777216)
            if(size==0L || !hashes.add(o.getString("sha256")))SyncV2Codec.fail("invalid_schema");total+=size
        }
        if(total>67108864)SyncV2Codec.fail("object_too_large")
        return b
    }
    fun objects(b:JSONObject):List<JSONObject> = listOf(b.getJSONObject("manifest"))+b.getJSONArray("chunks").let{a->(0 until a.length()).map{a.getJSONObject(it)}}
    private fun seal(key:ByteArray,info:String,aad:String,magic:String,raw:ByteArray):ByteArray {
        val salt=SyncV2Codec.random(16);val nonce=SyncV2Codec.random(12);val derived=SyncV2Codec.hkdf(key,salt,SyncV2Codec.utf8(info))
        return try {SyncV2Codec.utf8(magic)+salt+nonce+SyncV2Codec.encrypt(derived,nonce,SyncV2Codec.utf8(aad),raw)}finally{derived.fill(0)}
    }
    private fun unseal(key:ByteArray,info:String,aad:String,magic:String,raw:ByteArray):ByteArray {
        if(raw.size<51 || !raw.copyOfRange(0,7).contentEquals(SyncV2Codec.utf8(magic)))SyncV2Codec.fail("invalid_schema")
        val derived=SyncV2Codec.hkdf(key,raw.copyOfRange(7,23),SyncV2Codec.utf8(info))
        return try{SyncV2Codec.decrypt(derived,raw.copyOfRange(23,35),SyncV2Codec.utf8(aad),raw.copyOfRange(35,raw.size))}finally{derived.fill(0)}
    }
    fun prepare(scope:JSONObject,anchor:JSONObject,binding:Binding,key:ByteArray,version:Long,signer:SyncV2Signing,raw:ByteArray):Prepared {
        if(key.size!=32)SyncV2Codec.fail("key_unavailable")
        if(raw.size>limit(binding.kind))SyncV2Codec.fail("object_too_large")
        val rawHash=hash(raw)
        if(revision(binding.kind,binding.entityKey,rawHash)!=binding.bodyRevision)SyncV2Codec.fail("body_revision_changed")
        val id=SyncV2Codec.hex(SyncV2Codec.random(16));val uuid=uuid(id);val objects=linkedMapOf<String,ByteArray>();val chunks=JSONArray()
        for(at in 0 until maxOf(1,raw.size) step 262144) {
            val part=raw.copyOfRange(at,minOf(raw.size,at+262144));val plainHash=hash(part)
            val encrypted=seal(key,"watchrss/cloud/chunk/v1","watchrss-cloud-chunk-v1:$plainHash","WRSSCC2",part)
            val sha=hash(encrypted);objects[sha]=encrypted
            chunks.put(JSONObject().put("plaintextSha256",plainHash).put("ciphertextSha256",sha).put("plaintextBytes",part.size).put("ciphertextBytes",encrypted.size)
                .put("saltBase64",Base64.getEncoder().encodeToString(encrypted.copyOfRange(7,23))).put("nonceBase64",Base64.getEncoder().encodeToString(encrypted.copyOfRange(23,35))))
        }
        val objectDescriptor=JSONObject().put("name",name(binding.kind,binding.logicalId)).put("encoding","identity").put("originalBytes",raw.size).put("encodedBytes",raw.size).put("chunks",chunks)
        val m=JSONObject().put("version",1).put("purpose",PURPOSE).put("scope",scope).put("entityKey",binding.entityKey).put("bodyRevision",binding.bodyRevision)
            .put("kind",binding.kind).put("logicalId",binding.logicalId).put("plaintextSha256",rawHash).put("objects",JSONArray().put(objectDescriptor))
        val manifest=seal(key,"watchrss/cloud/manifest/v2/$uuid","watchrss-cloud-manifest-v2:$uuid","WRSSCM3",SyncV2Codec.utf8(SyncV2Codec.canonical(m)))
        val b=JSONObject().put("version",1).put("purpose",PURPOSE).put("scope",scope).put("carrierId",id).put("authorDeviceId",signer.deviceId)
            .put("entityKey",binding.entityKey).put("bodyRevision",binding.bodyRevision).put("keyVersion",version).put("anchor",anchor).put("sourceSnapshotId",JSONObject.NULL)
            .put("manifest",JSONObject().put("sha256",hash(manifest)).put("sizeBytes",manifest.size))
            .put("chunks",JSONArray(objects.toSortedMap().map{(h,bytes)->JSONObject().put("sha256",h).put("sizeBytes",bytes.size)}))
        objects[hash(manifest)]=manifest
        val descriptor=signer.signed(DOMAIN,b);validate(descriptor,scope)
        return Prepared(descriptor,objects)
    }
    fun limit(kind:String)=if(kind=="asset")5*1024*1024 else 64*1024*1024
    fun verifyDescriptor(d:JSONObject,scope:JSONObject,binding:Binding,publicKey:String):JSONObject {
        val b=validate(d,scope)
        if(b.getString("entityKey")!=binding.entityKey || b.getString("bodyRevision")!=binding.bodyRevision)SyncV2Codec.fail("body_revision_changed")
        if(!SyncV2Identity.verifySignature(publicKey,SyncV2Codec.lp(SyncV2Codec.utf8(DOMAIN),SyncV2Codec.utf8(SyncV2Codec.canonical(b))),d.getString("signature")))SyncV2Codec.fail("signature_invalid")
        return b
    }
    fun open(d:JSONObject,scope:JSONObject,binding:Binding,publicKey:String,key:(Long)->ByteArray,read:(String,Int)->ByteArray):ByteArray {
        val b=verifyDescriptor(d,scope,binding,publicKey)
        fun checked(h:String,n:Int)=read(h,n).also{if(it.size!=n || hash(it)!=h)SyncV2Codec.fail("hash_mismatch")}
        val root=key(b.getLong("keyVersion")).copyOf();if(root.size!=32)SyncV2Codec.fail("key_unavailable")
        try {
            val ref=b.getJSONObject("manifest");val encrypted=checked(ref.getString("sha256"),ref.getInt("sizeBytes"))
            if(!b.isNull("sourceSnapshotId"))return openLegacy(b,binding,root,encrypted,::checked)
            val uuid=uuid(b.getString("carrierId"));val plain=unseal(root,"watchrss/cloud/manifest/v2/$uuid","watchrss-cloud-manifest-v2:$uuid","WRSSCM3",encrypted)
            val m=try{SyncV2Wire.parse(plain,1048576,true)}finally{plain.fill(0)}
            SyncV2Codec.exact(m,setOf("version","purpose","scope","entityKey","bodyRevision","kind","logicalId","plaintextSha256","objects"))
            if(m.get("version")!=1 || m.get("purpose")!=PURPOSE || SyncV2Codec.scope(m.getJSONObject("scope"))!=SyncV2Codec.scope(scope) ||
                m.get("entityKey")!=binding.entityKey || m.get("bodyRevision")!=binding.bodyRevision || m.get("kind")!=binding.kind || m.get("logicalId")!=binding.logicalId)SyncV2Codec.fail("source_identity_missing")
            val list=m.getJSONArray("objects");if(list.length()!=1)SyncV2Codec.fail("source_identity_missing")
            val o=list.getJSONObject(0)
            if(o.getString("name")!=name(binding.kind,binding.logicalId))SyncV2Codec.fail("source_identity_missing")
            val raw=decodeObject(o,b,root,limit(binding.kind),::checked)
            val h=hash(raw)
            if(h!=m.getString("plaintextSha256") || revision(binding.kind,binding.entityKey,h)!=binding.bodyRevision)SyncV2Codec.fail("hash_mismatch")
            return raw
        } finally {root.fill(0)}
    }
    private fun decodeObject(o:JSONObject,b:JSONObject,key:ByteArray,max:Int,read:(String,Int)->ByteArray):ByteArray {
        SyncV2Codec.exact(o,setOf("name","encoding","originalBytes","encodedBytes","chunks")+if(o.has("contentSha256"))setOf("contentSha256")else emptySet())
        val original=SyncV2Wire.uint(o,"originalBytes",max.toLong()).toInt();val encoded=SyncV2Wire.uint(o,"encodedBytes",67108864)
        val chunks=o.getJSONArray("chunks");if(chunks.length()>512)SyncV2Codec.fail("object_too_large")
        val closure=linkedMapOf<String,Int>()
        for(i in 0 until chunks.length()) {val c=chunks.getJSONObject(i)
            SyncV2Codec.exact(c,setOf("plaintextSha256","ciphertextSha256","plaintextBytes","ciphertextBytes","saltBase64","nonceBase64"))
            SyncV2Codec.unhex(c.getString("plaintextSha256"),32);SyncV2Codec.unhex(c.getString("ciphertextSha256"),32)
            SyncV2Wire.uint(c,"plaintextBytes",16777216);val n=SyncV2Wire.uint(c,"ciphertextBytes",16777216).toInt()
            val previous=closure.put(c.getString("ciphertextSha256"),n);if(previous!=null && previous!=n)SyncV2Codec.fail("closure_incomplete")
        }
        val declared=objects(b).drop(1).associate{it.getString("sha256") to it.getInt("sizeBytes")}
        if(closure!=declared)SyncV2Codec.fail("closure_incomplete")
        val out=ByteArrayOutputStream();var total=0L
        fun append(raw:ByteArray){if(out.size().toLong()+raw.size>if(o.getString("encoding")=="gzip")67108864L else original.toLong())SyncV2Codec.fail("object_too_large");out.write(raw)}
        for(i in 0 until chunks.length()) {
            val c=chunks.getJSONObject(i);val blob=read(c.getString("ciphertextSha256"),c.getInt("ciphertextBytes"))
            if(!blob.copyOfRange(7,23).contentEquals(base64(c.getString("saltBase64"),16)) || !blob.copyOfRange(23,35).contentEquals(base64(c.getString("nonceBase64"),12)))SyncV2Codec.fail("hash_mismatch")
            val raw=unseal(key,"watchrss/cloud/chunk/v1","watchrss-cloud-chunk-v1:${c.getString("plaintextSha256")}","WRSSCC2",blob)
            if(raw.size!=c.getInt("plaintextBytes") || hash(raw)!=c.getString("plaintextSha256"))SyncV2Codec.fail("hash_mismatch")
            total+=raw.size
            when(o.getString("encoding")){"identity","gzip"->append(raw);"gzip-chunks-v1"->append(gunzip(raw,original-out.size()));else->SyncV2Codec.fail("invalid_schema")}
        }
        if(total!=encoded)SyncV2Codec.fail("hash_mismatch")
        val result=if(o.getString("encoding")=="gzip")gunzip(out.toByteArray(),original)else out.toByteArray()
        if(result.size!=original || o.has("contentSha256")&&o.getString("contentSha256")!=hash(result))SyncV2Codec.fail("hash_mismatch")
        return result
    }
    private fun base64(s:String,size:Int):ByteArray=Base64.getDecoder().decode(s).also{if(it.size!=size || Base64.getEncoder().encodeToString(it)!=s)SyncV2Codec.fail("invalid_encoding")}
    private fun gunzip(raw:ByteArray,max:Int):ByteArray=GZIPInputStream(raw.inputStream()).use{input->val out=ByteArrayOutputStream();val buf=ByteArray(8192);while(true){val n=input.read(buf);if(n<0)break;if(out.size().toLong()+n>max)SyncV2Codec.fail("object_too_large");out.write(buf,0,n)};out.toByteArray()}
    private fun openLegacy(b:JSONObject,binding:Binding,key:ByteArray,manifest:ByteArray,read:(String,Int)->ByteArray):ByteArray {
        val codec=com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec()
        val legacy=codec.decryptManifest(key,b.getString("sourceSnapshotId"),manifest)
        if(legacy.keyVersion.toLong()!=b.getLong("keyVersion"))SyncV2Codec.fail("key_unavailable")
        val selected=legacy.objects.singleOrNull{it.name==if(binding.kind=="asset")"notes-assets.zip"else name(binding.kind,binding.logicalId)}?:SyncV2Codec.fail("source_identity_missing")
        // Validate legacy compatibility and key wrapping before discarding locator/business metadata.
        legacy.requireReadable()
        if(selected.access in setOf(com.lightningstudio.watchrss.phone.cloud.CloudFormatAccess.OPAQUE,com.lightningstudio.watchrss.phone.cloud.CloudFormatAccess.ISOLATED) || selected.entryRef!=null)SyncV2Codec.fail("unsupported_feature")
        val objectKey=codec.unwrapReaderKey(key,selected)
        val raw=try {
            val old=selected.toJson();val body=JSONObject()
            for(field in listOf("name","encoding","originalBytes","encodedBytes","contentSha256"))if(old.has(field))body.put(field,old.get(field))
            body.put("chunks",JSONArray(selected.chunks.map{chunk->val c=chunk.toJson();JSONObject().also{out->
                for(field in com.lightningstudio.watchrss.phone.cloud.CloudChunkDescriptor.fields)out.put(field,c.get(field))}}))
            decodeObject(body,b,objectKey,if(binding.kind=="asset")32*1024*1024 else limit(binding.kind),read)
        }finally{if(objectKey!==key)objectKey.fill(0)}
        val body=if(binding.kind=="book")raw else java.util.zip.ZipInputStream(raw.inputStream()).use{zip->var result:ByteArray?=null;val seen=hashSetOf<String>();var total=0L
            while(true){val entry=zip.nextEntry?:break;if(!seen.add(entry.name)||seen.size>10000)SyncV2Codec.fail("invalid_schema");val keep=entry.name==name(binding.kind,binding.logicalId);val out=ByteArrayOutputStream();val buf=ByteArray(8192)
                while(true){val n=zip.read(buf);if(n<0)break;total+=n;if(total>32*1024*1024 || keep&&out.size()+n>limit(binding.kind))SyncV2Codec.fail("object_too_large");if(keep)out.write(buf,0,n)}
                if(keep)result=out.toByteArray();zip.closeEntry()};result?:SyncV2Codec.fail("source_identity_missing")}
        if(revision(binding.kind,binding.entityKey,hash(body))!=binding.bodyRevision)SyncV2Codec.fail("hash_mismatch")
        return body
    }
}
