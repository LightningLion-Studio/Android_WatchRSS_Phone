package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Independent counter + Keystore-backed record. Neither damaged record has a fallback slot.
 * The counter is forced before the credential commit. Any interrupted disagreement stops signing. */
internal class SyncV2SigningVault(private val store:SyncV2AccountStore,private val repo:String,
    private val counter:File,private val lifetime:SyncV2Lifetime):AutoCloseable {
    private val name="local/signing/$repo"
    private val available:SyncV2Signing?=try { openSigner() } catch(_:Exception) {
        // A damaged credential disables signing, never independent authenticated reception.
        lifetime.check();null
    }
    val signer:SyncV2Signing get()=available?:SyncV2Codec.fail("signing_state_unavailable")
    fun signerOrNull():SyncV2Signing?=available
    private fun openSigner():SyncV2Signing {
        val saved=store.read(name)
        val record=if(saved!=null)saved else {
            if(counter.exists() || File(counter.path+".next").exists())SyncV2Codec.fail("signing_state_unavailable")
            val raw=SyncV2Signing.randomPrivate()
            try {
                JSONObject().put("privateHex",SyncV2Codec.hex(raw)).put("deviceId",SyncV2Codec.hex(SyncV2Codec.random(16)))
                    .put("keyId",SyncV2Codec.hex(SyncV2Codec.random(16))).put("sequence","0").also {
                        store.write(name,it);writeCounter(it)
                    }
            } finally {raw.fill(0)}
        }
        SyncV2Codec.d64(record.getString("sequence"))
        val raw=SyncV2Codec.unhex(record.getString("privateHex"),32)
        return try{SyncV2Signing.fromPrivate(raw,record.getString("deviceId"),record.getString("keyId"))}finally{raw.fill(0)}
    }
    private fun writeCounter(record:JSONObject) {
        counter.parentFile?.let{if(!it.isDirectory && !it.mkdirs())SyncV2Codec.fail("storage_pressure")}
        val next=File(counter.path+".next")
        val body=JSONObject().put("keyId",record.get("keyId")).put("sequence",record.get("sequence"))
        FileOutputStream(next).use{it.write(SyncV2Codec.utf8(SyncV2Codec.canonical(body)));it.fd.sync()}
        if(!next.renameTo(counter))SyncV2Codec.fail("storage_pressure")
        FileChannel.open(counter.parentFile!!.toPath(),StandardOpenOption.READ).use{it.force(true)}
    }
    fun reserve(floor:String):String=lifetime.guard {
        val record=store.read(name)?:SyncV2Codec.fail("signing_state_unavailable")
        val marker=try {
            if(File(counter.path+".next").exists() || counter.length() !in 1..1024)SyncV2Codec.fail("signing_state_unavailable")
            SyncV2Wire.parse(counter.readBytes(),1024,true)
        } catch(_:Exception){SyncV2Codec.fail("signing_state_unavailable")}
        if(marker.opt("keyId")!=signer.keyId || marker.opt("sequence")!=record.opt("sequence"))SyncV2Codec.fail("signing_state_unavailable")
        val next=SyncV2Codec.next(maxOf(SyncV2Codec.d64(record.getString("sequence")),SyncV2Codec.d64(floor)).toString())
        record.put("sequence",next)
        writeCounter(record)
        store.write(name,record)
        next
    }
    override fun close(){available?.close()}
}
