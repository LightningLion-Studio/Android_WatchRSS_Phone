package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudMigrationCryptoTest {
    private fun fixture(name: String) = JSONObject(requireNotNull(javaClass.getResource("/r48/$name.json")).readText())
    private fun hex(s: String) = CloudMigrationCrypto.hex(s)
    private val crypto = CloudMigrationCrypto()

    @Test fun jcsProductionCodecMatchesSharedPlanAndEveryBoundary() {
        val v = fixture("r48-plan-grant")
        assertEquals(v.getString("canonicalPlanUtf8"), CloudMigrationJcs.canonical(v.getJSONObject("plan").toString()))
        assertEquals(v.getString("planHash"), CloudMigrationJcs.hash(v.getJSONObject("plan")))
        val samples = v.getJSONArray("jcsSamples")
        for (i in 0 until samples.length()) { val s=samples.getJSONObject(i); assertEquals(s.getString("canonicalUtf8"),CloudMigrationJcs.canonical(s.getJSONObject("value").toString())) }
        val rejected = v.getJSONArray("rejectedJcsJson")
        for (i in 0 until rejected.length()) assertThrows(Exception::class.java) { CloudMigrationJcs.canonical(rejected.getString(i)) }
    }

    @Test fun finalLocalDetailsDomainOrderingAndClosedSchemaMatchTheLeadVectors() {
        val v=fixture("r48-local-details")
        val cases=v.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i)
            assertEquals(c.getString("canonicalUtf8"),CloudMigrationJcs.canonical(c.getJSONObject("details").toString()))
            assertEquals(c.getString("commitment"),CloudMigrationPlanBuilder.detailsCommitment(c.getJSONObject("details")))
            if(c.has("inputObjects"))assertEquals(c.getString("commitment"),CloudMigrationPlanBuilder.detailsCommitment(CloudMigrationPlanBuilder.detailsFromGrant(c.getJSONArray("inputObjects"),c.getJSONArray("invariants"))))
        }
        val invalid=v.getJSONArray("rejectedDetails")
        for(i in 0 until invalid.length())assertThrows(Exception::class.java){CloudMigrationPlanBuilder.detailsCommitment(invalid.getJSONObject(i).getJSONObject("details"))}
    }
    @Test fun hpkeProductionSenderMatchesAllGrantBytesAndRejectsZeroDh() {
        val v=fixture("r48-plan-grant")
        val aad=v.getJSONObject("grantAad")
        val sealed=crypto.sealGrantWithSender(CloudMigrationCrypto.b64(fixture("r48-worker-cert").getJSONObject("certificate").getString("pubKey"),32),v.getString("planHash"),aad.getString("grantId"),aad.getString("sessionId"),aad,hex(v.getString("grantPlaintextHex")),hex(v.getString("senderPrivateKeyHex")))
        assertEquals(v.getJSONObject("sealedGrant").toString(),sealed.toString())
        assertThrows(Exception::class.java) { crypto.sealGrant(ByteArray(32),v.getString("planHash"),aad.getString("grantId"),aad.getString("sessionId"),aad,byteArrayOf(1)) }
        val second=crypto.sealGrant(CloudMigrationCrypto.b64(fixture("r48-worker-cert").getJSONObject("certificate").getString("pubKey"),32),v.getString("planHash"),aad.getString("grantId"),aad.getString("sessionId"),aad,byteArrayOf(1))
        assertNotEquals(sealed.getString("encBase64"),second.getString("encBase64"))
    }

    @Test fun selectedBouncyCastleHpkePrimitiveAlsoPassesTheCfrgOfficialSuiteKat() {
        val v=fixture("r48-hpke-rfc9180-reference").getJSONObject("vector")
        val hpke=org.bouncycastle.crypto.hpke.HPKE(0,32,1,2)
        val privateKey=org.bouncycastle.crypto.params.X25519PrivateKeyParameters(hex(v.getString("skEm")),0)
        val sender=org.bouncycastle.crypto.AsymmetricCipherKeyPair(privateKey.generatePublicKey(),privateKey)
        val context=hpke.setupBaseS(org.bouncycastle.crypto.params.X25519PublicKeyParameters(hex(v.getString("pkRm")),0),hex(v.getString("info")),sender)
        val encryption=v.getJSONArray("encryptions").getJSONObject(0)
        assertArrayEquals(hex(v.getString("enc")),context.encapsulation)
        assertArrayEquals(hex(encryption.getString("ct")),context.seal(hex(encryption.getString("aad")),hex(encryption.getString("pt"))))
    }
    @Test fun objectKeyWriterAndReceiptReaderMatchSharedCiphertextExactly() {
        val v=fixture("r48-object-key-envelope")
        val envelope=crypto.objectEnvelopeWithNonce(hex(v.getString("accountKeyHex")),hex(v.getString("outputRootHex")),v.getJSONObject("envelope").getString("scope").removePrefix("migration:"),hex(v.getString("saltHex")),hex(v.getString("nonceHex")))
        assertEquals(v.getJSONObject("envelope").getString("ciphertextBase64"),envelope.getString("ciphertextBase64"))
        val r=fixture("r48-receipt"); val blob=hex(r.getString("blobHex"))
        val receipt=crypto.openReceipt(hex(r.getString("outputRootHex")),r.getString("planHash"),r.getJSONObject("aad"),blob,r.getString("sha256"),r.getLong("sizeBytes"))
        assertEquals(CloudMigrationJcs.canonical(r.getJSONObject("plaintext").toString()),CloudMigrationJcs.canonical(receipt.toString()))
        assertThrows(Exception::class.java){crypto.openReceipt(ByteArray(32),r.getString("planHash"),r.getJSONObject("aad"),blob,r.getString("sha256"),blob.size.toLong())}
        blob[35]=(blob[35].toInt() xor 1).toByte()
        assertThrows(Exception::class.java){crypto.openReceipt(hex(r.getString("outputRootHex")),r.getString("planHash"),r.getJSONObject("aad"),blob,r.getString("sha256"),blob.size.toLong())}
    }

    @Test fun certificatePinRevocationTimeBuildAndTransformAreAllEnforced() {
        val v=fixture("r48-worker-cert");val cert=v.getJSONObject("certificate");val pins=v.getJSONObject("pins")
        val pin=CloudMigrationCrypto.b64(pins.getString("current"),32);val next=CloudMigrationCrypto.b64(pins.getString("next"),32)
        val t=cert.getJSONArray("transforms").getJSONObject(0)
        fun trust(revoked:Set<String> = emptySet(), builds:Set<String> = setOf(cert.getString("workerBuildSha256")))=CloudWorkerTrust(pin,next,revoked,builds,setOf(CloudMigrationCrypto.transformKey(t)))
        val session=JSONObject().put("certificate",cert).put("signatureBase64",v.getString("signatureBase64")).put("signingKeyId",CloudSnapshotCodec.sha256(pin))
        crypto.verifyWorker(session,trust(),cert.getLong("notBefore")+1,t)
        assertThrows(Exception::class.java){crypto.verifyWorker(session,trust(setOf(CloudSnapshotCodec.sha256(pin))),cert.getLong("notBefore")+1,t)}
        assertThrows(Exception::class.java){crypto.verifyWorker(session,trust(),cert.getLong("expiresAt"),t)}
        assertThrows(Exception::class.java){crypto.verifyWorker(session,trust(),cert.getLong("notBefore")-1,t)}
        assertThrows(Exception::class.java){crypto.verifyWorker(session,trust(builds=emptySet()),cert.getLong("notBefore")+1,t)}
        assertThrows(Exception::class.java){crypto.verifyWorker(session,CloudWorkerTrust.disabled(),cert.getLong("notBefore")+1,t)}
        cert.put("unknown",true)
        assertThrows(Exception::class.java){crypto.verifyWorker(session,trust(),cert.getLong("notBefore")+1,t)}
    }
}
