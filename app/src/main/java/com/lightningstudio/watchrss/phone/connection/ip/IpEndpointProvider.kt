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
        if (value.wifiNetwork) return IpTransportKind.WIFI_LAN
        if (name.contains("bnep") || name.contains("bt-pan") || name.startsWith("pan")) {
            return IpTransportKind.BLUETOOTH_BRIDGE
        }
        if (
            name.contains("softap") || name.contains("swlan") || name.startsWith("ap") ||
            name.startsWith("wlan") || name.startsWith("wifi")
        ) {
            return IpTransportKind.PHONE_HOTSPOT
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
    private val authToken = randomBytes(32)
    private val nonce = randomBytes(16)

    private var epoch = 1L
    private var lastCandidateSignature = ""

    @Synchronized
    fun descriptor(nowMillis: Long = System.currentTimeMillis()): IpEndpointDescriptor {
        val endpoints = IpEndpointClassifier.candidates(snapshotAddresses())
        val signature = endpoints.joinToString("|") {
            "${it.address},${it.transportKind.wireName},${it.priority}"
        }
        if (lastCandidateSignature.isNotEmpty() && signature != lastCandidateSignature) epoch += 1
        lastCandidateSignature = signature
        val unsigned = IpEndpointDescriptor(
            version = IpSyncProtocol.VERSION,
            serverDeviceId = serverDeviceId,
            epoch = epoch,
            expiresAt = nowMillis + IpSyncProtocol.DESCRIPTOR_TTL_MS,
            port = portProvider(),
            endpoints = endpoints,
            nonce = nonce,
            authToken = authToken,
            hmac = ""
        )
        return unsigned.copy(hmac = IpSyncProtocol.hmac(authToken, unsigned.canonicalPayload()))
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
