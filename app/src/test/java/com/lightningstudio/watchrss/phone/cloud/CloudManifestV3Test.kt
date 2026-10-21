package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudManifestV3Test {
    private val vector=JSONObject(requireNotNull(javaClass.getResource("/cloud/manifest-v3.json")).readText())
    private fun bytes(name:String)=vector.getString(name).chunked(2).map{it.toInt(16).toByte()}.toByteArray()
    @Test fun sharedManifestV3VectorMatchesExactBytesAndReadsIdentity() {
        val codec=CloudSnapshotCodec();val id=vector.getString("snapshotId");val key=bytes("accountKeyHex")
        val encrypted=codec.encryptManifestBytes(key,id,vector.getString("plaintextUtf8").toByteArray(),bytes("saltHex"),bytes("nonceHex"))
        assertEquals(vector.getString("encryptedManifestHex"),CloudProtocolV2.hex(encrypted))
        assertEquals(vector.getString("encryptedManifestBase64"),encrypted.base64())
        val manifest=codec.decryptManifest(key,id,encrypted)
        assertEquals(id,manifest.snapshotId);assertEquals(7,manifest.deviceSequence)
        assertEquals(vector.getString("derivedKeyHex"),CloudProtocolV2.hex(CloudSnapshotCodec.hkdfSha256(key,bytes("saltHex"),vector.getString("hkdfInfo").toByteArray(),32)))
    }
    @Test fun writerOnlyEmitsV3WhileV2RemainsReadOnlyCompatible() {
        val codec=CloudSnapshotCodec();val id=vector.getString("snapshotId");val key=bytes("accountKeyHex")
        assertEquals("WRSSCM3",codec.create(key,1,"phone",1,emptyList()).encryptedManifest.copyOfRange(0,7).toString(Charsets.US_ASCII))
        val salt=bytes("saltHex");val nonce=bytes("nonceHex");val derived=CloudSnapshotCodec.hkdfSha256(key,salt,"watchrss/cloud/manifest/v1".toByteArray(),32)
        val old="WRSSCM2".toByteArray()+salt+nonce+CloudSnapshotCodec.encryptAesGcm(derived,nonce,vector.getString("plaintextUtf8").toByteArray(),"watchrss-cloud-manifest-v2:$id".toByteArray())
        assertEquals(id,codec.decryptManifest(key,id,old).snapshotId)
        try{codec.decryptManifest(key,"other-id",bytes("encryptedManifestHex"));fail("snapshot binding bypassed")}catch(_:javax.crypto.AEADBadTagException){}
    }
}
