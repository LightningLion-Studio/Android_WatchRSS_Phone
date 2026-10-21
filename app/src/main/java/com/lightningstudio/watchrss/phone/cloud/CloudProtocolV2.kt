package com.lightningstudio.watchrss.phone.cloud

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Byte domains are frozen by cloud-v2/vectors/v2-vectors.json. */
internal object CloudProtocolV2 {
    fun lp(vararg fields: Any): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { data -> fields.forEach { field ->
            val bytes = if (field is ByteArray) field else field.toString().toByteArray(Charsets.UTF_8)
            data.writeInt(bytes.size); data.write(bytes)
        } }
    }.toByteArray()
    fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun mac(key: ByteArray, bytes: ByteArray): String = hex(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes)
    })
    fun commitment(key: ByteArray, user: String, epoch: String, version: Int): String =
        mac(key, lp("watchrss/key-commit/v2", user, epoch, version))
    fun rotationProof(previous: ByteArray, user: String, epoch: String, version: Int, commitment: String): String =
        mac(previous, lp("watchrss/key-rotate/v2", user, epoch, version, commitment))
    fun attestation(key: ByteArray, user: String, epoch: String, device: String, spki: ByteArray): String =
        mac(key, lp("watchrss/device-attest/v2", user, epoch, device, sha(spki)))
    fun approvalCommitment(spki: ByteArray, nonce: ByteArray): String {
        require(nonce.size == 32)
        return hex(sha(lp("watchrss/approval-commit/v2", spki, nonce)))
    }
    fun transcript(user: String, epoch: String, request: String, requester: String, requesterSpki: ByteArray,
                   approver: String, approverSpki: ByteArray, requesterNonce: ByteArray, approverNonce: ByteArray): ByteArray {
        require(requesterNonce.size == 32 && approverNonce.size == 32)
        return lp("watchrss/approval/v2", user, epoch, request, requester, requesterSpki, approver, approverSpki, requesterNonce, approverNonce)
    }
    fun code(transcript: ByteArray): String = BigInteger(1, sha(transcript).copyOfRange(0, 8))
        .mod(BigInteger.valueOf(100000000)).toString().padStart(8, '0')
    fun signingKey(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    private fun delivery(transcript: ByteArray, payload: String) =
        lp("watchrss/approval-deliver/v2", sha(transcript), payload.toByteArray(Charsets.UTF_8))
    fun sign(privateKey: PrivateKey, transcript: ByteArray, payload: String): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey); update(delivery(transcript, payload)); sign()
        }
        var offset = 2
        fun integer(): ByteArray {
            require(der[offset++].toInt() == 2)
            val size = der[offset++].toInt() and 255
            val value = BigInteger(1, der.copyOfRange(offset, offset + size)).toByteArray()
            offset += size
            return ByteArray(32).also { value.takeLast(32).toByteArray().copyInto(it, 32 - minOf(32, value.size)) }
        }
        return integer() + integer()
    }
    fun verify(spki: ByteArray, transcript: ByteArray, payload: String, signature: ByteArray): Boolean = runCatching {
        require(signature.size == 64)
        fun integer(bytes: ByteArray): ByteArray {
            val positive = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(2, positive.size.toByte()) + positive
        }
        val content = integer(signature.copyOfRange(0, 32)) + integer(signature.copyOfRange(32, 64))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)))
            update(delivery(transcript, payload)); verify(byteArrayOf(0x30, content.size.toByte()) + content)
        }
    }.getOrDefault(false)
    fun equal(a: String?, b: String): Boolean = a != null && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}

data class CloudKeyCommitment(val keyVersion: Int, val commitment: String, val rotationProof: String? = null)
internal class CloudTrustException : IllegalStateException("云密钥验证失败，请恢复或重新批准")

/** Validates the complete candidate set before any key may be persisted. */
internal object CloudKeyTrust {
    /** Keep only the verifiable prefix; a bad future row cannot invalidate an older key. */
    fun verifiedSubset(user: String, epoch: String, floor: Int, commitments: List<CloudKeyCommitment>, keys: Map<Int, ByteArray>): Map<Int, ByteArray> {
        val verified = linkedMapOf<Int, ByteArray>()
        keys.toSortedMap().forEach { (version, key) ->
            val candidate = verified + (version to key)
            try {
                validate(user, epoch, floor, commitments.filter { it.keyVersion <= version }, candidate)
                verified[version] = key
            } catch (_: CloudTrustException) { /* Quarantine this version and any unanchored successors. */ }
        }
        return verified
    }

    fun validate(user: String, epoch: String, floor: Int, commitments: List<CloudKeyCommitment>, keys: Map<Int, ByteArray>) {
        if (epoch.isBlank() || commitments.isEmpty() || keys.isEmpty()) throw CloudTrustException()
        val ordered = commitments.sortedBy { it.keyVersion }
        if (ordered.map { it.keyVersion }.distinct().size != ordered.size || ordered.first().keyVersion <= floor ||
            ordered.first().rotationProof != null || ordered.zipWithNext().any { (a,b) -> b.keyVersion != a.keyVersion + 1 || b.rotationProof == null }) throw CloudTrustException()
        keys.toSortedMap().forEach { (version, key) ->
            val row = ordered.firstOrNull { it.keyVersion == version } ?: throw CloudTrustException()
            if (!CloudProtocolV2.equal(row.commitment, CloudProtocolV2.commitment(key, user, epoch, version))) throw CloudTrustException()
            if (version != ordered.first().keyVersion) {
                val previous = keys[version - 1] ?: throw CloudTrustException()
                if (!CloudProtocolV2.equal(row.rotationProof, CloudProtocolV2.rotationProof(previous, user, epoch, version, row.commitment))) throw CloudTrustException()
            }
        }
        if (ordered.first().keyVersion !in keys) throw CloudTrustException()
    }
}
