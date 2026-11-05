package com.lightningstudio.watchrss.phone.syncv2

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

/** Receives an existing approved repo only. P1 deliberately has no repo-init operation. */
internal class SyncV2Identity private constructor(
    val accountId: String, val scopeJson: String, val descriptorOid: String,
    private val identityKey: ByteArray,
    private val creator: String, private val creatorPublicKey: String
) {
    val scope get() = JSONObject(scopeJson)
    val repoId get() = scope.getString("repoId")
    fun entity(kind: String, id: String) = SyncV2Codec.entity(identityKey, repoId, kind, id)
    fun opaque(type: String, bytes: ByteArray) = SyncV2Codec.opaque(identityKey, scope, type, bytes)
    internal fun encryptedRecord(localKey: ByteArray): ByteArray = SyncV2Codec.sealLocal(localKey, scopeJson, "identity",
        JSONObject().put("accountId", accountId).put("descriptorOid", descriptorOid).put("idKeyHex", SyncV2Codec.hex(identityKey)).put("creator",creator).put("creatorPublicKey",creatorPublicKey))


    /** P1 only accepts the descriptor-approved creator key. Other approved authors need P2 bindings. */
    fun verifyHeader(envelope: JSONObject): JSONObject {
        SyncV2Codec.exact(envelope,setOf("body","signature"))
        val body=envelope.getJSONObject("body")
        SyncV2HeadPins.validateBody(body)
        if(SyncV2Codec.scope(body.getJSONObject("scope"))!=scopeJson) SyncV2Codec.fail("scope_mismatch")
        if(body.getString("authorDeviceId")!=creator) SyncV2Codec.fail("unsupported_feature")
        val oid=SyncV2Codec.oid("commit",body)
        if(!verifySignature(creatorPublicKey,SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/commit-sign"),SyncV2Codec.utf8(oid)),envelope.getString("signature"))) SyncV2Codec.fail("signature_invalid")
        return JSONObject(body.toString())
    }

    fun signingBinding(signer: SyncV2Signing, authorizedAt: Long, revocationRevision: String): JSONObject {
        val b = JSONObject().put("version", 2).put("scope", scope).put("deviceId", signer.deviceId)
            .put("keyId", signer.keyId).put("publicKey", SyncV2Codec.b64(SyncV2Codec.unhex(signer.publicHex, 65)))
            .put("authorizedAtMillis", authorizedAt).put("revocationRevision", revocationRevision)
        val proof = mac(identityKey, "wrss.sync.v2/signing-key-bind", b)
        return JSONObject().put("body", b).put("bindingProof", proof).put("possessionSignature", signer.sign(
            SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/signing-key-possession"), SyncV2Codec.utf8(SyncV2Codec.canonical(b)), SyncV2Codec.utf8(proof))))
    }
    fun verifySigningBinding(value: JSONObject, revision: String) {
        SyncV2Codec.exact(value, setOf("body", "bindingProof", "possessionSignature"))
        val b = value.getJSONObject("body")
        SyncV2Codec.exact(b, setOf("version", "scope", "deviceId", "keyId", "publicKey", "authorizedAtMillis", "revocationRevision"))
        if (b.get("version") != 2 || SyncV2Codec.scope(b.getJSONObject("scope")) != scopeJson) SyncV2Codec.fail("scope_mismatch")
        SyncV2Codec.unhex(SyncV2Wire.string(b, "deviceId"), 16); SyncV2Codec.unhex(SyncV2Wire.string(b, "keyId"), 16)
        SyncV2Wire.uint(b, "authorizedAtMillis"); SyncV2Codec.d64(SyncV2Wire.string(b, "revocationRevision")); SyncV2Codec.d64(revision)
        val proof = SyncV2Wire.string(value, "bindingProof")
        if (b.getString("revocationRevision") != revision || !MessageDigest.isEqual(SyncV2Codec.decode64(proof, 32), SyncV2Codec.decode64(mac(identityKey, "wrss.sync.v2/signing-key-bind", b), 32))) SyncV2Codec.fail("authentication_required")
        val pub = SyncV2Codec.hex(SyncV2Codec.decode64(SyncV2Wire.string(b, "publicKey"), 65))
        if (!verifySignature(pub, SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/signing-key-possession"), SyncV2Codec.utf8(SyncV2Codec.canonical(b)), SyncV2Codec.utf8(proof)), SyncV2Wire.string(value, "possessionSignature"))) SyncV2Codec.fail("signature_invalid")
    }

    companion object {
        fun restoreInstalled(bytes:ByteArray,localKey:ByteArray,scope:String,account:String,pin:String):SyncV2Identity {
            val p=JSONObject(SyncV2Codec.openLocal(localKey,scope,"identity",bytes))
            SyncV2Codec.exact(p,setOf("accountId","descriptorOid","idKeyHex","creator","creatorPublicKey"))
            if(p.getString("accountId")!=account || p.getString("descriptorOid")!=pin)SyncV2Codec.fail("identity_conflict")
            SyncV2Codec.scope(JSONObject(scope));SyncV2Codec.unhex(p.getString("creator"),16);SyncV2Codec.unhex(p.getString("creatorPublicKey"),65)
            return SyncV2Identity(account,scope,pin,SyncV2Codec.unhex(p.getString("idKeyHex"),32),p.getString("creator"),p.getString("creatorPublicKey"))
        }
        private fun commitment(key: ByteArray) = SyncV2Codec.hex(SyncV2Codec.sha(SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/id-key-commitment"), key)))
        private fun message(domain: String, value: Any?) = SyncV2Codec.lp(SyncV2Codec.utf8(domain), SyncV2Codec.utf8(SyncV2Codec.canonical(value)))
        private fun mac(key: ByteArray, domain: String, value: Any?) = SyncV2Codec.b64(SyncV2Codec.hmac(key, message(domain, value)))
        internal fun verifySignature(publicHex: String, message: ByteArray, signature: String): Boolean = runCatching {
            val raw = SyncV2Codec.decode64(signature, 64)
            val curve = SECNamedCurves.getByName("secp256r1")
            val r = BigInteger(1, raw.copyOfRange(0, 32)); val s = BigInteger(1, raw.copyOfRange(32, 64))
            require(r > BigInteger.ZERO && r < curve.n && s > BigInteger.ZERO && s <= curve.n.shiftRight(1))
            val bytes = SyncV2Codec.unhex(publicHex, 65); require(bytes[0] == 4.toByte())
            val point = curve.curve.decodePoint(bytes); require(!point.isInfinity && point.isValid)
            ECDSASigner().run {
                init(false, ECPublicKeyParameters(point, ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)))
                verifySignature(SyncV2Codec.sha(message), r, s)
            }
        }.getOrDefault(false)
        fun verifyDescriptor(d: JSONObject, wrapKey: ByteArray, account: String, scope: JSONObject, pin: String): JSONObject {
            SyncV2Codec.exact(d, setOf("body", "proof", "signature"))
            val b = d.getJSONObject("body")
            SyncV2Codec.exact(b, setOf("version", "accountId", "scope", "creationId", "creatorDeviceId", "creatorPlatform", "wrappingKeyVersion", "creatorPublicKey", "idKeyCommitment"))
            if (b.get("version") != 2 || b.getString("creatorPlatform") !in setOf("android", "mini")) SyncV2Codec.fail("invalid_schema")
            listOf("accountId", "creationId", "creatorDeviceId").forEach { SyncV2Codec.unhex(b.getString(it), 16) }
            SyncV2Codec.unhex(b.getString("idKeyCommitment"), 32)
            for(f in listOf("accountId","creationId","creatorDeviceId","creatorPlatform","creatorPublicKey","idKeyCommitment"))if(b.get(f) !is String)SyncV2Codec.fail("invalid_schema")
            val version = b.get("wrappingKeyVersion")
            if (version !is Int && version !is Long) SyncV2Codec.fail("invalid_schema")
            if ((version as Number).toLong() !in 1..4294967295L) SyncV2Codec.fail("invalid_schema")
            if (wrapKey.size != 32 || b.getString("accountId") != account || SyncV2Codec.scope(b.getJSONObject("scope")) != SyncV2Codec.scope(scope) ||
                SyncV2Codec.oid("repo-descriptor", d) != pin || !MessageDigest.isEqual(SyncV2Codec.utf8(d.getString("proof")), SyncV2Codec.utf8(mac(wrapKey, "wrss.sync.v2/repo-bootstrap-auth", b))) ||
                !verifySignature(b.getString("creatorPublicKey"), message("wrss.sync.v2/repo-bootstrap-sign", JSONObject().put("body", b).put("proof", d.getString("proof"))), d.getString("signature"))) SyncV2Codec.fail("authentication_required")
            return b
        }
        fun recover(capsule: JSONObject, descriptor: JSONObject, wrapKey: ByteArray, account: String, scope: JSONObject, pin: String): SyncV2Identity {
            val b = verifyDescriptor(descriptor, wrapKey, account, scope, pin)
            SyncV2Codec.exact(capsule, setOf("header", "ciphertext"))
            val h = capsule.getJSONObject("header")
            val bound = JSONObject().put("version", 2).put("domain", "wrss.sync.v2/id-key-recovery").put("accountId", account)
                .put("scope", scope).put("descriptorOid", pin).put("wrappingKeyVersion", b.get("wrappingKeyVersion"))
            checkHeader(h, bound)
            return installed(unwrap(capsule, wrapKey), b, account, scope, pin)
        }
        data class Member(val deviceId: String, val platform: String, val authorized: Boolean)
        fun receive(value: JSONObject, request: JSONObject, descriptor: JSONObject, wrapKey: ByteArray, now: Long, member: Member): SyncV2Identity {
            SyncV2Codec.exact(request, setOf("body", "proof"))
            val req = request.getJSONObject("body")
            SyncV2Codec.exact(req, setOf("version", "accountId", "scope", "descriptorOid", "recipientDeviceId", "recipientPlatform", "requestId", "challenge", "wrappingKeyVersion", "expiresAtMillis"))
            for(f in listOf("accountId","descriptorOid","recipientDeviceId","recipientPlatform","requestId","challenge"))if(req.get(f) !is String)SyncV2Codec.fail("invalid_schema")
            val expires=req.get("expiresAtMillis");if(expires !is Int && expires !is Long)SyncV2Codec.fail("invalid_schema")
            if((expires as Number).toLong() !in 0..9007199254740991L)SyncV2Codec.fail("invalid_schema")
            val account = req.getString("accountId"); val scope = req.getJSONObject("scope"); val pin = req.getString("descriptorOid")
            val b = verifyDescriptor(descriptor, wrapKey, account, scope, pin)
            SyncV2Codec.unhex(req.getString("requestId"), 16); SyncV2Codec.unhex(req.getString("challenge"), 32)
            if (!member.authorized || req.get("version") != 2 || req.getString("recipientDeviceId") != member.deviceId || req.getString("recipientPlatform") != member.platform ||
                member.platform !in setOf("watch", "android", "mini", "cli") || req.get("wrappingKeyVersion") != b.get("wrappingKeyVersion") ||
                req.getLong("expiresAtMillis") <= now || req.getLong("expiresAtMillis") > now + 300000 || request.getString("proof") != mac(wrapKey, "wrss.sync.v2/id-key-request", req)) SyncV2Codec.fail("authentication_required")
            SyncV2Codec.exact(value, setOf("header", "ciphertext"))
            val expected = JSONObject(req.toString()).put("domain", "wrss.sync.v2/id-key-delivery")
            checkHeader(value.getJSONObject("header"), expected)
            return installed(unwrap(value, wrapKey), b, account, scope, pin)
        }
        private fun checkHeader(h: JSONObject, expected: JSONObject) {
            SyncV2Codec.exact(h, expected.keys().asSequence().toSet() + setOf("salt", "nonce"))
            if (expected.keys().asSequence().any { SyncV2Codec.canonical(h.get(it)) != SyncV2Codec.canonical(expected.get(it)) }) SyncV2Codec.fail("authentication_required")
        }
        private fun unwrap(v: JSONObject, wrapKey: ByteArray): ByteArray {
            if (SyncV2Codec.utf8(SyncV2Codec.canonical(v)).size > 8192) SyncV2Codec.fail("invalid_schema")
            val h = v.getJSONObject("header"); val aad = SyncV2Codec.utf8(SyncV2Codec.canonical(h))
            val key = SyncV2Codec.hkdf(wrapKey, SyncV2Codec.decode64(h.getString("salt"), 32), SyncV2Codec.lp(SyncV2Codec.utf8(h.getString("domain")), SyncV2Codec.sha(aad)))
            val p = JSONObject(SyncV2Codec.decrypt(key, SyncV2Codec.decode64(h.getString("nonce"), 12), aad, SyncV2Codec.decode64(v.getString("ciphertext"))).toString(Charsets.UTF_8))
            SyncV2Codec.exact(p, setOf("idKeyHex")); return SyncV2Codec.unhex(p.getString("idKeyHex"), 32)
        }
        private fun installed(key: ByteArray, b: JSONObject, account: String, scope: JSONObject, pin: String): SyncV2Identity {
            if (commitment(key) != b.getString("idKeyCommitment")) SyncV2Codec.fail("fingerprint_mismatch")
            return SyncV2Identity(account, SyncV2Codec.scope(scope), pin, key.copyOf(), b.getString("creatorDeviceId"), b.getString("creatorPublicKey"))
        }
    }
}
