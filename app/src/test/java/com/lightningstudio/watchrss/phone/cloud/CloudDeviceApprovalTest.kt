package com.lightningstudio.watchrss.phone.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudDeviceApprovalTest {
    @Test
    fun `approval registration follows highest cloud key version`() {
        val bootstrap = bootstrap(
            envelopes = listOf(envelope("recovery", null, 1), envelope("device", "old", 3))
        )

        assertEquals(3, deviceApprovalKeyVersion(bootstrap))
    }

    @Test
    fun `approval accepts only envelopes addressed to current device in version order`() {
        val bootstrap = bootstrap(
            envelopes = listOf(
                envelope("device", "new-phone", 3),
                envelope("recovery", null, 1),
                envelope("device", "old-phone", 2),
                envelope("device", "new-phone", 1)
            )
        )

        assertEquals(
            listOf(1, 3),
            deviceApprovalEnvelopes(bootstrap, "new-phone").map { it.envelope.keyVersion }
        )
    }

    private fun bootstrap(envelopes: List<StoredCloudKeyEnvelope>) = CloudBootstrap(
        member = CloudMemberState("member", true, true, true, 1, 0, 0, 30, null, null),
        devices = emptyList(),
        keyEnvelopes = envelopes
    )

    private fun envelope(type: String, deviceId: String?, version: Int) =
        StoredCloudKeyEnvelope(
            id = "$type-$deviceId-$version",
            recipientType = type,
            recipientDeviceId = deviceId,
            envelope = CloudKeyEnvelope("test", version, "wrapped", "nonce", null)
        )
}
