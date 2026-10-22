package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.cloud.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException

class PairingProtocolTest {
    private val d=PairingTestData
    @Test fun miniProgramFixtureMatchesBothRelayDirectionsExactCiphertextAndR6Sas() {
        val qr=d.qr();assertEquals(d.fixture.getString("sas"),PairingWireCodec.sas(qr,d.user))
        for((direction,name,offset) in listOf(Triple("phone","phone",0),Triple("watch","watch",12))) {
            val expected=d.fixture.getJSONObject(name);val wire=PairingWireCodec.sealText(qr,direction,0,expected.getString("plaintext"),ByteArray(12){(it+offset).toByte()})
            for(field in listOf("seq","n","c"))assertEquals(expected.getJSONObject("wire").get(field),wire.get(field))
            assertEquals(JSONObject(expected.getString("plaintext")).fields(),PairingWireCodec.open(qr,direction,0,expected.getJSONObject("wire")).fields())
        }
        assertFalse(qr.toString().contains(d.fixture.getJSONObject("descriptor").getString("secret")))
    }
    @Test fun relayAuthenticatesSessionDirectionSequenceAndExactCiphertext() {
        val qr=d.qr();val wire=d.fixture.getJSONObject("phone").getJSONObject("wire")
        assertThrows(AEADBadTagException::class.java){PairingWireCodec.open(qr,"watch",0,wire)}
        assertThrows(IllegalArgumentException::class.java){PairingWireCodec.open(qr,"phone",1,wire)}
        val changed=JSONObject(wire.toString());val ciphertext=Base64.getDecoder().decode(changed.getString("c"));ciphertext[0]=(ciphertext[0].toInt() xor 1).toByte()
        changed.put("c",Base64.getEncoder().encodeToString(ciphertext));assertThrows(AEADBadTagException::class.java){PairingWireCodec.open(qr,"phone",0,changed)}
        val other=WatchPairingQr("AQEBAQEBAQEBAQEBAQEBAQ",qr.deviceId,qr.installId,qr.expiresAt,qr.secret.copyOf(),qr.claimToken)
        assertThrows(AEADBadTagException::class.java){PairingWireCodec.open(other,"phone",0,wire)}
    }
    @Test fun qrOnlyAcceptsBoundedInternetDescriptorAndRejectsEverySuppliedUrlOrOrigin() {
        val original=d.fixture.getJSONObject("descriptor")
        fun raw(json:JSONObject)="wrss-pair:"+Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
        for(field in listOf("url","origin","backendBaseUrl","host"))assertThrows(PairingException::class.java){WatchPairingQr.parse(raw(JSONObject(original.toString()).put(field,"https://evil.invalid")),d.now)}
        val patches=listOf("transport" to "bluetooth","protocol" to "watchrss-ble-setup-v1","sessionId" to "bad","secret" to "bad","claimToken" to "bad","deviceId" to "bad","expiresAt" to d.now,"expiresAt" to d.now+310001)
        for((field,value)in patches)assertThrows(PairingException::class.java){WatchPairingQr.parse(raw(JSONObject(original.toString()).put(field,value)),d.now)}
        assertThrows(PairingException::class.java){WatchPairingQr.parse("https://evil.invalid",d.now)}
    }
    @Test fun miniKeyPayloadUsesSameDeviceEnvelopeAndStrictBackendShapeAsPhoneGrant() {
        val shape=JSONObject(requireNotNull(javaClass.getResource("/pairing/approval-delivery-shape.json")).readText())
        val mini=JSONObject(d.fixture.getString("keyPayload"));val expected=ByteArray(32){7}
        assertArrayEquals(expected,CloudKeyEnvelopeCodec.unwrapDeviceEnvelope(mini.getJSONArray("envelopes").getJSONObject(0).getJSONObject("envelope").cloudEnvelope(),d.user,d.device,d.privateKey))
        val grant=d.grant();val payload=JSONObject(grant.payload)
        assertEquals(shape.getJSONObject("payload").getJSONArray("required").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() },payload.keys().asSequence().toSet())
        assertEquals(mini.getJSONObject("attestation").fields(),payload.getJSONObject("attestation").fields())
        val row=payload.getJSONArray("envelopes").getJSONObject(0);val env=row.getJSONObject("envelope")
        assertEquals(setOf("keyVersion","envelope"),row.keys().asSequence().toSet())
        assertEquals(setOf("recipientType","recipientDeviceId","keyVersion","algorithm","wrappedKeyBase64","nonceBase64","ephemeralPublicKeySpki","createdByDeviceId"),env.keys().asSequence().toSet())
        assertEquals(d.device,env.getString("recipientDeviceId"));assertEquals("phone",env.getString("createdByDeviceId"))
        assertArrayEquals(expected,CloudKeyEnvelopeCodec.unwrapDeviceEnvelope(env.cloudEnvelope(),d.user,d.device,d.privateKey))
    }
    @Test fun r28RequiresExistingLiveSelfRegistrationAndSameDerBeforeCreatingAnyGrant() {
        val bootstrap=d.bootstrap();val watch=bootstrap.devices.single()
        for(devices in listOf(emptyList(),listOf(watch.copy(revokedAt="now")),listOf(watch.copy(watchSelfRegisteredAt=null)))) {
            assertThrows(PairingException::class.java){buildPairingCloudGrant(d.user,"phone",bootstrap.copy(devices=devices),d.qr(),d.spki,d.keys())}
        }
        val other=CloudKeyEnvelopeCodec.generateP256KeyPair().public.encoded.base64()
        assertThrows(IllegalArgumentException::class.java){buildPairingCloudGrant(d.user,"phone",bootstrap.copy(devices=listOf(watch.copy(publicKeySpki=other))),d.qr(),d.spki,d.keys())}
        val legacy=bootstrap.copy(devices=listOf(watch.copy(publicKeySpki=d.spki.trimEnd('='))))
        assertEquals(1,buildPairingCloudGrant(d.user,"phone",legacy,d.qr(),d.spki,d.keys()).keyVersion)
    }
    @Test fun grantRequiresCompleteCommittedChainAndChecksEscapedFinalRelaySizeBeforeWrites() {
        assertThrows(IllegalArgumentException::class.java){buildPairingCloudGrant(d.user,"phone",d.bootstrap(2),d.qr(),d.spki,d.keys())}
        assertThrows(CloudTrustException::class.java){buildPairingCloudGrant(d.user,"phone",d.bootstrap(2),d.qr(),d.spki,mapOf(1 to ByteArray(32){7},2 to ByteArray(32){9}))}
        assertThrows(PairingException::class.java){buildPairingCloudGrant(d.user,"phone",d.bootstrap(50),d.qr(),d.spki,d.keys(50))}
        val message=JSONObject().put("action","key").put("payload","🙂\"".repeat(2100))
        assertTrue(message.getString("payload").toByteArray().size<14*1024)
        assertTrue(message.toString().toByteArray().size+16>PairingWireCodec.MAX_CIPHERTEXT_BYTES)
        assertThrows(PairingException::class.java){PairingWireCodec.checkMessage(d.qr(),message)}
    }
    @Test fun relayLimitsUseDecodedCiphertextCanonicalPaddingAndSequenceFifteen() {
        fun wire(size:Int)=JSONObject().put("seq",15).put("n",Base64.getEncoder().encodeToString(ByteArray(12))).put("c",Base64.getEncoder().encodeToString(ByteArray(size)))
        PairingWireCodec.validate(wire(12240),15)
        assertThrows(PairingException::class.java){PairingWireCodec.validate(wire(12241),15)}
        assertThrows(IllegalArgumentException::class.java){PairingWireCodec.validate(wire(16).apply {put("c",getString("c").trimEnd('='))},15)}
        val zero=WatchPairingQr("CAgICAgICAgICAgICAgICA",d.device,d.install,d.now+1000,ByteArray(32),"a".repeat(64))
        PairingWireCodec.seal(zero,"phone",0,JSONObject());zero.destroy()
        assertTrue(zero.secret.all {it==0.toByte()});assertThrows(IllegalArgumentException::class.java){PairingWireCodec.seal(zero,"phone",0,JSONObject())}
    }
}

private fun JSONObject.fields():Map<String,Any> = keys().asSequence().associateWith {get(it)}
