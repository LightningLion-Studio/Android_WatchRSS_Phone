package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Synthetic authenticated input. Deliberately not the production writer, which is disabled this phase. */
internal object ArticleProvenanceFixtures {
    val key=ByteArray(32){it.toByte()}
    const val user="synthetic-provenance-owner"
    private val sequence=AtomicInteger()
    fun resource(name:String)=requireNotNull(javaClass.getResource("/article-provenance-v1/$name")).readBytes()
    fun envelope(raw:ByteArray,id:String):JSONObject {
        val nonce=java.nio.ByteBuffer.allocate(12).putLong(0).putInt(sequence.incrementAndGet()).array()
        val derived=CloudSnapshotCodec.hkdfSha256(key,user.toByteArray(),"watchrss/cloud/record/v1".toByteArray(),32)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(derived,"AES"),GCMParameterSpec(128,nonce))
        cipher.updateAAD("watchrss-cloud-record-v1:$user:$id:1".toByteArray())
        return JSONObject().put("algorithm","A256GCM").put("keyVersion",1).put("nonceBase64",Base64.getEncoder().encodeToString(nonce))
            .put("ciphertextBase64",Base64.getEncoder().encodeToString(cipher.doFinal(raw))).also{derived.fill(0)}
    }
    fun decode(raw:String):DecodedCloudRecord {
        val id=PhoneCloudRecordCodec.id(key,user,"article",JSONObject(raw).getJSONObject("value"))
        return PhoneCloudRecordCodec.decryptDocument(key,user,id,envelope(raw.toByteArray(),id))
    }
}
