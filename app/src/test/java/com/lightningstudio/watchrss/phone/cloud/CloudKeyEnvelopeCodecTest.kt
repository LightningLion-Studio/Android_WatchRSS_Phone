package com.lightningstudio.watchrss.phone.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.AEADBadTagException

class CloudKeyEnvelopeCodecTest {
    private val accountKey = ByteArray(32) { it.toByte() }

    @Test
    fun recoveryWordsUnwrapAccountKey() {
        val entropy = ByteArray(32) { (it * 3).toByte() }
        val envelope = CloudKeyEnvelopeCodec.createRecoveryEnvelope(
            accountKey = accountKey,
            userId = "user-1",
            keyVersion = 1,
            recoveryEntropy = entropy
        )
        assertArrayEquals(
            accountKey,
            CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(envelope, "user-1", entropy)
        )
    }

    @Test
    fun authorizedDeviceUnwrapsAccountKey() {
        val recipient = CloudKeyEnvelopeCodec.generateP256KeyPair()
        val envelope = CloudKeyEnvelopeCodec.createDeviceEnvelope(
            accountKey = accountKey,
            userId = "user-1",
            recipientDeviceId = "watch-1",
            recipientPublicKey = recipient.public,
            keyVersion = 2
        )
        assertArrayEquals(
            accountKey,
            CloudKeyEnvelopeCodec.unwrapDeviceEnvelope(
                envelope,
                "user-1",
                "watch-1",
                recipient.private
            )
        )
    }

    @Test(expected = AEADBadTagException::class)
    fun smsLoginForDifferentAccountCannotUnwrapRecoveryEnvelope() {
        val entropy = ByteArray(32) { (it * 5).toByte() }
        val envelope = CloudKeyEnvelopeCodec.createRecoveryEnvelope(
            accountKey,
            "account-a",
            1,
            entropy
        )
        CloudKeyEnvelopeCodec.unwrapRecoveryEnvelope(envelope, "account-b", entropy)
    }
}
