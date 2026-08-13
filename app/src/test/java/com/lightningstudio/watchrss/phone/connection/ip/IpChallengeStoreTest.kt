package com.lightningstudio.watchrss.phone.connection.ip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class IpChallengeStoreTest {
    @Test
    fun challengeIsBoundToWatchAndConsumedOnlyOnce() {
        val descriptor = descriptor()
        val store = storeWith(descriptor, "watch-1")
        val hello = hello(descriptor, "watch-1")

        assertNotNull(store.consume(hello, NOW))
        assertNull(store.consume(hello, NOW))
    }

    @Test
    fun challengeRejectsExpiryWrongWatchAndTampering() {
        val descriptor = descriptor()

        assertNull(storeWith(descriptor, "watch-1").consume(hello(descriptor, "watch-2"), NOW))
        assertNull(storeWith(descriptor, "watch-1").consume(hello(descriptor, "watch-1"), EXPIRES + 1))
        assertNull(
            storeWith(descriptor, "watch-1").consume(
                hello(descriptor, "watch-1").copy(hmac = "tampered"),
                NOW
            )
        )
    }

    @Test
    fun concurrentConsumptionHasExactlyOneWinner() {
        val descriptor = descriptor()
        val store = storeWith(descriptor, "watch-1")
        val hello = hello(descriptor, "watch-1")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val results = (1..8).map {
            executor.submit<Boolean> {
                start.await()
                store.consume(hello, NOW) != null
            }
        }
        start.countDown()
        assertEquals(1, results.count { it.get() })
        executor.shutdownNow()
    }

    private fun storeWith(descriptor: IpEndpointDescriptor, watchId: String) =
        IpChallengeStore().apply {
            register(PendingIpChallenge(descriptor, watchId, EXPIRES), NOW)
        }

    private fun hello(descriptor: IpEndpointDescriptor, watchId: String): IpHello {
        val unsigned = IpHello(
            watchDeviceId = watchId,
            challengeId = descriptor.challengeId,
            endpointEpoch = descriptor.epoch,
            clientNonce = "client-nonce",
            resumeSessionId = null,
            lastAckSeq = 0,
            hmac = ""
        )
        return unsigned.copy(
            hmac = IpSyncProtocol.hmac(descriptor.challengeSecret, unsigned.canonicalPayload())
        )
    }

    private fun descriptor(): IpEndpointDescriptor {
        val secret = Base64.getUrlEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val unsigned = IpEndpointDescriptor(
            version = IpSyncProtocol.VERSION,
            serverDeviceId = "phone",
            epoch = 1,
            expiresAt = EXPIRES,
            port = 30_000,
            endpoints = listOf(
                IpEndpointCandidate("wifi", "192.168.1.2", transportKind = IpTransportKind.WIFI_LAN)
            ),
            challengeId = "challenge-1",
            challengeSecret = secret,
            hmac = ""
        )
        return unsigned.copy(hmac = IpSyncProtocol.hmac(secret, unsigned.canonicalPayload()))
    }

    companion object {
        private const val NOW = 1_700_000_000_000L
        private const val EXPIRES = NOW + 30_000L
    }
}
