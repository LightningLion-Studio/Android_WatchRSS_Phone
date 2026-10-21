package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudProtocolV2Test {
    private val vectors = JSONObject(requireNotNull(javaClass.getResource("/cloud/v2-vectors.json")).readText())
    private val inputs = vectors.getJSONObject("inputs")
    private val user = inputs.getString("userId")
    private val epoch = inputs.getString("libraryEpoch")
    private fun key(v: Int) = inputs.getString("K${v}_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun commitments() = (1..3).map { CloudKeyCommitment(it, vectors.getJSONObject("commit").getString("$it"),
        if (it == 1) null else vectors.getJSONObject("rotationProof").getString("$it")) }
    @Test fun r8LegacyCommitmentMigrationRequiresExistingDeviceProofAndCannotClaimAResetFloor() {
        val recipient=CloudKeyEnvelopeCodec.generateP256KeyPair();val k=ByteArray(32){4};val device="phone"
        val envelope=CloudKeyEnvelopeCodec.createDeviceEnvelope(k,"owner",device,recipient.public,1)
        val registered=RegisteredCloudDevice(device,"phone","Phone",recipient.public.encoded.base64().trimEnd('='),1,0,null)
        val bootstrap=CloudBootstrap(CloudMemberState("free",true,true,true,1,0,0,30,null,null),listOf(registered),
            listOf(StoredCloudKeyEnvelope("e","device",device,envelope)),"epoch")
        fun authorized(b:CloudBootstrap, keys:Map<Int,ByteArray> = mapOf(1 to k)) = legacyMigrationAuthorized(b,device,recipient.public.encoded.base64(),keys) {
            CloudKeyEnvelopeCodec.unwrapDeviceEnvelope(it,"owner",device,recipient.private)
        }
        assertTrue(authorized(bootstrap)) // same DER, padded local SPKI and legacy unpadded server SPKI.
        assertFalse(authorized(bootstrap.copy(devices=emptyList(),keyEnvelopes=emptyList(),keyVersionFloor=1)))
        assertFalse(authorized(bootstrap.copy(keyVersionFloor=1)))
        assertFalse(authorized(bootstrap.copy(keyEnvelopes=emptyList())))
        assertFalse(authorized(bootstrap.copy(devices=listOf(registered.copy(revokedAt="now")))))
        assertFalse(authorized(bootstrap, mapOf(1 to ByteArray(32))))
    }
    @Test fun allSharedVectorsMatchExactly() {
        assertEquals(vectors.getJSONObject("lp").getString("hex"), CloudProtocolV2.hex(CloudProtocolV2.lp("ab",12,"中")))
        for (v in 1..3) {
            val commit = CloudProtocolV2.commitment(key(v),user,epoch,v)
            assertEquals(vectors.getJSONObject("commit").getString("$v"), commit)
            if (v > 1) assertEquals(vectors.getJSONObject("rotationProof").getString("$v"), CloudProtocolV2.rotationProof(key(v-1),user,epoch,v,commit))
        }
        val a = vectors.getJSONObject("attest")
        assertEquals(a.getString("mac"), CloudProtocolV2.attestation(key(3),user,epoch,a.getString("deviceId"),a.getString("spki_b64").base64Bytes()))
        val p = vectors.getJSONObject("approval")
        assertEquals(p.getString("commitment"), CloudProtocolV2.approvalCommitment(p.getString("requesterSpki_b64").base64Bytes(),p.getString("requesterNonce_b64").base64Bytes()))
        val transcript = transcript(p)
        assertEquals(p.getString("transcript_hex"),CloudProtocolV2.hex(transcript))
        assertEquals(p.getString("transcriptSha256_hex"),CloudProtocolV2.hex(CloudProtocolV2.sha(transcript)))
        assertEquals(p.getString("code"),CloudProtocolV2.code(transcript))
        assertTrue(CloudProtocolV2.verify(p.getString("approverSpki_b64").base64Bytes(),transcript,p.getString("payload"),p.getString("signature_b64").base64Bytes()))
        assertNotEquals(vectors.getJSONObject("negative").getString("rotationProof3_with_wrong_prev"), vectors.getJSONObject("rotationProof").getString("3"))
    }
    private fun transcript(p: JSONObject) = CloudProtocolV2.transcript(user,epoch,p.getString("requestId"),p.getString("requesterDeviceId"),p.getString("requesterSpki_b64").base64Bytes(),
        p.getString("approverDeviceId"),p.getString("approverSpki_b64").base64Bytes(),p.getString("requesterNonce_b64").base64Bytes(),p.getString("approverNonce_b64").base64Bytes())
    @Test fun signaturesBindLiteralPayloadAndTranscript() {
        val p=vectors.getJSONObject("approval");val t=transcript(p);val keys=CloudProtocolV2.signingKey();val payload=p.getString("payload")
        val signature=CloudProtocolV2.sign(keys.private,t,payload)
        assertEquals(64,signature.size);assertTrue(CloudProtocolV2.verify(keys.public.encoded,t,payload,signature))
        assertFalse(CloudProtocolV2.verify(keys.public.encoded,t,payload+" ",signature))
        assertFalse(CloudProtocolV2.verify(keys.public.encoded,t+byteArrayOf(0),payload,signature))
        assertFalse(CloudProtocolV2.verify(keys.public.encoded,t,payload,ByteArray(64)))
    }
    @Test fun chainRejectsInjectedHigherKeysWrongEpochAndWrongPreviousMac() {
        val keys=(1..3).associateWith(::key)
        CloudKeyTrust.validate(user,epoch,0,commitments(),keys)
        fun reject(rows: List<CloudKeyCommitment> = commitments(), candidate: Map<Int,ByteArray> = keys, e: String = epoch) {
            try { CloudKeyTrust.validate(user,e,0,rows,candidate);fail("untrusted chain") } catch (_:CloudTrustException) { }
        }
        reject(candidate=keys+ (3 to ByteArray(32)))
        reject(candidate=mapOf(3 to key(3)))
        reject(e="new-epoch")
        reject(rows=commitments().map { if(it.keyVersion==3) it.copy(rotationProof=vectors.getJSONObject("negative").getString("rotationProof3_with_wrong_prev")) else it })
        reject(rows=commitments().filter { it.keyVersion!=2 })
        reject(rows=commitments()+commitments().last())
    }
    @Test fun attestationBindsDeviceAndPublicKey() {
        val spki=vectors.getJSONObject("attest").getString("spki_b64").base64Bytes()
        val a=CloudProtocolV2.attestation(key(3),user,epoch,"device",spki)
        assertNotEquals(a,CloudProtocolV2.attestation(key(3),user,epoch,"other",spki))
        assertNotEquals(a,CloudProtocolV2.attestation(key(3),user,epoch,"device",spki+byteArrayOf(0)))
    }
}
