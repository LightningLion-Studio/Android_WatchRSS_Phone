package com.lightningstudio.watchrss.phone.syncv2

import org.bouncycastle.crypto.digests.SHA256Digest
import org.json.JSONArray
import org.json.JSONObject

/** Authenticated body cache only. Binding back into a business entity requires a short CAS by
 * the host using the captured rowSequence, entityKey, bodyRevision and deleted=false. */
internal class SyncV2ContentReceive(private val identity:SyncV2Identity,private val authors:SyncV2Authors,
    private val store:SyncV2ContentStore,private val worker:SyncV2ContentWorker,private val client:SyncV2CloudClient,
    private val key:(Long)->ByteArray,private val now:()->Long={System.currentTimeMillis()}) {
    class Verified private constructor(val rowJson:String,val recipeOid:String,val recipeJson:String) {
        fun bind(current:JSONObject,commit:(JSONObject)->Unit) {
            val captured=JSONObject(rowJson)
            if(current.getBoolean("deleted") || listOf("entityKey","bodyRevision","rowSequence","recipeOid").any{current.get(it)!=captured.get(it)})SyncV2Codec.fail("body_revision_changed")
            commit(JSONObject(recipeJson))
        }
        companion object {internal fun create(row:JSONObject,recipe:JSONObject)=Verified(SyncV2Codec.canonical(row),row.getString("recipeOid"),SyncV2Codec.canonical(recipe))}
    }
    class Recipe private constructor(internal val value:String,internal val oid:String) {
        companion object {internal fun create(value:JSONObject,oid:String)=Recipe(SyncV2Codec.canonical(value),oid)}
    }
    fun recipe(row:JSONObject,window:String):Recipe {
        val oid=SyncV2Wire.oid(row.getString("recipeOid"),"recipe")
        val value=SyncV2Wire.parse(worker.authenticate(oid,key),262144,true);SyncV2ContentCodec.matchRow(value,row)
        val blobs=JSONArray();val blocks=value.getJSONArray("blocks");for(i in 0 until blocks.length())blobs.put(blocks.getJSONObject(i).getString("oid"))
        val mapping=client.reference(oid,window).apply{remove("version")}
        val raw=client.raw(mapping.getString("refsOid"),window)
        SyncV2ContentCodec.verifyRefs(identity,authors,oid,blobs,mapping,raw)
        val availability=client.availability(row.getString("entityKey"),row.getString("bodyRevision"),oid).getJSONObject("availability")
        SyncV2Codec.exact(availability,setOf("scope","entityKey","bodyRevision","recipeOid","status","receipt"))
        if(SyncV2Codec.scope(availability.getJSONObject("scope"))!=identity.scopeJson || listOf("entityKey","bodyRevision","recipeOid").any{availability.get(it)!=row.get(it)})SyncV2Codec.fail("invalid_schema")
        val status=client.verifyAvailability(availability.getJSONObject("receipt"),row.getString("entityKey"),row.getString("bodyRevision"),oid,blobs,now())
        if(status!=availability.getString("status"))SyncV2Codec.fail("invalid_schema")
        if(status!="cloud-ready")SyncV2Codec.fail(status.replace('-','_'))
        store.save("recipe/${row.getString("entityKey")}/${row.getString("bodyRevision")}",JSONObject().put("recipe",value).put("recipeOid",oid))
        return Recipe.create(value,oid)
    }
    /** A step authenticates at most one 256 KiB block, then commits its running digest.
     * Reopening skips accepted blocks and never repeats network AEAD/decompression. */
    fun step(row:JSONObject,authenticated:Recipe):Verified? {
        val recipe=JSONObject(authenticated.value)
        if(authenticated.oid!=row.get("recipeOid"))SyncV2Codec.fail("body_revision_changed")
        worker.gate();SyncV2ContentCodec.matchRow(recipe,row)
        val name="receive/${row.getString("recipeOid")}"
        val binding=SyncV2Codec.hash(recipe)
        val state=store.load(name)?:JSONObject().put("binding",binding).put("index",0).put("digest",SyncV2Codec.b64(SHA256Digest().encodedState)).put("plainBytes",0)
        if(state.getString("binding")!=binding)SyncV2Codec.fail("hash_mismatch")
        if(state.optBoolean("complete",false))return Verified.create(row,recipe)
        val blocks=recipe.getJSONArray("blocks");val index=state.getInt("index")
        if(index<blocks.length()) {
            val block=blocks.getJSONObject(index);val plain=worker.authenticate(block.getString("oid"),key)
            if(plain.size!=block.getInt("plainBytes") || identity.opaque("blob",plain)!=block.getString("opaqueContentId"))SyncV2Codec.fail("fingerprint_mismatch")
            val digest=SHA256Digest(SyncV2Codec.decode64(state.getString("digest")));digest.update(plain,0,plain.size);store.counts.hashBytes+=plain.size
            state.put("index",index+1).put("plainBytes",state.getLong("plainBytes")+plain.size).put("digest",SyncV2Codec.b64(digest.encodedState));store.save(name,state);return null
        }
        val digest=SHA256Digest(SyncV2Codec.decode64(state.getString("digest")));val hash=ByteArray(32);digest.doFinal(hash,0)
        if(state.getLong("plainBytes")!=recipe.getLong("totalPlainBytes") || SyncV2Codec.hex(hash)!=recipe.getString("plaintextSha256"))SyncV2Codec.fail("hash_mismatch")
        if(identity.opaqueDigest("recipe",hash)!=recipe.getString("opaqueContentId"))SyncV2Codec.fail("fingerprint_mismatch")
        state.put("complete",true);store.save(name,state);return Verified.create(row,recipe)
    }
}
