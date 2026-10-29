package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.cloud.CloudProtocolV2
import com.lightningstudio.watchrss.phone.cloud.CloudSnapshotCodec
import org.json.JSONObject
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The internet relay deliberately uses the existing setup encryption domain. */
object PairingWireCodec {
    private const val DOMAIN = "watchrss-ble-setup-v1"
    const val MAX_WIRE_BYTES = 16384
    const val MAX_CIPHERTEXT_BYTES = 12240
    const val MAX_KEY_PAYLOAD_BYTES = 14 * 1024
    private fun key(qr: WatchPairingQr, direction: String): ByteArray {
        require(direction == "phone" || direction == "watch")
        require(qr.active && qr.secret.size == 32) { "配对已结束" }
        return CloudSnapshotCodec.hkdfSha256(qr.secret, CloudProtocolV2.sha(qr.sessionId.toByteArray()),
            "$DOMAIN:${qr.sessionId}:$direction".toByteArray(), 32)
    }
    private fun aad(qr: WatchPairingQr, direction: String, sequence: Int) = "$DOMAIN:${qr.sessionId}:$direction:$sequence".toByteArray()
    fun sas(qr: WatchPairingQr, userId: String): String {
        check(qr.active) { "配对已结束" }
        val mac = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(qr.secret,"HmacSHA256")); doFinal(CloudProtocolV2.lp("watchrss/pairing-sas/v2",qr.sessionId,userId)) }
        return BigInteger(1,mac.copyOfRange(0,8)).mod(BigInteger.valueOf(100000000)).toString().padStart(8,'0')
    }
    internal fun sealText(qr: WatchPairingQr, direction: String, sequence: Int, text: String,
        nonce: ByteArray = ByteArray(12).also(SecureRandom()::nextBytes), maxSequence: Int = 15): JSONObject {
        require(maxSequence in setOf(15,255) && sequence in 0..maxSequence && nonce.size == 12)
        val k = key(qr,direction); val plain = text.toByteArray(Charsets.UTF_8)
        try {
            val encrypted = CloudSnapshotCodec.encryptAesGcm(k,nonce,plain,aad(qr,direction,sequence))
            return JSONObject().put("seq",sequence).put("n",Base64.getEncoder().encodeToString(nonce))
                .put("c",Base64.getEncoder().encodeToString(encrypted)).also { validate(it,sequence,maxSequence) }
        } finally { k.fill(0); plain.fill(0) }
    }
    fun seal(qr: WatchPairingQr, direction: String, sequence: Int, value: JSONObject, maxSequence:Int=15): JSONObject = sealText(qr,direction,sequence,value.toString(),maxSequence=maxSequence)
    fun validate(wire: JSONObject, sequence: Int, maxSequence:Int=15) {
        if (wire.toString().toByteArray(Charsets.UTF_8).size > MAX_WIRE_BYTES) tooLarge()
        require(maxSequence in setOf(15,255) && sequence in 0..maxSequence && wire.keys().asSequence().toSet() == setOf("seq","n","c")) { "配对响应格式无效" }
        require(wire.get("seq").toString() == sequence.toString()) { "配对响应次序错误" }
        val n = canonical(wire.getString("n")); val c = canonical(wire.getString("c"))
        require(n.size == 12 && c.size >= 16) { "配对响应格式无效" }
        if (c.size > MAX_CIPHERTEXT_BYTES) tooLarge()
    }
    private fun canonical(encoded: String): ByteArray = Base64.getDecoder().decode(encoded).also {
        require(Base64.getEncoder().encodeToString(it) == encoded) { "配对响应编码无效" }
    }
    fun open(qr: WatchPairingQr, direction: String, sequence: Int, wire: JSONObject, maxSequence:Int=15): JSONObject {
        validate(wire,sequence,maxSequence); val k=key(qr,direction)
        try {
            val plain=CloudSnapshotCodec.decryptAesGcm(k,canonical(wire.getString("n")),canonical(wire.getString("c")),aad(qr,direction,sequence))
            try { return JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
        } finally { k.fill(0) }
    }
    fun checkMessage(qr: WatchPairingQr, value: JSONObject) { seal(qr,"phone",15,value) }
    internal fun tooLarge(): Nothing = throw PairingException("pairing_payload_too_large","扫码交付超出限制，请改用设备审批 v2 授权")
}
