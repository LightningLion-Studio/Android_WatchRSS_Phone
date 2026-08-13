package com.lightningstudio.watchrss.phone.connection.ip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class IpSyncProtocolTest {
    @Test
    fun syncServerPorts_fitHeyTapBluetoothProxySignedPortField() {
        assertTrue(IP_SYNC_PORT_CANDIDATES.all { it in 1..Short.MAX_VALUE })
        assertFalse(18_765 in IP_SYNC_PORT_CANDIDATES)
        assertEquals(10 * 60 * 1_000, IP_SYNC_SOCKET_READ_TIMEOUT_MS)
    }

    @Test
    fun descriptor_roundTripsCompactBlePayloadAndRejectsTampering() {
        val unsigned = descriptor(hmac = "")
        val signed = unsigned.copy(
            hmac = IpSyncProtocol.hmac(unsigned.challengeSecret, unsigned.canonicalPayload())
        )
        val encoded = signed.toBleJson().toString().toByteArray(Charsets.UTF_8)
        val decoded = IpEndpointDescriptor.fromJson(signed.toBleJson())

        assertTrue(encoded.size < 512)
        assertEquals(signed, decoded)
        assertTrue(decoded.verify(nowMillis = 1_700_000_000_000L))
        assertFalse(decoded.copy(port = decoded.port + 1).verify(1_700_000_000_000L))
        assertFalse(decoded.verify(decoded.expiresAt + 1))
    }

    @Test
    fun endpointClassifier_filtersAndPrioritizesLocalInterfaces() {
        assertNull(
            IpEndpointClassifier.classify(
                LocalInterfaceAddress("rmnet_data0", "10.0.0.2", wifiNetwork = false)
            )
        )
        val candidates = IpEndpointClassifier.candidates(
            listOf(
                LocalInterfaceAddress("bnep0", "192.168.7.1", wifiNetwork = false),
                LocalInterfaceAddress("wlan0", "192.168.1.10", wifiNetwork = true),
                LocalInterfaceAddress("swlan0", "10.42.0.1", wifiNetwork = true),
                LocalInterfaceAddress("mystery0", "172.16.0.1", wifiNetwork = false),
                LocalInterfaceAddress("wlan1", "192.168.1.10", wifiNetwork = true)
            )
        )

        assertEquals(
            listOf(
                IpTransportKind.WIFI_LAN,
                IpTransportKind.PHONE_HOTSPOT,
                IpTransportKind.BLUETOOTH_BRIDGE,
                IpTransportKind.UNKNOWN_LOCAL
            ),
            candidates.map { it.transportKind }
        )
        assertEquals(candidates.size, candidates.map { it.address }.distinct().size)
    }

    @Test
    fun routeClassification_usesActualRemoteSubnetWithoutFixedGateway() {
        val endpoints = descriptor("").endpoints
        assertEquals(
            IpTransportKind.WIFI_LAN,
            WatchIpSyncServer.routeKindForRemote("192.168.1.42", endpoints)
        )
        assertEquals(
            IpTransportKind.BLUETOOTH_BRIDGE,
            WatchIpSyncServer.routeKindForRemote("192.168.7.2", endpoints)
        )
        assertEquals(
            IpTransportKind.UNKNOWN_LOCAL,
            WatchIpSyncServer.routeKindForRemote("10.8.0.2", endpoints)
        )
    }

    private fun descriptor(hmac: String): IpEndpointDescriptor = IpEndpointDescriptor(
        version = IpSyncProtocol.VERSION,
        serverDeviceId = "phone-device",
        epoch = 7,
        expiresAt = 1_800_000_000_000L,
        port = 31_337,
        endpoints = listOf(
            IpEndpointCandidate(
                endpointId = "wifi",
                address = "192.168.1.2",
                transportKind = IpTransportKind.WIFI_LAN
            ),
            IpEndpointCandidate(
                endpointId = "bridge",
                address = "192.168.7.1",
                transportKind = IpTransportKind.BLUETOOTH_BRIDGE
            )
        ),
        challengeId = "challenge-1",
        challengeSecret = Base64.getUrlEncoder().encodeToString(ByteArray(32) { it.toByte() }),
        hmac = hmac
    )
}
