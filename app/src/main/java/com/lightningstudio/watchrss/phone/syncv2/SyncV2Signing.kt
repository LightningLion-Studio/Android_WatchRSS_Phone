package com.lightningstudio.watchrss.phone.syncv2

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.json.JSONObject
import java.math.BigInteger

/** Fixed independent P256 key. Never uses the existing ECDH key or content key. */
internal class SyncV2Signing private constructor(private val material: ByteArray, val deviceId: String, val keyId: String):AutoCloseable {
    @Volatile private var closed=false
    val publicHex: String = SyncV2Codec.hex(curve.g.multiply(BigInteger(1,material)).normalize().getEncoded(false))
    override fun close(){closed=true;material.fill(0)}
    fun sign(message: ByteArray): String {
        if(closed)SyncV2Codec.fail("authentication_required")
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(BigInteger(1,material), domain))
        val (r, original) = signer.generateSignature(SyncV2Codec.sha(message))
        val s = if (original > curve.n.shiftRight(1)) curve.n - original else original
        return SyncV2Codec.b64(fixed(r) + fixed(s))
    }
    fun signed(domain: String, body: JSONObject): JSONObject = JSONObject().put("body", body).put("signature",
        sign(SyncV2Codec.lp(SyncV2Codec.utf8(domain), SyncV2Codec.utf8(SyncV2Codec.canonical(body)))))
    fun commit(body: JSONObject): JSONObject {
        SyncV2HeadPins.validateBody(body)
        if (body.getString("authorDeviceId") != deviceId) SyncV2Codec.fail("authentication_required")
        return JSONObject().put("body", body).put("signature", sign(SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/commit-sign"), SyncV2Codec.utf8(SyncV2Codec.oid("commit", body)))))
    }
    companion object {
        private val curve = SECNamedCurves.getByName("secp256r1")
        private val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        private fun fixed(n: BigInteger): ByteArray = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        internal fun fromPrivate(bytes: ByteArray, deviceId: String, keyId: String): SyncV2Signing {
            SyncV2Codec.unhex(deviceId, 16); SyncV2Codec.unhex(keyId, 16)
            val n = BigInteger(1, bytes)
            if (bytes.size != 32 || n < BigInteger.ONE || n >= curve.n) SyncV2Codec.fail("invalid_schema")
            return SyncV2Signing(bytes.copyOf(), deviceId, keyId)
        }
        internal fun randomPrivate():ByteArray {
            while(true) {
                val raw=SyncV2Codec.random(32);val n=BigInteger(1,raw)
                if(n>=BigInteger.ONE && n<curve.n)return raw
                raw.fill(0)
            }
        }
        /** Installed atomically before returning; a missing/unreadable existing record is never replaced. */
        @Synchronized fun loadOrCreate(context: Context, accountId: String, repoId: String): SyncV2Signing {
            SyncV2Codec.unhex(accountId, 16); SyncV2Codec.unhex(repoId, 16)
            val master = MasterKey.Builder(context, "watchrss-syncv2-signing-master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(context, "watchrss-syncv2-signing", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            val name = "$accountId/$repoId"
            val stored = prefs.getString(name, null)
            val value = if (stored != null) SyncV2Wire.parse(SyncV2Codec.utf8(stored)) else {
                var bytes: ByteArray
                do { bytes = SyncV2Codec.random(32) } while ((BigInteger(1, bytes) < BigInteger.ONE || BigInteger(1, bytes) >= curve.n))
                val v = JSONObject().put("privateHex", SyncV2Codec.hex(bytes)).put("deviceId", SyncV2Codec.hex(SyncV2Codec.random(16)))
                    .put("keyId", SyncV2Codec.hex(SyncV2Codec.random(16)))
                bytes.fill(0)
                if (!prefs.edit().putString(name, SyncV2Codec.canonical(v)).commit()) SyncV2Codec.fail("storage_pressure")
                v
            }
            SyncV2Codec.exact(value, setOf("privateHex", "deviceId", "keyId"))
            val bytes = SyncV2Codec.unhex(value.getString("privateHex"), 32)
            return try { fromPrivate(bytes, value.getString("deviceId"), value.getString("keyId")) } finally { bytes.fill(0) }
        }
    }
}

/** Existing approval system supplies the verified random-Device -> legacy-device mapping (C1). */
internal class SyncV2Authors(private val identity: SyncV2Identity, private val serviceTrust:SyncV2ServiceTrust?=null, private val approved: (String) -> Boolean) {
    private val keys = mutableMapOf<String, Pair<String, String>>()
    private val mappings=mutableMapOf<String,String>()
    private fun registration(binding:JSONObject):JSONObject = JSONObject().put("body",binding.getJSONObject("body")).put("bindingProof",binding.get("bindingProof")).put("possessionSignature",binding.get("possessionSignature"))
    private fun mapping(binding:JSONObject,checkApproval:Boolean):String {
        val b=binding.getJSONObject("body");val device=b.getString("deviceId")
        if(serviceTrust==null) {
            SyncV2Codec.exact(binding,setOf("body","bindingProof","possessionSignature"))
            if(checkApproval&&!approved(device))SyncV2Codec.fail("device_revoked")
            return device
        }
        SyncV2Codec.exact(binding,setOf("body","bindingProof","possessionSignature","deviceMapping"))
        val m=serviceTrust.verify("wrss.sync.v2/device-mapping",binding.getJSONObject("deviceMapping"))
        SyncV2Codec.exact(m,setOf("scope","deviceId","keyId","legacyDeviceId","approvalRevision"))
        if(SyncV2Codec.scope(m.getJSONObject("scope"))!=identity.scopeJson || m.get("deviceId")!=b.get("deviceId") || m.get("keyId")!=b.get("keyId"))SyncV2Codec.fail("authentication_required")
        val legacy=SyncV2Wire.string(m,"legacyDeviceId");SyncV2Codec.text(legacy,1024)
        if(legacy.isEmpty() || SyncV2Codec.d64(SyncV2Wire.string(m,"approvalRevision")).signum()==0 || checkApproval&&!approved(legacy))SyncV2Codec.fail("device_revoked")
        val hash=SyncV2Codec.hash(m)
        if(mappings[device]?.let{it!=hash}==true)SyncV2Codec.fail("identity_conflict")
        mappings[device]=hash;return legacy
    }
    fun accept(binding: JSONObject, revocationRevision: String) {
        val body = binding.getJSONObject("body")
        val device = SyncV2Wire.string(body, "deviceId")
        val original=registration(binding)
        identity.verifySigningBinding(original, if(serviceTrust==null)revocationRevision else body.getString("revocationRevision"))
        if(SyncV2Codec.d64(body.getString("revocationRevision"))>SyncV2Codec.d64(revocationRevision))SyncV2Codec.fail("replay_detected")
        val legacy=mapping(binding,true)
        val value = body.getString("keyId") to SyncV2Codec.hex(SyncV2Codec.decode64(body.getString("publicKey"), 65))
        if (keys[device]?.let { it != value } == true) SyncV2Codec.fail("identity_conflict")
        keys[device] = value;legacyIds[device]=legacy
    }
    private val legacyIds=mutableMapOf<String,String>()
    /** Only reloads bindings from the authenticated encrypted projection state. */
    fun restorePinned(binding:JSONObject) {
        val b=binding.getJSONObject("body");identity.verifySigningBinding(registration(binding),b.getString("revocationRevision"))
        val legacy=mapping(binding,false)
        val device=b.getString("deviceId");val value=b.getString("keyId") to SyncV2Codec.hex(SyncV2Codec.decode64(b.getString("publicKey"),65))
        if(keys[device]?.let{it!=value}==true)SyncV2Codec.fail("identity_conflict")
        keys[device]=value;legacyIds[device]=legacy
    }
    fun publicKey(device: String, keyId: String? = null, historical: Boolean = false): String {
        if (!historical && !approved(legacyIds[device]?:device)) SyncV2Codec.fail("device_revoked")
        val key = keys[device] ?: SyncV2Codec.fail("authentication_required")
        if (keyId != null && keyId != key.first) SyncV2Codec.fail("identity_conflict")
        return key.second
    }
    fun header(value: JSONObject, historical: Boolean = true): JSONObject {
        SyncV2Codec.exact(value, setOf("body", "signature"))
        val body = value.getJSONObject("body"); SyncV2HeadPins.validateBody(body)
        if (SyncV2Codec.scope(body.getJSONObject("scope")) != identity.scopeJson) SyncV2Codec.fail("scope_mismatch")
        val pub = publicKey(body.getString("authorDeviceId"), historical = historical)
        val msg = SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/commit-sign"), SyncV2Codec.utf8(SyncV2Codec.oid("commit", body)))
        if (!SyncV2Identity.verifySignature(pub, msg, value.getString("signature"))) SyncV2Codec.fail("signature_invalid")
        return body
    }
}
