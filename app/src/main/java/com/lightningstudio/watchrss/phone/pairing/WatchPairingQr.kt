package com.lightningstudio.watchrss.phone.pairing

import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Secret-bearing descriptor is memory-only and deliberately has no data-class toString. */
class WatchPairingQr internal constructor(
    val sessionId: String, val deviceId: String, val installId: String, val expiresAt: Long,
    internal val secret: ByteArray, internal var claimToken: String
) {
    @Volatile internal var active: Boolean = true
    internal fun destroy() { active = false; secret.fill(0); claimToken = "" }
    override fun toString(): String = "WatchPairingQr(redacted)"
    companion object {
        const val PROTOCOL = "watchrss-online-setup-v1"
        fun parse(raw: String, now: Long = System.currentTimeMillis()): WatchPairingQr {
            try {
                require(raw.startsWith("wrss-pair:") && raw.length <= 4096)
                val encoded = raw.substring(10)
                require(encoded.matches(Regex("[A-Za-z0-9_-]+")))
                val bytes = Base64.getUrlDecoder().decode(encoded)
                require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                val allowed = setOf("protocol", "transport", "sessionId", "deviceId", "installId", "secret", "expiresAt", "claimToken")
                require(json.keys().asSequence().toSet() == allowed) // No URL/origin/backend supplied by a QR.
                require(json.getString("protocol") == PROTOCOL && json.getString("transport") == "internet")
                val id = json.getString("sessionId")
                require(id.matches(Regex("[A-Za-z0-9_-]{22}")))
                val idBytes = Base64.getUrlDecoder().decode(id)
                require(idBytes.size == 16 && Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes) == id)
                fun uuid(name: String): String = json.getString(name).also { require(UUID.fromString(it).toString().equals(it, ignoreCase = true)) }
                val device = uuid("deviceId"); val install = uuid("installId")
                val expires = json.get("expiresAt").toString().toLong()
                require(expires > now && expires - now <= 310_000 && expires <= 9_007_199_254_740_991L)
                val claim = json.getString("claimToken"); require(claim.matches(Regex("[a-f0-9]{64}")))
                val secret = Base64.getDecoder().decode(json.getString("secret"))
                if (secret.size != 32) { secret.fill(0); error("invalid") }
                return WatchPairingQr(id, device, install, expires, secret, claim)
            } catch (_: Exception) { throw PairingException("invalid_qr", "请扫描手表上未过期的联网配对码") }
        }
    }
}

class PairingException(val code: String, message: String) : IllegalStateException(message)
internal class PairingInvalidated : kotlinx.coroutines.CancellationException("配对已结束或账号/资料库已变化")
