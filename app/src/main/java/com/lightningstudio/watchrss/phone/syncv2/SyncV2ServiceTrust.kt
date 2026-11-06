package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.json.JSONObject

/** C3: this record comes only from the existing authenticated bootstrap, never capabilities. */
internal class SyncV2ServiceTrust private constructor(val accountId:String,val keyId:String,val spki:String,private val publicHex:String) {
    private var blocked=false
    fun requireActive(){if(blocked)throw SyncV2CloudError("service_key_changed")}
    fun capabilities(value:JSONObject) {
        requireActive()
        if(value.get("receiptKeyId")!=keyId || value.get("receiptSigningPublicKey")!=spki) {blocked=true;throw SyncV2CloudError("service_key_changed")}
    }
    fun verify(domain:String,value:JSONObject):JSONObject {
        requireActive();SyncV2Codec.exact(value,setOf("body","signature"));val b=value.getJSONObject("body")
        if(!SyncV2Identity.verifySignature(publicHex,SyncV2Codec.lp(SyncV2Codec.utf8(domain),SyncV2Codec.utf8(SyncV2Codec.canonical(b))),SyncV2Wire.string(value,"signature")))SyncV2Codec.fail("signature_invalid")
        return b
    }
    fun receipt(value:JSONObject,scope:JSONObject,oid:String,total:Long,offset:Long,now:Long) {
        val body=verify("wrss.sync.v2/object-receipt-sign",value)
        SyncV2Codec.exact(body,setOf("version","scope","oid","totalBytes","durableOffset","storeId","receiptRevision","createdAtMillis","expiresAtMillis"))
        if(body.get("version")!=2 || SyncV2Codec.scope(body.getJSONObject("scope"))!=SyncV2Codec.scope(scope) || body.get("oid")!=oid || SyncV2Wire.uint(body,"totalBytes")!=total || SyncV2Wire.uint(body,"durableOffset")!=offset || offset>total)SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.unhex(SyncV2Wire.string(body,"storeId"),16)
        if(SyncV2Codec.d64(SyncV2Wire.string(body,"receiptRevision")).signum()==0)SyncV2Codec.fail("invalid_schema")
        val created=SyncV2Wire.uint(body,"createdAtMillis");val expires=SyncV2Wire.uint(body,"expiresAtMillis")
        if(expires<=created || expires-created>604800000 || expires<=now || created>now+30000)SyncV2Codec.fail("replay_detected")
    }
    fun availability(value:JSONObject,scope:JSONObject,entity:String,revision:String,recipe:String,blobs:org.json.JSONArray,now:Long):String {
        val body=verify("wrss.sync.v2/availability-sign",value)
        SyncV2Codec.exact(body,setOf("version","scope","entityKey","bodyRevision","recipeOid","status","blobOidsHash","storeId","receiptRevision","createdAtMillis","expiresAtMillis"))
        if(body.get("version")!=2 || SyncV2Codec.scope(body.getJSONObject("scope"))!=SyncV2Codec.scope(scope) || body.get("entityKey")!=entity || body.get("bodyRevision")!=revision || body.get("recipeOid")!=recipe)SyncV2Codec.fail("invalid_schema")
        SyncV2Codec.unhex(entity,32);SyncV2Codec.unhex(revision,16);SyncV2Wire.oid(recipe,"recipe")
        SyncV2Codec.unhex(SyncV2Wire.string(body,"storeId"),16)
        if(SyncV2Codec.d64(SyncV2Wire.string(body,"receiptRevision")).signum()==0)SyncV2Codec.fail("invalid_schema")
        val status=SyncV2Wire.string(body,"status")
        if(status !in setOf("cloud-ready","source-pending","source-unavailable"))SyncV2Codec.fail("invalid_schema")
        if(status=="cloud-ready") {
            val hash=SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.utf8(SyncV2Codec.canonical(blobs))))
            if(body.get("blobOidsHash")!=hash)SyncV2Codec.fail("hash_mismatch")
        } else if(!body.isNull("blobOidsHash"))SyncV2Codec.fail("invalid_schema")
        val created=SyncV2Wire.uint(body,"createdAtMillis");val expires=SyncV2Wire.uint(body,"expiresAtMillis")
        if(expires<=created || expires-created>604800000 || expires<=now || created>now+30000)SyncV2Codec.fail("replay_detected")
        return status
    }
    /** C5 authenticates the complete fixed window; it does not replace author or content verification. */
    fun checkpoint(value:JSONObject,scope:JSONObject,now:Long):JSONObject = SyncV2Wire.schema {
        SyncV2Codec.exact(value,setOf("version","scope","body","signature"))
        if(value.get("version")!=2 || SyncV2Codec.scope(value.getJSONObject("scope"))!=SyncV2Codec.scope(scope))SyncV2Codec.fail("scope_mismatch")
        val body=verify("wrss.sync.v2/checkpoint-sign",JSONObject().put("body",value.getJSONObject("body")).put("signature",value.get("signature")))
        SyncV2Codec.exact(body,setOf("scope","checkpointId","serverRevision","refs","account","historyFloorRevision","historyFloorMillis","windowId","issuedAtMillis","expiresAtMillis"))
        if(SyncV2Codec.scope(body.getJSONObject("scope"))!=SyncV2Codec.scope(scope))SyncV2Codec.fail("scope_mismatch")
        SyncV2Codec.unhex(SyncV2Wire.string(body,"checkpointId"),16);SyncV2Codec.unhex(SyncV2Wire.string(body,"windowId"),16)
        val revision=SyncV2Codec.d64(SyncV2Wire.string(body,"serverRevision"))
        if(SyncV2Codec.d64(SyncV2Wire.string(body,"historyFloorRevision"))>revision)SyncV2Codec.fail("invalid_schema")
        SyncV2Wire.uint(body,"historyFloorMillis")
        val issued=SyncV2Wire.uint(body,"issuedAtMillis");val expires=SyncV2Wire.uint(body,"expiresAtMillis")
        if(expires<=issued || expires-issued>300000 || expires<=now || issued>now+30000)SyncV2Codec.fail("replay_detected")
        val refs=body.getJSONArray("refs");if(refs.length()>64)SyncV2Codec.fail("invalid_schema")
        var previous=""
        for(i in 0 until refs.length()) {
            val ref=refs.getJSONObject(i);SyncV2Codec.exact(ref,setOf("deviceId","commitOid","refRevision"))
            val device=SyncV2Wire.string(ref,"deviceId");SyncV2Codec.unhex(device,16)
            if(device<=previous)SyncV2Codec.fail("invalid_schema");previous=device
            SyncV2Wire.oid(SyncV2Wire.string(ref,"commitOid"),"commit")
            if(SyncV2Codec.d64(SyncV2Wire.string(ref,"refRevision")).signum()==0)SyncV2Codec.fail("invalid_schema")
        }
        val account=body.getJSONObject("account");SyncV2Codec.exact(account,setOf("commitOid","revision","rollbackGeneration"))
        val accountRevision=SyncV2Codec.d64(SyncV2Wire.string(account,"revision"));val generation=SyncV2Codec.d64(SyncV2Wire.string(account,"rollbackGeneration"))
        if(account.isNull("commitOid")) {if(accountRevision.signum()!=0 || generation.signum()!=0)SyncV2Codec.fail("invalid_schema")}
        else {SyncV2Wire.oid(SyncV2Wire.string(account,"commitOid"),"commit");if(accountRevision.signum()==0)SyncV2Codec.fail("invalid_schema")}
        body
    }
    companion object {
        /** Explicit storage port lets synthetic HTTP tests inject a test bootstrap and durable test pin. */
        fun fromBootstrap(accountId:String,syncV2:JSONObject,pinned:JSONObject?,save:(JSONObject)->Unit):SyncV2ServiceTrust {
            SyncV2Codec.unhex(accountId,16);SyncV2Codec.exact(syncV2,setOf("receiptKeyId","receiptSigningPublicKey","algorithm"))
            val id=SyncV2Wire.string(syncV2,"receiptKeyId");SyncV2Codec.unhex(id,16)
            if(syncV2.get("algorithm")!="ES256")SyncV2Codec.fail("invalid_schema")
            val spki=SyncV2Wire.string(syncV2,"receiptSigningPublicKey");val raw=SyncV2Codec.decode64(spki)
            val pub=try {
                val info=SubjectPublicKeyInfo.getInstance(raw)
                if(!info.encoded.contentEquals(raw) || info.algorithm.algorithm.id!="1.2.840.10045.2.1" || ASN1ObjectIdentifier.getInstance(info.algorithm.parameters).id!="1.2.840.10045.3.1.7")SyncV2Codec.fail("invalid_schema")
                val bytes=info.publicKeyData.bytes
                val curve=SECNamedCurves.getByName("secp256r1");val point=curve.curve.decodePoint(bytes)
                if(bytes.size!=65 || bytes[0]!=4.toByte() || point.isInfinity || !point.isValid)SyncV2Codec.fail("invalid_schema")
                SyncV2Codec.hex(bytes)
            }catch(_:Exception){SyncV2Codec.fail("invalid_schema")}
            if(pinned!=null && SyncV2Codec.canonical(pinned)!=SyncV2Codec.canonical(syncV2))throw SyncV2CloudError("service_key_changed")
            if(pinned==null)save(JSONObject(syncV2.toString()))
            return SyncV2ServiceTrust(accountId,id,spki,pub)
        }
        @Synchronized fun install(context:Context,accountId:String,authenticatedBootstrap:JSONObject):SyncV2ServiceTrust {
            val master=MasterKey.Builder(context,"watchrss-syncv2-service-trust").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs=EncryptedSharedPreferences.create(context,"watchrss-syncv2-service-pins",master,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            val prior=prefs.getString(accountId,null)?.let{SyncV2Wire.parse(SyncV2Codec.utf8(it))}
            return fromBootstrap(accountId,authenticatedBootstrap.getJSONObject("syncV2"),prior){value->
                if(!prefs.edit().putString(accountId,SyncV2Codec.canonical(value)).commit())SyncV2Codec.fail("storage_pressure")
            }
        }
    }
}
