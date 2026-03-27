package com.lightningstudio.watchrss.phone.connection.guided

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.lightningstudio.watchrss.phone.acoustic.AcousticCodec
import com.lightningstudio.watchrss.phone.acoustic.AcousticPacket
import com.lightningstudio.watchrss.phone.connection.AcousticConnectionProtocol
import com.lightningstudio.watchrss.phone.connection.PhoneConnectionAbility
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GuidedSessionState(
    val ability: PhoneConnectionAbility,
    val ssid: String,
    val passphrase: String,
    val host: String,
    val port: Int,
    val token: String,
    val packet: AcousticPacket
)

class PhoneGuidedSessionManager(
    context: Context,
    private val repository: PhoneCompanionRepository
) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: GuidedSessionServer? = null

    suspend fun startSession(
        ability: PhoneConnectionAbility,
        remoteUrl: String? = null
    ): GuidedSessionState = withContext(Dispatchers.Main) {
        stopSession()

        val token = UUID.randomUUID().toString()
        val guidedServer = GuidedSessionServer(
            repository = repository,
            ability = ability,
            token = token,
            remoteUrl = remoteUrl
        )
        guidedServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        try {
            val hotspotReservation = startHotspotInternal()
            val config = hotspotReservation.softApConfiguration
            val ssid = resolveSsid(config, hotspotReservation.wifiConfiguration)
            val passphrase = resolvePassphrase(config, hotspotReservation.wifiConfiguration)
            val host = resolveHotspotHost() ?: DEFAULT_HOTSPOT_HOST
            val packet = AcousticCodec.encode(
                AcousticConnectionProtocol.buildGuidedWifi(
                    ability = ability,
                    ssid = ssid,
                    passphrase = passphrase,
                    host = host,
                    port = guidedServer.listeningPort,
                    token = token
                )
            )

            reservation = hotspotReservation
            server = guidedServer

            GuidedSessionState(
                ability = ability,
                ssid = ssid,
                passphrase = passphrase,
                host = host,
                port = guidedServer.listeningPort,
                token = token,
                packet = packet
            )
        } catch (throwable: Throwable) {
            guidedServer.stop()
            throw throwable
        }
    }

    fun stopSession() {
        server?.stop()
        server = null
        reservation?.close()
        reservation = null
    }

    private suspend fun startHotspotInternal(): WifiManager.LocalOnlyHotspotReservation {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    if (!continuation.isCompleted) {
                        continuation.resume(reservation)
                    }
                }

                override fun onFailed(reason: Int) {
                    if (!continuation.isCompleted) {
                        continuation.resumeWithException(
                            IllegalStateException("手机热点启动失败，原因码：$reason")
                        )
                    }
                }
            }

            wifiManager.startLocalOnlyHotspot(
                callback,
                Handler(Looper.getMainLooper())
            )
        }
    }

    private fun resolveSsid(
        config: SoftApConfiguration,
        wifiConfiguration: WifiConfiguration?
    ): String {
        return config.ssid
            ?: wifiConfiguration?.SSID?.trim('"')
            ?: error("无法获取手机热点 SSID")
    }

    private fun resolvePassphrase(
        config: SoftApConfiguration,
        wifiConfiguration: WifiConfiguration?
    ): String {
        return config.passphrase
            ?: wifiConfiguration?.preSharedKey?.trim('"')
            ?: error("无法获取手机热点密码")
    }

    private fun resolveHotspotHost(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }
            val lowercaseName = networkInterface.name.lowercase(Locale.US)
            if (!lowercaseName.contains("wlan") && !lowercaseName.contains("ap")) {
                continue
            }
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    private class GuidedSessionServer(
        private val repository: PhoneCompanionRepository,
        private val ability: PhoneConnectionAbility,
        private val token: String,
        private val remoteUrl: String?
    ) : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response {
            val requestToken = session.parameters["token"]?.firstOrNull()
            if (requestToken != token) {
                return jsonResponse(Response.Status.FORBIDDEN) {
                    put("success", false)
                    put("message", "令牌无效")
                }
            }

            return when (session.uri) {
                "/pullRemoteInput" -> handleRemoteInput()
                "/pushFavorites" -> handleSavedItems(session, PhoneSavedItemType.FAVORITE)
                "/pushWatchLater" -> handleSavedItems(session, PhoneSavedItemType.WATCH_LATER)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }

        private fun handleRemoteInput(): Response {
            require(ability == PhoneConnectionAbility.REMOTE_INPUT) { "当前会话不是 RSS 输入" }
            return jsonResponse(Response.Status.OK) {
                put("success", true)
                put("url", remoteUrl.orEmpty())
            }
        }

        private fun handleSavedItems(session: IHTTPSession, type: PhoneSavedItemType): Response {
            val expectedAbility = when (type) {
                PhoneSavedItemType.FAVORITE -> PhoneConnectionAbility.SYNC_FAVORITES
                PhoneSavedItemType.WATCH_LATER -> PhoneConnectionAbility.SYNC_WATCH_LATER
            }
            require(ability == expectedAbility) { "当前会话能力不匹配" }

            val bodyMap = mutableMapOf<String, String>()
            session.parseBody(bodyMap)
            val postData = bodyMap["postData"].orEmpty()
            val payload = JSONObject(postData.ifBlank { "{}" })
            val items = payload.optJSONArray("items")
                ?: return jsonResponse(Response.Status.BAD_REQUEST) {
                    put("success", false)
                    put("message", "缺少 items")
                }
            val count = kotlinx.coroutines.runBlocking {
                repository.replaceSavedItems(type, items)
            }
            return jsonResponse(Response.Status.OK) {
                put("success", true)
                put("count", count)
            }
        }

        private inline fun jsonResponse(
            status: Response.Status,
            block: JSONObject.() -> Unit
        ): Response {
            return newFixedLengthResponse(
                status,
                "application/json",
                JSONObject().apply(block).toString()
            )
        }
    }

    companion object {
        private const val DEFAULT_HOTSPOT_HOST = "192.168.43.1"
    }
}
