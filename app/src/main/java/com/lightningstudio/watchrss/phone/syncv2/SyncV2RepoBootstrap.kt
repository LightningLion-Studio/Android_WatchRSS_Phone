package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** Pending identity is persisted BEFORE constructing or sending repo/init. No replacement K_id on retry. */
internal object SyncV2RepoBootstrap {
    data class Candidate(val descriptor:JSONObject,val recoveryCapsule:JSONObject) {
        val scope:JSONObject get()=descriptor.getJSONObject("body").getJSONObject("scope")
    }
    @Synchronized fun prepare(context:Context,accountId:String,legacyDeviceId:String,legacyEpoch:String,legacyGeneration:String,
        approvedWrappingRoot:ByteArray,wrappingKeyVersion:Long):Candidate {
        SyncV2Codec.unhex(accountId,16)
        if(approvedWrappingRoot.size!=32 || wrappingKeyVersion !in 1..4294967295L || listOf(legacyDeviceId,legacyEpoch,legacyGeneration).any{it.isEmpty()})SyncV2Codec.fail("authentication_required")
        val master=MasterKey.Builder(context,"watchrss-syncv2-pending-repo").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs=EncryptedSharedPreferences.create(context,"watchrss-syncv2-pending-repo",master,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        return prepareStored(accountId,legacyDeviceId,legacyEpoch,legacyGeneration,wrappingKeyVersion,
            { approvedWrappingRoot },{prefs.getString(accountId,null)?.let{SyncV2Wire.parse(SyncV2Codec.utf8(it))}}) {
            if(!prefs.edit().putString(accountId,SyncV2Codec.canonical(it)).commit())SyncV2Codec.fail("storage_pressure")
        }
    }
    /** The secure port commits complete candidate bytes before the first repo-init request. */
    internal fun prepareStored(accountId:String,legacyDeviceId:String,legacyEpoch:String,legacyGeneration:String,
        version:Long,key:(Long)->ByteArray,load:()->JSONObject?,save:(JSONObject)->Unit):Candidate {
        SyncV2Codec.unhex(accountId,16)
        if(version !in 1..4294967295L || listOf(legacyDeviceId,legacyEpoch,legacyGeneration).any{it.isEmpty()})SyncV2Codec.fail("authentication_required")
        val fence=SyncV2Codec.hash(JSONObject().put("accountId",accountId).put("legacyDeviceId",legacyDeviceId).put("libraryEpoch",legacyEpoch).put("contentGeneration",legacyGeneration))
        val pending=load()?:run {
            val raw=SyncV2Signing.randomPrivate()
            try {
                JSONObject().put("fence",fence).put("repoId",SyncV2Codec.hex(SyncV2Codec.random(16)))
                    .put("creationId",SyncV2Codec.hex(SyncV2Codec.random(16))).put("idKeyHex",SyncV2Codec.hex(SyncV2Codec.random(32)))
                    .put("creatorPrivateHex",SyncV2Codec.hex(raw)).put("creatorDeviceId",SyncV2Codec.hex(SyncV2Codec.random(16)))
                    .put("creatorKeyId",SyncV2Codec.hex(SyncV2Codec.random(16))).also(save)
            } finally {raw.fill(0)}
        }
        if(pending.getString("fence")!=fence)SyncV2Codec.fail("scope_mismatch")
        if(pending.has("descriptor")) {
            val result=Candidate(pending.getJSONObject("descriptor"),pending.getJSONObject("recoveryCapsule"))
            SyncV2Identity.recover(result.recoveryCapsule,result.descriptor,key(result.descriptor.getJSONObject("body").getLong("wrappingKeyVersion")),accountId,result.scope,SyncV2Codec.oid("repo-descriptor",result.descriptor)).close()
            return result
        }
        val raw=SyncV2Codec.unhex(pending.getString("creatorPrivateHex"),32)
        val signer=try{SyncV2Signing.fromPrivate(raw,pending.getString("creatorDeviceId"),pending.getString("creatorKeyId"))}finally{raw.fill(0)}
        val idKey=SyncV2Codec.unhex(pending.getString("idKeyHex"),32)
        val scope=JSONObject().put("repoId",pending.getString("repoId")).put("libraryEpoch","1").put("contentGeneration","1")
        val result=try{build(accountId,scope,pending.getString("creationId"),idKey,key(version),version,signer,SyncV2Codec.random(32),SyncV2Codec.random(12))}
            finally{idKey.fill(0);signer.close()}
        pending.put("descriptor",result.descriptor).put("recoveryCapsule",result.recoveryCapsule)
        save(pending)
        return result
    }
    internal fun build(accountId:String,scope:JSONObject,creationId:String,idKey:ByteArray,wrap:ByteArray,version:Long,
        signer:SyncV2Signing,salt:ByteArray,nonce:ByteArray):Candidate {
        SyncV2Codec.unhex(accountId,16);SyncV2Codec.scope(scope);SyncV2Codec.unhex(creationId,16)
        if(idKey.size!=32 || wrap.size!=32 || version !in 1..4294967295L || salt.size!=32 || nonce.size!=12)SyncV2Codec.fail("invalid_schema")
        val commitment=SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/id-key-commitment"),idKey)))
        val body=JSONObject().put("version",2).put("accountId",accountId).put("scope",scope).put("creationId",creationId)
            .put("creatorDeviceId",signer.deviceId).put("creatorPlatform","android").put("wrappingKeyVersion",version).put("creatorPublicKey",signer.publicHex).put("idKeyCommitment",commitment)
        val proof=SyncV2Codec.b64(SyncV2Codec.hmac(wrap,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/repo-bootstrap-auth"),SyncV2Codec.utf8(SyncV2Codec.canonical(body)))))
        val descriptor=JSONObject().put("body",body).put("proof",proof)
        descriptor.put("signature",signer.sign(SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/repo-bootstrap-sign"),SyncV2Codec.utf8(SyncV2Codec.canonical(descriptor)))))
        val header=JSONObject().put("version",2).put("domain","wrss.sync.v2/id-key-recovery").put("accountId",accountId).put("scope",scope)
            .put("descriptorOid",SyncV2Codec.oid("repo-descriptor",descriptor)).put("wrappingKeyVersion",version).put("salt",SyncV2Codec.b64(salt)).put("nonce",SyncV2Codec.b64(nonce))
        val aad=SyncV2Codec.utf8(SyncV2Codec.canonical(header));val key=SyncV2Codec.hkdf(wrap,salt,SyncV2Codec.lp(SyncV2Codec.utf8(header.getString("domain")),SyncV2Codec.sha(aad)))
        val cipher=try{SyncV2Codec.encrypt(key,nonce,aad,SyncV2Codec.utf8(SyncV2Codec.canonical(JSONObject().put("idKeyHex",SyncV2Codec.hex(idKey)))))}finally{key.fill(0)}
        return Candidate(descriptor,JSONObject().put("header",header).put("ciphertext",SyncV2Codec.b64(cipher)))
    }
    /** Conflict is not an empty repo. Caller must recover the winner before any identity-map scan. */
    fun publish(client:SyncV2CloudClient,candidate:Candidate,wrap:ByteArray,accountId:String):SyncV2Identity = publish(client,candidate,{wrap},accountId)
    fun publish(client:SyncV2CloudClient,candidate:Candidate,key:(Long)->ByteArray,accountId:String):SyncV2Identity {
        client.capabilities()
        try{client.repoInit(candidate.descriptor,candidate.recoveryCapsule)}catch(e:SyncV2CloudError) {
            if(e.code!="ref_conflict")throw e
            val winner=client.descriptor();val descriptor=winner.getJSONObject("descriptor");val pin=winner.getString("descriptorOid")
            val recovery=SyncV2Wire.parse(client.recovery(winner.getString("recoveryOid")),8192,true)
            return SyncV2Identity.recover(recovery,descriptor,key(SyncV2Wire.uint(descriptor.getJSONObject("body"),"wrappingKeyVersion",4294967295)),accountId,descriptor.getJSONObject("body").getJSONObject("scope"),pin)
        }
        return SyncV2Identity.recover(candidate.recoveryCapsule,candidate.descriptor,key(candidate.descriptor.getJSONObject("body").getLong("wrappingKeyVersion")),accountId,candidate.scope,SyncV2Codec.oid("repo-descriptor",candidate.descriptor))
    }
}
