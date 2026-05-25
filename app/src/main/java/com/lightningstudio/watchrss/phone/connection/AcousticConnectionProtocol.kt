package com.lightningstudio.watchrss.phone.connection

import org.json.JSONObject

const val ACOUSTIC_KIND_GUIDED_WIFI = "guided_wifi"
private const val ACOUSTIC_KIND_GUIDED_WIFI_COMPACT = "g"

data class GuidedWifiEnvelope(
    val ability: PhoneConnectionAbility,
    val ssid: String,
    val passphrase: String,
    val host: String,
    val port: Int,
    val token: String
)

object AcousticConnectionProtocol {
    fun buildGuidedWifi(
        ability: PhoneConnectionAbility,
        ssid: String,
        passphrase: String,
        host: String,
        port: Int,
        token: String
    ): ByteArray {
        return JSONObject().apply {
            put("k", ACOUSTIC_KIND_GUIDED_WIFI_COMPACT)
            put("a", ability.acousticCode)
            if (ssid.isNotBlank()) {
                put("s", ssid)
            }
            if (passphrase.isNotBlank()) {
                put("p", passphrase)
            }
            put("h", host)
            put("o", port)
            put("t", token)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun parseGuidedWifi(bytes: ByteArray): GuidedWifiEnvelope {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val kind = json.optString("k").ifBlank { json.optString("kind") }
        require(kind == ACOUSTIC_KIND_GUIDED_WIFI_COMPACT || kind == ACOUSTIC_KIND_GUIDED_WIFI) {
            "不是声波引导 WiFi 数据"
        }
        return GuidedWifiEnvelope(
            ability = PhoneConnectionAbility.fromPayloadValue(
                json.optString("a").ifBlank { json.getString("ability") }
            ),
            ssid = json.optString("s").ifBlank { json.optString("ssid") },
            passphrase = json.optString("p").ifBlank { json.optString("passphrase") },
            host = json.optString("h").ifBlank { json.getString("host") },
            port = if (json.has("o")) json.getInt("o") else json.getInt("port"),
            token = json.optString("t").ifBlank { json.getString("token") }
        )
    }
}
