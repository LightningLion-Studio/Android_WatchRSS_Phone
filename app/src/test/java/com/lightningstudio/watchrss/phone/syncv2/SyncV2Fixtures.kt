package com.lightningstudio.watchrss.phone.syncv2

import org.json.JSONObject
import java.io.File

internal object SyncV2Fixtures {
    fun vector(name: String) = JSONObject(requireNotNull(javaClass.classLoader!!.getResourceAsStream("syncv2/$name.json")).bufferedReader().use{it.readText()})
    fun identity(): SyncV2Identity {
        val v=vector("identity-bootstrap"); val context=v.getJSONObject("context")
        return SyncV2Identity.recover(v.getJSONObject("capsule"),v.getJSONObject("descriptor"),SyncV2Codec.unhex(v.getString("wrapKeyHex"),32),context.getString("accountId"),context.getJSONObject("scope"),v.getJSONObject("request").getString("descriptorOid"))
    }
    val localKey=ByteArray(32){0x55}
    fun envelope(): JSONObject {
        val c=vector("commit").getJSONArray("cases").getJSONObject(0)
        return JSONObject().put("body",c.getJSONObject("body")).put("signature",c.getJSONObject("expected").getString("signature"))
    }
    fun window(): JSONObject = JSONObject(vector("rc6-contracts").getJSONObject("window").toString())
    fun observationHeaders():List<JSONObject> {
        val v=vector("rollback")
        return listOf("rollbackCommit","currentCommit","targetCommit").map { name ->
            val c=v.getJSONObject(name)
            JSONObject().put("body",c.getJSONObject("body")).put("signature",c.getJSONObject("expected").getString("signature"))
        }
    }
    fun signSyntheticHeader(body:JSONObject):JSONObject {
        val curve=org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256r1")
        val signer=org.bouncycastle.crypto.signers.ECDSASigner(org.bouncycastle.crypto.signers.HMacDSAKCalculator(org.bouncycastle.crypto.digests.SHA256Digest()))
        signer.init(true,org.bouncycastle.crypto.params.ECPrivateKeyParameters(java.math.BigInteger.ONE,org.bouncycastle.crypto.params.ECDomainParameters(curve.curve,curve.g,curve.n,curve.h)))
        val oid=SyncV2Codec.oid("commit",body)
        val pair=signer.generateSignature(SyncV2Codec.sha(SyncV2Codec.lp(SyncV2Codec.utf8("wrss.sync.v2/commit-sign"),SyncV2Codec.utf8(oid))))
        if(pair[1]>curve.n.shiftRight(1))pair[1]=curve.n-pair[1]
        fun bytes(n:java.math.BigInteger)=n.toByteArray().takeLast(32).toByteArray().let{ByteArray(32-it.size)+it}
        return JSONObject().put("body",body).put("signature",SyncV2Codec.b64(bytes(pair[0])+bytes(pair[1])))
    }
    fun evidence(name: String,body: JSONObject) {
        System.getenv("WRSS_P1_EVIDENCE_DIR")?.let { dir -> File(dir).mkdirs(); File(dir,name).writeText(body.toString(2)+"\n") }
    }
}
