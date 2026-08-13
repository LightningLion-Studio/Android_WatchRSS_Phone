package com.lightningstudio.watchrss.phone.connection.ip

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class LocalInterfaceAddress(
    val interfaceName: String,
    val address: String,
    val wifiNetwork: Boolean
)

internal object IpEndpointClassifier {
    private val excludedPrefixes = listOf(
        "rmnet", "ccmni", "pdp", "v4-rmnet", "tun", "tap", "wg", "dummy"
    )

    fun classify(value: LocalInterfaceAddress): IpTransportKind? {
        val name = value.interfaceName.lowercase(Locale.US)
        if (excludedPrefixes.any(name::startsWith)) return null
        if (name.contains("bnep") || name.contains("bt-pan") || name.startsWith("pan")) {
            return IpTransportKind.BLUETOOTH_BRIDGE
        }
        if (
            name.contains("softap") || name.contains("swlan") || name.startsWith("ap")
        ) {
            return IpTransportKind.PHONE_HOTSPOT
        }
        if (value.wifiNetwork || name.startsWith("wlan") || name.startsWith("wifi")) {
            return IpTransportKind.WIFI_LAN
        }
        return IpTransportKind.UNKNOWN_LOCAL
    }

    fun candidates(addresses: List<LocalInterfaceAddress>): List<IpEndpointCandidate> =
        addresses.mapNotNull { value ->
            val kind = classify(value) ?: return@mapNotNull null
            IpEndpointCandidate(
                endpointId = stableEndpointId(value.interfaceName, value.address, kind),
                address = value.address,
                transportKind = kind
            )
        }.distinctBy { it.address }
            .sortedWith(compareByDescending<IpEndpointCandidate> { it.priority }.thenBy { it.address })

    private fun stableEndpointId(
        interfaceName: String,
        address: String,
        kind: IpTransportKind
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "$interfaceName|$address|${kind.wireName}".toByteArray(Charsets.UTF_8)
        )
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

internal class IpEndpointProvider(
    context: Context,
    private val serverDeviceId: String,
    private val portProvider: () -> Int
) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val random = SecureRandom()
    private val challengeStore = IpChallengeStore()

    private var epoch = 1L
    private var lastCandidateSignature = ""

    @Synchronized
    fun issueDescriptor(
        expectedWatchDeviceId: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): IpEndpointDescriptor {
        val endpoints = IpEndpointClassifier.candidates(snapshotAddresses())
        val signature = endpoints.joinToString("|") {
            "${it.address},${it.transportKind.wireName},${it.priority}"
        }
        if (lastCandidateSignature.isNotEmpty() && signature != lastCandidateSignature) epoch += 1
        lastCandidateSignature = signature
        val challengeId = UUID.randomUUID().toString()
        val challengeSecret = randomBytes(32)
        val expiresAt = nowMillis + IpSyncProtocol.CHALLENGE_TTL_MS
        val unsigned = IpEndpointDescriptor(
            version = IpSyncProtocol.VERSION,
            serverDeviceId = serverDeviceId,
            epoch = epoch,
            expiresAt = expiresAt,
            port = portProvider(),
            endpoints = endpoints,
            challengeId = challengeId,
            challengeSecret = challengeSecret,
            hmac = ""
        )
        val descriptor = unsigned.copy(
            hmac = IpSyncProtocol.hmac(challengeSecret, unsigned.canonicalPayload())
        )
        challengeStore.register(PendingIpChallenge(
            descriptor = descriptor,
            expectedWatchDeviceId = expectedWatchDeviceId?.takeIf(String::isNotBlank),
            expiresAt = expiresAt
        ), nowMillis)
        return descriptor
    }

    fun consumeChallenge(hello: IpHello, nowMillis: Long = System.currentTimeMillis()): IpEndpointDescriptor? {
        return challengeStore.consume(hello, nowMillis)
    }

    fun deviceIdHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(serverDeviceId.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun snapshotAddresses(): List<LocalInterfaceAddress> {
        val wifiInterfaceNames = connectivityManager?.allNetworks.orEmpty().mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) return@mapNotNull null
            connectivityManager.getLinkProperties(network)?.interfaceName
        }.toSet()

        val result = mutableListOf<LocalInterfaceAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!runCatching { networkInterface.isUp }.getOrDefault(false) || networkInterface.isLoopback) {
                continue
            }
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (
                    address is Inet4Address && !address.isLoopbackAddress &&
                    address.isSiteLocalAddress && !address.isLinkLocalAddress
                ) {
                    val hostAddress = address.hostAddress ?: continue
                    result += LocalInterfaceAddress(
                        interfaceName = networkInterface.name,
                        address = hostAddress,
                        wifiNetwork = networkInterface.name in wifiInterfaceNames
                    )
                }
            }
        }
        return result
    }

    private fun randomBytes(size: Int): String = ByteArray(size).also(random::nextBytes).let {
        Base64.getUrlEncoder().encodeToString(it)
    }
}

internal data class PendingIpChallenge(
    val descriptor: IpEndpointDescriptor,
    val expectedWatchDeviceId: String?,
    val expiresAt: Long
)

internal class IpChallengeStore {
    private val challenges = ConcurrentHashMap<String, PendingIpChallenge>()

    fun register(challenge: PendingIpChallenge, nowMillis: Long = System.currentTimeMillis()) {
        challenges.entries.removeIf { it.value.expiresAt < nowMillis }
        challenges[challenge.descriptor.challengeId] = challenge
    }

    fun consume(hello: IpHello, nowMillis: Long = System.currentTimeMillis()): IpEndpointDescriptor? {
        val pending = challenges[hello.challengeId] ?: return null
        if (pending.expiresAt < nowMillis ||
            pending.expectedWatchDeviceId?.let { it != hello.watchDeviceId } == true ||
            hello.endpointEpoch != pending.descriptor.epoch ||
            !IpSyncProtocol.constantTimeEquals(
                IpSyncProtocol.hmac(pending.descriptor.challengeSecret, hello.canonicalPayload()),
                hello.hmac
            )
        ) return null
        return if (challenges.remove(hello.challengeId, pending)) pending.descriptor else null
    }
}
