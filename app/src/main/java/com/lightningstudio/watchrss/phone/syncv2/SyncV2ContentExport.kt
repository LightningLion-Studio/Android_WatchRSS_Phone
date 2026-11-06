package com.lightningstudio.watchrss.phone.syncv2

import org.bouncycastle.crypto.digests.SHA256Digest
import org.json.JSONArray
import org.json.JSONObject

/** Caller supplies an immutable versioned source. State edits do not alter this body binding. */
internal class SyncV2ContentExport(private val identity:SyncV2Identity,private val key:ByteArray,private val keyVersion:Long,
    private val store:SyncV2ContentStore) {
    val authenticationBinding=SyncV2Codec.hash(JSONObject().put("scope",identity.scope).put("descriptorOid",identity.descriptorOid).put("keyVersion",keyVersion))
    interface Source {
        val size:Long
        /** Must change when the original body changes. Never derived from mutable reading state. */
        val version:String
        fun read(offset:Long,maxBytes:Int):ByteArray
    }
    data class Binding(val entity:String,val revision:String,val authentication:String) {
        fun key()="export/$entity/$revision/$authentication"
    }
    fun step(binding:Binding,source:Source,ranges:JSONArray=JSONArray()):Boolean {
        val at=System.nanoTime();try{return prepare(binding,source,ranges)}finally{store.counts.preparationNanos+=System.nanoTime()-at}
    }
    private fun prepare(binding:Binding,source:Source,ranges:JSONArray):Boolean {
        SyncV2Codec.unhex(binding.entity,32);SyncV2Codec.unhex(binding.revision,16)
        if(source.size<0 || source.size>2048L*262144 || ranges.length()>100000)SyncV2Codec.fail("object_too_large")
        val fingerprint=SyncV2Codec.hash(JSONObject().put("source",source.version).put("bytes",source.size).put("keyVersion",keyVersion).put("ranges",ranges))
        val name=binding.key();val previous=store.load(name)
        if(previous!=null && previous.getString("sourceBinding")!=fingerprint)SyncV2Codec.fail("body_revision_changed")
        val state=previous?:JSONObject().put("sourceBinding",fingerprint).put("blocks",JSONArray()).put("offset",0).put("digest",SyncV2Codec.b64(SHA256Digest().encodedState))
        if(state.has("recipeOid"))return true
        val blocks=state.getJSONArray("blocks");val offset=state.getLong("offset")
        if(offset<source.size) {
            // A staged completed block includes the running digest. Reopening adopts it without source IO.
            val stagedKey="$name/block/${blocks.length()}"
            val staged=store.load(stagedKey)?:run {
                val n=minOf(262144L,source.size-offset).toInt();val bytes=source.read(offset,n)
                if(bytes.size!=n || fingerprint!=SyncV2Codec.hash(JSONObject().put("source",source.version).put("bytes",source.size).put("keyVersion",keyVersion).put("ranges",ranges)))SyncV2Codec.fail("body_revision_changed")
                store.counts.sourceBytes+=n;store.counts.exports++;store.counts.hashBytes+=n
                val digest=SHA256Digest(SyncV2Codec.decode64(state.getString("digest")));digest.update(bytes,0,bytes.size)
                val sealed=SyncV2Envelope.seal(identity,key,keyVersion,"blob",bytes);store.counts.seals++
                // Keep exact sealed bytes with the digest until the object's durable file is installed.
                JSONObject().put("descriptor",JSONObject().put("index",blocks.length()).put("oid",sealed.oid).put("plainBytes",n).put("opaqueContentId",sealed.header.getString("opaqueContentId")))
                    .put("digest",SyncV2Codec.b64(digest.encodedState)).put("bytes",SyncV2Codec.b64(sealed.bytes))
                    .also{store.save(stagedKey,it)}
            }
            val descriptor=staged.getJSONObject("descriptor")
            store.putObject(descriptor.getString("oid"),SyncV2Codec.decode64(staged.getString("bytes")))
            blocks.put(descriptor);state.put("offset",offset+descriptor.getInt("plainBytes")).put("digest",staged.getString("digest"))
            // Enforce actual recipe budget before accepting this candidate. Original source is untouched.
            val probe=JSONObject().put("version",2).put("entityKey",binding.entity).put("bodyRevision",binding.revision).put("opaqueContentId","0".repeat(64))
                .put("totalPlainBytes",state.getLong("offset")).put("plaintextSha256","0".repeat(64)).put("blocks",blocks).put("ranges",JSONArray())
            SyncV2ContentCodec.recipe(probe)
            store.save(name,state);return false
        }
        val digest=SHA256Digest(SyncV2Codec.decode64(state.getString("digest")));val hash=ByteArray(32);digest.doFinal(hash,0)
        val recipe=JSONObject().put("version",2).put("entityKey",binding.entity).put("bodyRevision",binding.revision)
            .put("opaqueContentId",identity.opaqueDigest("recipe",hash)).put("totalPlainBytes",source.size).put("plaintextSha256",SyncV2Codec.hex(hash)).put("blocks",blocks).put("ranges",ranges)
        SyncV2ContentCodec.recipe(recipe)
        val stagedKey="$name/recipe"
        val staged=store.load(stagedKey)?:SyncV2Envelope.seal(identity,key,keyVersion,"recipe",SyncV2Codec.utf8(SyncV2Codec.canonical(recipe))).let {
            store.counts.seals++;JSONObject().put("oid",it.oid).put("bytes",SyncV2Codec.b64(it.bytes)).also{v->store.save(stagedKey,v)}
        }
        store.putObject(staged.getString("oid"),SyncV2Codec.decode64(staged.getString("bytes")))
        state.put("recipe",recipe).put("recipeOid",staged.getString("oid"));store.save(name,state);return true
    }
    fun descriptor(binding:Binding):JSONObject?=store.load(binding.key())?.takeIf{it.has("recipeOid")}
}
