package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager
import org.json.JSONObject

/** Uses the same Keystore-backed preferences as v1. Public pins survive credential logout. */
internal class SyncV2AccountStore(private val keys:CloudKeyManager,private val user:String,private val lifetime:SyncV2Lifetime) {
    fun read(name:String):JSONObject?=lifetime.guard {keys.syncV2Read(user,name)?.let{SyncV2Wire.parse(SyncV2Codec.utf8(it))}}
    fun write(name:String,value:JSONObject?)=lifetime.guard {keys.syncV2Write(user,name,value?.let(SyncV2Codec::canonical))}
    fun pending()=read("secret/pending")
    fun pending(value:JSONObject)=write("secret/pending",value)
    fun localKey(repo:String):ByteArray=lifetime.guard {
        val name="local/$repo"
        val old=read(name)
        if(old!=null)SyncV2Codec.unhex(old.getString("key"),32) else SyncV2Codec.random(32).also {
            write(name,JSONObject().put("key",SyncV2Codec.hex(it)))
        }
    }
    /** Full descriptor/capsule validation precedes the one-record installed commit. */
    fun install(descriptor:JSONObject,capsule:JSONObject,descriptorOid:String,recoveryOid:String,contentKeys:SyncV2ContentKeys):SyncV2Identity {
        val body=descriptor.getJSONObject("body")
        val version=SyncV2Wire.uint(body,"wrappingKeyVersion",4294967295)
        val account=user.replace("-","").lowercase()
        val scope=body.getJSONObject("scope")
        if(SyncV2Codec.oid("id-key-recovery",capsule)!=recoveryOid)SyncV2Codec.fail("hash_mismatch")
        val identity=SyncV2Identity.recover(capsule,descriptor,contentKeys.read(version),account,scope,descriptorOid)
        try {
            return lifetime.guard {
                val pin=JSONObject().put("descriptorOid",descriptorOid).put("idKeyCommitment",body.get("idKeyCommitment"))
                    .put("wrappingKeyVersion",version).put("scope",scope)
                val prior=read("pin")
                if(prior!=null && SyncV2Codec.canonical(prior)!=SyncV2Codec.canonical(pin))SyncV2Codec.fail("identity_conflict")
                // Keep the public pin even if the following credential commit fails.
                write("pin",pin)
                val local=localKey(identity.repoId)
                try {
                    write("secret/installed",JSONObject().put("scope",identity.scopeJson).put("pin",pin)
                        .put("identity",SyncV2Codec.b64(identity.encryptedRecord(local))))
                } finally {local.fill(0)}
                write("secret/pending",null)
                identity
            }
        } catch(e:Throwable){identity.close();throw e}
    }
}
