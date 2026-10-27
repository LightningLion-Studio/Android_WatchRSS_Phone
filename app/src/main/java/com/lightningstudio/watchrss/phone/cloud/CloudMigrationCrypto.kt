package com.lightningstudio.watchrss.phone.cloud

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** R48 control documents use restricted RFC8785. Account-authenticated raw extensions never use this. */
object CloudMigrationJcs {
    fun bytes(value: JSONObject): ByteArray = canonical(value.toString()).toByteArray(Charsets.UTF_8)
    fun canonical(raw: String): String {
        val doc = CloudRawDocument.parse(raw)
        fun string(value: String): String = buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\")
                '\b' -> append("\\b"); '\u000c' -> append("\\f"); '\n' -> append("\\n")
                '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        fun node(n: CloudRawDocument.Node): String {
            n.properties?.let { props -> return props.sortedBy { it.key }.joinToString(",", "{", "}") { string(it.key) + ":" + node(it.value) } }
            n.elements?.let { elements -> return elements.joinToString(",", "[", "]") { node(it) } }
            val token = raw.substring(n.start, n.end)
            if (token.startsWith('"')) return string(JSONArray("[$token]").getString(0))
            if (token in setOf("null", "true", "false")) return token
            val number = BigDecimal(token)
            require(number.signum() >= 0 && number <= BigDecimal("9007199254740991")) { "migration_jcs_number" }
            return number.toBigIntegerExact().toString()
        }
        return node(doc.root)
    }
    fun hash(plan: JSONObject): String = CloudSnapshotCodec.sha256("watchrss/r48/plan/v1\n".toByteArray() + bytes(plan))
}

/** No production pins are learned from a network response. Empty bundled configuration fails closed. */
class CloudWorkerTrust(
    val currentPin: ByteArray?,
    val nextPin: ByteArray?,
    val revokedPinHashes: Set<String>,
    val allowedBuilds: Set<String>,
    val allowedTransforms: Set<String>
) {
    override fun toString() = "CloudWorkerTrust"
    val configured:Boolean get() = currentPin?.size==32 && nextPin?.size==32 && !currentPin.contentEquals(nextPin) && allowedBuilds.isNotEmpty() && allowedTransforms.isNotEmpty()
    companion object {
        fun disabled() = CloudWorkerTrust(null, null, emptySet(), emptySet(), emptySet())
        val bundled:CloudWorkerTrust by lazy {
            runCatching {
                val raw=com.lightningstudio.watchrss.phone.BuildConfig.R48_WORKER_TRUST_JSON
                val policy=CloudRawDocument.parse(raw).json()
                CloudMigrationCrypto.exact(policy,"currentPin","nextPin","revokedPinSha256","workerBuildSha256s","transforms")
                fun strings(key:String)=policy.getJSONArray(key).let{a->(0 until a.length()).map{a.getString(it)}.toSet()}
                val builds=strings("workerBuildSha256s").onEach{CloudMigrationCrypto.hash(it)}
                val revoked=strings("revokedPinSha256").onEach{CloudMigrationCrypto.hash(it)}
                val transforms=policy.getJSONArray("transforms").let{a->(0 until a.length()).map{CloudMigrationCrypto.transformKey(a.getJSONObject(it))}.toSet()}
                CloudWorkerTrust(CloudMigrationCrypto.b64(policy.getString("currentPin"),32),CloudMigrationCrypto.b64(policy.getString("nextPin"),32),revoked,builds,transforms)
            }.getOrElse{disabled()}
        }
    }
}

class CloudMigrationCrypto(private val random: SecureRandom = SecureRandom()) {
    fun verifyWorker(session: JSONObject, trust: CloudWorkerTrust, now: Long, transform: JSONObject): JSONObject {
        exact(session, "certificate", "signatureBase64", "signingKeyId")
        val cert = session.getJSONObject("certificate")
        exact(cert, "v", "suite", "sessionId", "pubKey", "notBefore", "expiresAt", "workerBuildSha256", "transforms")
        require(cert.getInt("v") == 1 && cert.getString("suite") == SUITE)
        uuid(cert.getString("sessionId")); b64(cert.getString("pubKey"), 32)
        val start = integer(cert, "notBefore"); val end = integer(cert, "expiresAt")
        require(start <= now && now < end && end - start in 1..86_400_000)
        val build = hash(cert.getString("workerBuildSha256")); require(build in trust.allowedBuilds)
        val transforms = cert.getJSONArray("transforms")
        val declared = (0 until transforms.length()).map { index ->
            val t = transforms.getJSONObject(index); exact(t, "id", "version", "codeSha256")
            require(integer(t,"version") > 0); hash(t.getString("codeSha256"))
            transformKey(t)
        }
        require(declared == declared.distinct().sorted())
        require(transformKey(transform) in declared && transformKey(transform) in trust.allowedTransforms)
        val keyId = hash(session.getString("signingKeyId"))
        require(keyId !in trust.revokedPinHashes) { "migration_worker_revoked" }
        val pin = listOfNotNull(trust.currentPin, trust.nextPin).singleOrNull { it.size == 32 && CloudSnapshotCodec.sha256(it) == keyId }
            ?: error("migration_worker_untrusted")
        val signature = b64(session.getString("signatureBase64"), 64)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(pin, 0))
        val transcript = "watchrss/r48/worker-cert/v1\n".toByteArray() + CloudMigrationJcs.bytes(cert)
        verifier.update(transcript, 0, transcript.size)
        require(verifier.verifySignature(signature)) { "migration_worker_signature" }
        return JSONObject(cert.toString())
    }

    /** Each call constructs a new sender context; the seq=0 context is never reused. */
    fun sealGrant(workerPublicKey: ByteArray, planHash: String, grantId: String, sessionId: String, aad: JSONObject, plaintext: ByteArray): JSONObject =
        sealGrantWithSender(workerPublicKey, planHash, grantId, sessionId, aad, plaintext, null)

    internal fun sealGrantWithSender(workerPublicKey: ByteArray, planHash: String, grantId: String, sessionId: String, aad: JSONObject, plaintext: ByteArray, senderPrivate: ByteArray?): JSONObject {
        require(workerPublicKey.size == 32)
        uuid(sessionId)
        val id = uuid(grantId)
        val rawId = ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
        val info = "watchrss/r48/grant/v1\n".toByteArray() + hex(hash(planHash)) + rawId + sessionId.toByteArray()
        val hpke = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)
        val receiver = X25519PublicKeyParameters(workerPublicKey, 0)
        val context = if (senderPrivate == null) hpke.setupBaseS(receiver, info) else {
            val privateKey = X25519PrivateKeyParameters(senderPrivate, 0)
            hpke.setupBaseS(receiver, info, AsymmetricCipherKeyPair(privateKey.generatePublicKey(), privateKey))
        }
        val ciphertext = context.seal(CloudMigrationJcs.bytes(aad), plaintext)
        return JSONObject().put("suite", SUITE).put("encBase64", encode(context.encapsulation)).put("ciphertextBase64", encode(ciphertext))
    }

    fun objectEnvelope(accountKey: ByteArray, outputRoot: ByteArray, planId: String): JSONObject =
        objectEnvelopeWithNonce(accountKey, outputRoot, planId, randomBytes(16), randomBytes(12))

    internal fun objectEnvelopeWithNonce(accountKey: ByteArray, outputRoot: ByteArray, planId: String, salt: ByteArray, nonce: ByteArray): JSONObject {
        uuid(planId); require(accountKey.size == 32 && outputRoot.size == 32 && salt.size == 16 && nonce.size == 12)
        val scope = "migration:$planId"
        val key = CloudSnapshotCodec.hkdfSha256(accountKey, salt, "watchrss/cloud/object-key/v1".toByteArray(), 32)
        val ciphertext = try { gcm(Cipher.ENCRYPT_MODE, key, nonce, "watchrss-object-key-v1:$scope".toByteArray(), outputRoot) } finally { key.fill(0) }
        return JSONObject().put("version", 1).put("scope", scope).put("saltBase64", encode(salt)).put("nonceBase64", encode(nonce)).put("ciphertextBase64", encode(ciphertext))
    }

    fun openReceipt(root: ByteArray, planHash: String, aad: JSONObject, blob: ByteArray, expectedHash: String, expectedBytes: Long): JSONObject {
        require(root.size == 32 && blob.size.toLong() == expectedBytes && blob.size >= 44 && CloudSnapshotCodec.sha256(blob) == hash(expectedHash)) { "migration_receipt_integrity" }
        val key = CloudSnapshotCodec.hkdfSha256(root, blob.copyOfRange(0,16), "watchrss/r48/receipt/v1".toByteArray() + hex(hash(planHash)), 32)
        val plain = try { gcm(Cipher.DECRYPT_MODE,key,blob.copyOfRange(16,28),CloudMigrationJcs.bytes(aad),blob.copyOfRange(28,blob.size)) } finally { key.fill(0) }
        return try { CloudRawDocument.parse(plain).json() } finally { plain.fill(0) }
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    companion object {
        const val SUITE = "HPKE-0020-0001-0002"
        fun exact(value: JSONObject, vararg fields: String) { require(value.keys().asSequence().toSet() == fields.toSet()) { "migration_closed_schema" } }
        fun integer(value: JSONObject, name: String): Long {
            require(value.get(name) is Number) { "migration_integer_type" }
            val number = BigDecimal(value.get(name).toString()); require(number.signum() >= 0 && number <= BigDecimal("9007199254740991"))
            return number.longValueExact()
        }
        fun hash(value: String): String = value.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        fun uuid(value: String): UUID = UUID.fromString(value).also { require(it.toString() == value && value.length == 36) }
        fun b64(value: String, size: Int): ByteArray = Base64.getDecoder().decode(value).also { require(it.size == size && encode(it) == value) }
        fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
        fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun transformKey(value: JSONObject): String = value.getString("id") + ":" + value.getInt("version") + ":" + value.getString("codeSha256")
        private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, value: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(value)
        }
    }
}
