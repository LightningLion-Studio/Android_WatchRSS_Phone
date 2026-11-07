package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal object SyncV2ContentCodec {
    fun openStored(identity:SyncV2Identity,store:SyncV2ContentStore,oid:String,key:(Long)->ByteArray):ByteArray {
        val raw=store.objectBytes(oid)
        if(raw.size<24)SyncV2Codec.fail("invalid_encoding")
        val length=java.nio.ByteBuffer.wrap(raw,4,4).int
        if(length !in 1..4096 || length+24>raw.size)SyncV2Codec.fail("invalid_encoding")
        val header=SyncV2Wire.parse(raw.copyOfRange(8,8+length),4096,true)
        return SyncV2Envelope.open(identity,key(header.getLong("keyVersion")),oid,raw)
    }
    const val BLOCK=262144
    const val FRAME=65536
    fun recipe(value:JSONObject):JSONObject = SyncV2Wire.schema {
        SyncV2Codec.exact(value,setOf("version","entityKey","bodyRevision","opaqueContentId","totalPlainBytes","plaintextSha256","blocks","ranges"))
        if(value.get("version")!=2)SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.unhex(SyncV2Wire.string(value,"entityKey"),32);SyncV2Codec.unhex(SyncV2Wire.string(value,"bodyRevision"),16)
        for(k in listOf("opaqueContentId","plaintextSha256"))SyncV2Codec.unhex(SyncV2Wire.string(value,k),32)
        val blocks=value.getJSONArray("blocks");val ranges=value.getJSONArray("ranges")
        if(blocks.length()>2048 || ranges.length()>100000 || SyncV2Codec.utf8(SyncV2Codec.canonical(value)).size>BLOCK)SyncV2Codec.fail("object_too_large")
        val total=SyncV2Wire.uint(value,"totalPlainBytes");var sum=0L
        for(i in 0 until blocks.length()) {
            val b=blocks.getJSONObject(i);SyncV2Codec.exact(b,setOf("index","oid","plainBytes","opaqueContentId"))
            if(SyncV2Wire.uint(b,"index",4294967295)!=i.toLong())SyncV2Codec.fail("invalid_schema")
            SyncV2Wire.oid(SyncV2Wire.string(b,"oid"),"blob");SyncV2Codec.unhex(SyncV2Wire.string(b,"opaqueContentId"),32)
            val size=SyncV2Wire.uint(b,"plainBytes",BLOCK.toLong())
            if(size==0L || i<blocks.length()-1 && size!=BLOCK.toLong())SyncV2Codec.fail("invalid_schema")
            sum+=size
        }
        if(sum!=total)SyncV2Codec.fail("invalid_schema")
        val names=mutableSetOf<String>();var previous:Triple<Long,String,Long>?=null
        for(i in 0 until ranges.length()) {
            val r=ranges.getJSONObject(i);SyncV2Codec.exact(r,setOf("logicalPart","offset","length"))
            val name=SyncV2Wire.string(r,"logicalPart");if(SyncV2Codec.utf8(name).size>1024 || !names.add(name))SyncV2Codec.fail("invalid_schema")
            val offset=SyncV2Wire.uint(r,"offset");val length=SyncV2Wire.uint(r,"length")
            if(offset>total || length>total-offset)SyncV2Codec.fail("invalid_schema")
            val current=Triple(offset,name,length)
            if(previous!=null && compareValuesBy(previous,current,{it.first},{it.second},{it.third})>=0)SyncV2Codec.fail("invalid_schema")
            previous=current
        }
        value
    }
    fun matchRow(recipe:JSONObject,row:JSONObject) {
        recipe(recipe)
        if(row.getBoolean("deleted") || listOf("entityKey","bodyRevision","opaqueContentId").any{row.get(it)!=recipe.get(it)} ||
            SyncV2Wire.uint(row,"contentBytes")!=recipe.getLong("totalPlainBytes"))SyncV2Codec.fail("body_revision_changed")
    }
    fun verifyBody(identity:SyncV2Identity,recipe:JSONObject,plain:(JSONObject)->ByteArray,yieldControl:()->Unit={}) {
        recipe(recipe);val digest=MessageDigest.getInstance("SHA-256");val blocks=recipe.getJSONArray("blocks")
        // The content HMAC is SHA-based. Its final input is the digest, not another full body read.
        for(i in 0 until blocks.length()) {
            val b=blocks.getJSONObject(i);val bytes=plain(b)
            if(bytes.size!=b.getInt("plainBytes") || identity.opaque("blob",bytes)!=b.getString("opaqueContentId"))SyncV2Codec.fail("fingerprint_mismatch")
            digest.update(bytes);yieldControl()
        }
        val full=digest.digest()
        if(identity.opaqueDigest("recipe",full)!=recipe.getString("opaqueContentId"))SyncV2Codec.fail("fingerprint_mismatch")
        if(SyncV2Codec.hex(full)!=recipe.getString("plaintextSha256"))SyncV2Codec.fail("hash_mismatch")
    }
    fun refs(identity:SyncV2Identity,signer:SyncV2Signing,oid:String,blobs:JSONArray):JSONObject {
        SyncV2Wire.oid(oid,"recipe");SyncV2Wire.strings(blobs,2048,false).forEach{SyncV2Wire.oid(it,"blob")}
        return signer.signed("wrss.sync.v2/recipe-refs-sign",JSONObject().put("scope",identity.scope).put("recipeOid",oid).put("blobOids",blobs))
    }
    fun verifyRefs(identity:SyncV2Identity,authors:SyncV2Authors,parent:String,oids:JSONArray,mapping:JSONObject,raw:ByteArray) {
        val type=parent.substringBefore(':');if(type !in setOf("state","recipe"))SyncV2Codec.fail("invalid_oid")
        SyncV2Codec.exact(mapping,setOf("scope","parentOid","refsOid","authorDeviceId","keyId","totalBytes"))
        if(SyncV2Codec.scope(mapping.getJSONObject("scope"))!=identity.scopeJson || mapping.get("parentOid")!=parent)SyncV2Codec.fail("scope_mismatch")
        val refs=SyncV2Wire.oid(SyncV2Wire.string(mapping,"refsOid"),"$type-refs")
        if(raw.size.toLong()!=SyncV2Wire.uint(mapping,"totalBytes",262144) || refs!="$type-refs:sha256:${SyncV2Codec.hex(SyncV2Codec.sha(raw))}")SyncV2Codec.fail("hash_mismatch")
        val signed=SyncV2Wire.parse(raw,262144,true);SyncV2Codec.exact(signed,setOf("body","signature"))
        val body=signed.getJSONObject("body");val field=if(type=="state")"segmentOids" else "blobOids"
        SyncV2Codec.exact(body,setOf("scope","${type}Oid",field))
        if(SyncV2Codec.scope(body.getJSONObject("scope"))!=identity.scopeJson || body.get("${type}Oid")!=parent || SyncV2Codec.canonical(body.getJSONArray(field))!=SyncV2Codec.canonical(oids))SyncV2Codec.fail("invalid_schema")
        val pub=authors.publicKey(SyncV2Wire.string(mapping,"authorDeviceId"),SyncV2Wire.string(mapping,"keyId"),true)
        if(!SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/$type-refs-sign"),SyncV2Codec.utf8(SyncV2Codec.canonical(body))),SyncV2Wire.string(signed,"signature")))SyncV2Codec.fail("signature_invalid")
    }
}
