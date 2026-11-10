package com.lightningstudio.watchrss.phone.syncv2

import com.lightningstudio.watchrss.phone.cloud.CloudKeyManager

/** Owns only authenticated v1 account-key copies. HKDF separates uses inside each wire codec. */
internal class SyncV2ContentKeys private constructor(private val keys:Map<Long,ByteArray>):AutoCloseable {
    private var closed=false
    val newestVersion:Long get()=synchronized(this) { active();keys.keys.max() }
    @Synchronized fun read(version:Long):ByteArray { active();return keys[version]?:SyncV2Codec.fail("key_unavailable") }
    private fun active(){if(closed)SyncV2Codec.fail("authentication_required")}
    @Synchronized override fun close(){closed=true;keys.values.forEach{it.fill(0)}}
    companion object {
        /** The v1 trust verifier authenticates the entire set before this object escapes. */
        fun authenticated(manager:CloudKeyManager,userId:String,verify:(Map<Int,ByteArray>)->Unit):SyncV2ContentKeys {
            val values=linkedMapOf<Int,ByteArray>()
            try {
                for(v in manager.keyVersions(userId))values[v]=manager.getAccountKey(userId,v)?:SyncV2Codec.fail("key_unavailable")
                if(values.isEmpty())SyncV2Codec.fail("key_unavailable")
                verify(values)
                if(values.any{it.key<=0 || it.value.size!=32})SyncV2Codec.fail("key_unavailable")
                return SyncV2ContentKeys(values.mapKeys{it.key.toLong()})
            } catch(e:Throwable){values.values.forEach{it.fill(0)};throw e}
        }
    }
}
