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
