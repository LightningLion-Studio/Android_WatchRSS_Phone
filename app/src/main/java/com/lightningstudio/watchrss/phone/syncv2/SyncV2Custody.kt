package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONArray
import org.json.JSONObject

/** C19/C24: custody pins are durable non-expiring roots, not ordinary receive ACKs.
 * The content store has no eviction API. Release/transfer remains closed. */
internal class SyncV2Custody(private val identity:SyncV2Identity,private val store:SyncV2ContentStore,
    private val signer:SyncV2Signing,private val authors:SyncV2Authors,private val key:(Long)->ByteArray) {
    companion object {
        private val fields=setOf("version","scope","pinId","holderDeviceId","claimantDeviceId","entityKey","bodyRevision","recipeOid","blobOidsHash","createdAtMillis")
        fun body(value:JSONObject,scope:JSONObject):JSONObject {
            SyncV2Codec.exact(value,fields)
            if(value.get("version")!=2)SyncV2Codec.fail("invalid_schema")
            if(SyncV2Codec.scope(value.getJSONObject("scope"))!=SyncV2Codec.scope(scope))SyncV2Codec.fail("scope_mismatch")
            for(f in listOf("pinId","holderDeviceId","claimantDeviceId","bodyRevision"))SyncV2LocalProtocol.id(SyncV2Wire.string(value,f))
            for(f in listOf("entityKey","blobOidsHash"))SyncV2Codec.unhex(SyncV2Wire.string(value,f),32)
            SyncV2Wire.oid(SyncV2Wire.string(value,"recipeOid"),"recipe");SyncV2Wire.uint(value,"createdAtMillis")
            if(value.get("holderDeviceId")==value.get("claimantDeviceId"))SyncV2Codec.fail("invalid_schema")
            return value
        }
        fun verify(value:JSONObject,scope:JSONObject,authors:SyncV2Authors):JSONObject {
            SyncV2Codec.exact(value,setOf("body","signature"));val b=body(value.getJSONObject("body"),scope)
            val pub=authors.publicKey(b.getString("holderDeviceId"))
            if(!SyncV2Identity.verifySignature(pub,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/custody-pin-sign"),SyncV2LocalProtocol.bytes(b)),value.getString("signature")))SyncV2Codec.fail("signature_invalid")
            return b
        }
    }
    private fun open(oid:String):ByteArray {
        val raw=store.objectBytes(oid)
        if(raw.size<24)SyncV2Codec.fail("invalid_encoding")
        val length=java.nio.ByteBuffer.wrap(raw,4,4).int
        if(length !in 1..4096 || 8+length+16>raw.size)SyncV2Codec.fail("invalid_encoding")
        val header=SyncV2Wire.parse(raw.copyOfRange(8,8+length),4096,true)
        return SyncV2Envelope.open(identity,key(header.getLong("keyVersion")),oid,raw)
    }
    /** Before signing, authenticate every blob and the complete ordered plaintext fingerprint. */
    private fun closure(b:JSONObject):List<String> {
        val recipe=SyncV2ContentCodec.recipe(SyncV2Wire.parse(open(b.getString("recipeOid")),262144,true))
        if(recipe.get("entityKey")!=b.get("entityKey") || recipe.get("bodyRevision")!=b.get("bodyRevision"))SyncV2Codec.fail("body_revision_changed")
        val blocks=recipe.getJSONArray("blocks");val blobs=JSONArray()
        for(i in 0 until blocks.length())blobs.put(blocks.getJSONObject(i).getString("oid"))
        if(b.get("blobOidsHash")!=SyncV2Codec.hash(blobs))SyncV2Codec.fail("hash_mismatch")
        val mapping=store.load("local-refs/${b.getString("recipeOid")}")?:SyncV2Codec.fail("closure_incomplete")
        SyncV2ContentCodec.verifyRefs(identity,authors,b.getString("recipeOid"),blobs,mapping,store.objectBytes(mapping.getString("refsOid")))
        SyncV2ContentCodec.verifyBody(identity,recipe,{block->open(block.getString("oid"))})
        return listOf(b.getString("recipeOid"),mapping.getString("refsOid"))+(0 until blobs.length()).map{blobs.getString(it)}
    }
    /** One custody writer per account store. A failed return after fsync can only retain a pin. */
    @Synchronized fun persist(session:SyncV2LocalSession,request:JSONObject):JSONObject {
        session.requireFraming();val b=body(request,identity.scope)
        if(b.get("holderDeviceId")!=signer.deviceId || b.get("holderDeviceId")!=session.selfDeviceId || b.get("claimantDeviceId")!=session.peerDeviceId)SyncV2Codec.fail("authentication_required")
        authors.publicKey(signer.deviceId);authors.publicKey(b.getString("claimantDeviceId"))
        val name="custody/${b.getString("pinId")}";val existing=store.load(name)
        if(existing!=null) {
            if(SyncV2Codec.canonical(existing.getJSONObject("body"))!=SyncV2Codec.canonical(b))SyncV2Codec.fail("idempotency_conflict")
            if(existing.getString("state")!="active")SyncV2Codec.fail("feature_disabled")
            return JSONObject().put("body",existing.get("body")).put("signature",existing.get("signature"))
        }
        val refs=closure(b);val signed=signer.signed("wrss.sync.v2/custody-pin-sign",JSONObject(b.toString()))
        store.save(name,JSONObject(signed.toString()).put("state","active").put("protectedOids",JSONArray(refs)))
        return signed
    }
    fun read(pinId:String):JSONObject {SyncV2LocalProtocol.id(pinId);return store.load("custody/$pinId")?.let{JSONObject().put("body",it.get("body")).put("signature",it.get("signature")).put("state",it.get("state"))}?:SyncV2Codec.fail("object_missing")}
    /** Claimant stores a peer's signed durable pin separately from its object ACKs. */
    @Synchronized fun receive(session:SyncV2LocalSession,signed:JSONObject):JSONObject {
        session.requireFraming();val b=verify(signed,identity.scope,authors)
        if(b.get("holderDeviceId")!=session.peerDeviceId || b.get("claimantDeviceId")!=session.selfDeviceId)SyncV2Codec.fail("authentication_required")
        val name="peer-custody/${b.getString("pinId")}";val prior=store.load(name)
        if(prior!=null && SyncV2Codec.canonical(prior.getJSONObject("custody").getJSONObject("body"))!=SyncV2Codec.canonical(b))SyncV2Codec.fail("idempotency_conflict")
        if(prior!=null)return prior.getJSONObject("result")
        val result=JSONObject().put("pinId",b.get("pinId")).put("state","active").put("result",JSONObject(signed.toString()))
        store.save(name,JSONObject().put("custody",signed).put("result",result));return result
    }
    fun release(pinId:String):Nothing {SyncV2LocalProtocol.id(pinId);SyncV2Codec.fail("feature_disabled")}
}
