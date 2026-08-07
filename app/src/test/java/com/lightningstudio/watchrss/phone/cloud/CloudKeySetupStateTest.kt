package com.lightningstudio.watchrss.phone.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudKeySetupStateTest {
    @Test
    fun `completed activation is idempotent for the current device and key version`() {
        assertTrue(
            isCloudKeySetupComplete(
                envelopes = listOf(
                    envelope("recovery", null, 1),
                    envelope("device", "phone-1", 1)
                ),
                devices = listOf(device("phone-1", 1)),
                deviceId = "phone-1",
                keyVersion = 1
            )
        )
    }

    @Test
    fun `recovery envelope alone is not treated as completed activation`() {
        assertFalse(
            isCloudKeySetupComplete(
                envelopes = listOf(envelope("recovery", null, 1)),
                devices = listOf(device("phone-1", 1)),
                deviceId = "phone-1",
                keyVersion = 1
            )
        )
    }

    private fun envelope(
        recipientType: String,
        recipientDeviceId: String?,
        keyVersion: Int
    ) = StoredCloudKeyEnvelope(
        id = "$recipientType-$keyVersion",
        recipientType = recipientType,
        recipientDeviceId = recipientDeviceId,
        envelope = CloudKeyEnvelope(
            algorithm = "test",
            keyVersion = keyVersion,
            wrappedKeyBase64 = "wrapped",
            nonceBase64 = "nonce"
        )
    )

    private fun device(deviceId: String, keyVersion: Int) = RegisteredCloudDevice(
        deviceId = deviceId,
        platform = "phone",
        displayName = "Test phone",
        publicKeySpki = "public-key",
        keyVersion = keyVersion,
        lastSequence = 0,
        revokedAt = null
    )
}
