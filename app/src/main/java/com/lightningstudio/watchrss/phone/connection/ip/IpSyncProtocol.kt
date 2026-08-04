package com.lightningstudio.watchrss.phone.connection.ip

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class IpTransportKind(val wireName: String, val priority: Int) {
    WIFI_LAN("wifiLan", 300),
    PHONE_HOTSPOT("phoneHotspot", 250),
    BLUETOOTH_BRIDGE("bluetoothBridge", 100),
    UNKNOWN_LOCAL("unknownLocal", 50);

    companion object {
        fun fromWireName(value: String): IpTransportKind =
            entries.firstOrNull { it.wireName == value } ?: UNKNOWN_LOCAL
    }
}

internal data class IpEndpointCandidate(
    val endpointId: String,
    val address: String,
    val family: String = "ipv4",
    val transportKind: IpTransportKind,
    val priority: Int = transportKind.priority
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("endpointId", endpointId)
        put("address", address)
        put("family", family)
        put("transportKind", transportKind.wireName)
        put("priority", priority)
    }

    companion object {
        fun fromJson(json: JSONObject): IpEndpointCandidate = IpEndpointCandidate(
            endpointId = json.getString("endpointId"),
            address = json.getString("address"),
            family = json.optString("family", "ipv4"),
            transportKind = IpTransportKind.fromWireName(json.optString("transportKind")),
            priority = json.optInt(
                "priority",
                IpTransportKind.fromWireName(json.optString("transportKind")).priority
            )
        )
    }
}

internal data class IpEndpointDescriptor(
    val version: Int,
    val serverDeviceId: String,
    val epoch: Long,
    val expiresAt: Long,
    val port: Int,
    val endpoints: List<IpEndpointCandidate>,
    val nonce: String,
    val authToken: String,
    val hmac: String
) {
    fun toJson(includeHmac: Boolean = true): JSONObject = JSONObject().apply {
        put("version", version)
        put("serverDeviceId", serverDeviceId)
        put("epoch", epoch)
        put("expiresAt", expiresAt)
        put("port", port)
        put("endpoints", JSONArray().also { array -> endpoints.forEach { array.put(it.toJson()) } })
        put("nonce", nonce)
        put("authToken", authToken)
        if (includeHmac) put("hmac", hmac)
    }

    fun toBleJson(): JSONObject = JSONObject().apply {
        put("v", version)
        put("id", serverDeviceId)
        put("e", epoch)
        put("x", expiresAt)
        put("p", port)
        put("a", JSONArray().also { array ->
            endpoints.forEach { endpoint ->
                array.put(
                    JSONArray()
                        .put(endpoint.endpointId)
                        .put(endpoint.address)
                        .put(endpoint.transportKind.wireName)
                        .put(endpoint.priority)
                )
            }
        })
        put("n", nonce)
        put("k", authToken)
        put("h", hmac)
    }

    fun canonicalPayload(): String = buildString {
        append(version).append('|')
        append(serverDeviceId).append('|')
        append(epoch).append('|')
        append(expiresAt).append('|')
        append(port).append('|')
        endpoints.sortedWith(
            compareByDescending<IpEndpointCandidate> { it.priority }
                .thenBy { it.endpointId }
        ).forEach { endpoint ->
            append(endpoint.endpointId).append(',')
            append(endpoint.address).append(',')
            append(endpoint.family).append(',')
            append(endpoint.transportKind.wireName).append(',')
            append(endpoint.priority).append(';')
        }
        append('|').append(nonce).append('|').append(authToken)
    }

    fun verify(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (version != IpSyncProtocol.VERSION || expiresAt < nowMillis || endpoints.isEmpty()) {
            return false
        }
        val expected = IpSyncProtocol.hmac(authToken, canonicalPayload())
        return IpSyncProtocol.constantTimeEquals(expected, hmac)
    }

    companion object {
        fun fromJson(json: JSONObject): IpEndpointDescriptor {
            val endpointsJson = json.optJSONArray("endpoints") ?: json.optJSONArray("a") ?: JSONArray()
            val endpoints = buildList {
                for (index in 0 until endpointsJson.length()) {
                    endpointsJson.optJSONObject(index)?.let { candidate ->
                        runCatching { add(IpEndpointCandidate.fromJson(candidate)) }
                    } ?: endpointsJson.optJSONArray(index)?.let { compact ->
                        val kind = IpTransportKind.fromWireName(compact.optString(2))
                        if (compact.length() >= 4) {
                            add(
                                IpEndpointCandidate(
                                    endpointId = compact.optString(0),
                                    address = compact.optString(1),
                                    transportKind = kind,
                                    priority = compact.optInt(3, kind.priority)
                                )
                            )
                        }
                    }
                }
            }
            return IpEndpointDescriptor(
                version = json.optInt("version", json.optInt("v")),
                serverDeviceId = json.optString("serverDeviceId", json.optString("id")),
                epoch = json.optLong("epoch", json.optLong("e")),
                expiresAt = json.optLong("expiresAt", json.optLong("x")),
                port = json.optInt("port", json.optInt("p")),
                endpoints = endpoints,
                nonce = json.optString("nonce", json.optString("n")),
                authToken = json.optString("authToken", json.optString("k")),
                hmac = json.optString("hmac", json.optString("h"))
            )
        }
    }
}

internal data class IpHello(
    val watchDeviceId: String,
    val endpointEpoch: Long,
    val clientNonce: String,
    val resumeSessionId: String?,
    val lastAckSeq: Long,
    val hmac: String
) {
    fun canonicalPayload(): String = listOf(
        IpSyncProtocol.VERSION.toString(),
        watchDeviceId,
        endpointEpoch.toString(),
        clientNonce,
        resumeSessionId.orEmpty(),
        lastAckSeq.toString()
    ).joinToString("|")

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", IpSyncProtocol.TYPE_HELLO)
        put("version", IpSyncProtocol.VERSION)
        put("watchDeviceId", watchDeviceId)
        put("endpointEpoch", endpointEpoch)
        put("clientNonce", clientNonce)
        resumeSessionId?.let { put("resumeSessionId", it) }
        put("lastAckSeq", lastAckSeq)
        put("hmac", hmac)
    }

    companion object {
        fun fromJson(json: JSONObject): IpHello = IpHello(
            watchDeviceId = json.optString("watchDeviceId"),
            endpointEpoch = json.optLong("endpointEpoch"),
            clientNonce = json.optString("clientNonce"),
            resumeSessionId = json.optString("resumeSessionId").takeIf { it.isNotBlank() },
            lastAckSeq = json.optLong("lastAckSeq"),
            hmac = json.optString("hmac")
        )
    }
}

internal data class IpHelloAck(
    val serverDeviceId: String,
    val sessionId: String,
    val routeKind: IpTransportKind,
    val acceptedResumeSeq: Long,
    val serverNonce: String,
    val hmac: String
) {
    fun canonicalPayload(): String = listOf(
        IpSyncProtocol.VERSION.toString(),
        serverDeviceId,
        sessionId,
        routeKind.wireName,
        acceptedResumeSeq.toString(),
        serverNonce
    ).joinToString("|")

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", IpSyncProtocol.TYPE_HELLO_ACK)
        put("version", IpSyncProtocol.VERSION)
        put("serverDeviceId", serverDeviceId)
        put("sessionId", sessionId)
        put("routeKind", routeKind.wireName)
        put("acceptedResumeSeq", acceptedResumeSeq)
        put("serverNonce", serverNonce)
        put("hmac", hmac)
    }
}

internal object IpSyncProtocol {
    const val VERSION = 1
    const val TYPE_HELLO = "hello"
    const val TYPE_HELLO_ACK = "helloAck"
    const val TYPE_ERROR = "error"
    const val SERVICE_TYPE = "_watchrss-sync._tcp."
    const val CONNECT_TIMEOUT_MS = 2_000L
    const val HANDSHAKE_TIMEOUT_MS = 2_000L
    const val FAILED_ENDPOINT_COOLDOWN_MS = 10_000L
    const val DESCRIPTOR_TTL_MS = 120_000L
    val BLE_DISCOVERY_SERVICE_UUID: UUID =
        UUID.fromString("7e57d001-1f7d-4f0b-9f3d-2d7d3a65d001")
    val BLE_ENDPOINT_CHARACTERISTIC_UUID: UUID =
        UUID.fromString("7e57d001-1f7d-4f0b-9f3d-2d7d3a65d002")

    fun hmac(base64Key: String, payload: String): String {
        val key = Base64.getUrlDecoder().decode(base64Key)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getUrlEncoder().encodeToString(
            mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        )
    }

    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(
            left.toByteArray(Charsets.US_ASCII),
            right.toByteArray(Charsets.US_ASCII)
        )
}
