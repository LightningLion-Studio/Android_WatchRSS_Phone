package com.lightningstudio.watchrss.phone.connection.guided

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
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
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GuidedSessionState(
    val ability: PhoneConnectionAbility,
    val ssid: String,
    val passphrase: String,
    val host: String,
    val port: Int,
    val token: String,
    val usesHotspot: Boolean,
    val payload: ByteArray,
    val packet: AcousticPacket
)

class PhoneGuidedSessionManager(
    context: Context,
    private val repository: PhoneCompanionRepository
) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val random = SecureRandom()

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: GuidedSessionServer? = null

    suspend fun startSession(
        ability: PhoneConnectionAbility,
        remoteUrl: String? = null
    ): GuidedSessionState = withContext(Dispatchers.Main) {
        stopSession()

        val token = randomCode(TOKEN_LENGTH)
        val guidedServer = GuidedSessionServer(
            repository = repository,
            ability = ability,
            token = token,
            remoteUrl = remoteUrl
        )
        guidedServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)

        try {
            resolveCurrentWifiTarget()?.let { target ->
                val payload = AcousticConnectionProtocol.buildGuidedWifi(
                    ability = ability,
                    ssid = target.ssid,
                    passphrase = "",
                    host = target.host,
                    port = guidedServer.listeningPort,
                    token = token
                )
                val packet = AcousticCodec.encode(payload)

                server = guidedServer

                return@withContext GuidedSessionState(
                    ability = ability,
                    ssid = target.ssid,
                    passphrase = "",
                    host = target.host,
                    port = guidedServer.listeningPort,
                    token = token,
                    usesHotspot = false,
                    payload = payload,
                    packet = packet
                )
            }

            val shortConfig = createShortHotspotConfig()
            var activeShortConfig: ShortHotspotConfig? = null
            val hotspotReservation = shortConfig
                ?.let { config ->
                    runCatching {
                        startHotspotWithConfigInternal(config.configuration)
                    }.onSuccess {
                        activeShortConfig = config
                    }.getOrNull()
                }
                ?: startHotspotInternal()
            val config = hotspotReservation.softApConfiguration
            val ssid = resolveSsid(config, hotspotReservation.wifiConfiguration, activeShortConfig?.ssid)
            val passphrase = resolvePassphrase(
                config = config,
                wifiConfiguration = hotspotReservation.wifiConfiguration,
                fallback = activeShortConfig?.passphrase
            )
            val host = resolveHotspotHost()
                ?: error("无法获取当前热点地址，已取消固定网关回退")
            val payload = AcousticConnectionProtocol.buildGuidedWifi(
                ability = ability,
                ssid = ssid,
                passphrase = passphrase,
                host = host,
                port = guidedServer.listeningPort,
                token = token
            )
            val packet = AcousticCodec.encode(payload)

            reservation = hotspotReservation
            server = guidedServer

            GuidedSessionState(
                ability = ability,
                ssid = ssid,
                passphrase = passphrase,
                host = host,
                port = guidedServer.listeningPort,
                token = token,
                usesHotspot = true,
                payload = payload,
                packet = packet
            )
        } catch (throwable: Throwable) {
            guidedServer.stop()
            throw throwable
        }
    }

    private fun createShortHotspotConfig(): ShortHotspotConfig? {
        val ssid = "W${randomCode(SHORT_SSID_SUFFIX_LENGTH)}"
        val passphrase = randomCode(SHORT_PASSPHRASE_LENGTH)
        return runCatching {
            val builder = SoftApConfiguration.Builder()
            builder.javaClass
                .getMethod("setSsid", String::class.java)
                .invoke(builder, ssid)
            builder.javaClass
                .getMethod(
                    "setPassphrase",
                    String::class.java,
                    Int::class.javaPrimitiveType!!
                )
                .invoke(builder, passphrase, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
            ShortHotspotConfig(
                configuration = builder.build(),
                ssid = ssid,
                passphrase = passphrase
            )
        }.getOrNull()
    }

    private suspend fun startHotspotWithConfigInternal(
        config: SoftApConfiguration
    ): WifiManager.LocalOnlyHotspotReservation {
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

            runCatching {
                if (Build.VERSION.SDK_INT >= 36) {
                    wifiManager.startLocalOnlyHotspotWithConfiguration(
                        config,
                        appContext.mainExecutor,
                        callback
                    )
                } else {
                    val method = wifiManager.javaClass.getMethod(
                        "startLocalOnlyHotspot",
                        SoftApConfiguration::class.java,
                        Executor::class.java,
                        WifiManager.LocalOnlyHotspotCallback::class.java
                    )
                    method.invoke(wifiManager, config, appContext.mainExecutor, callback)
                }
            }.onFailure { throwable ->
                if (!continuation.isCompleted) {
                    continuation.resumeWithException(throwable)
                }
            }
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
        wifiConfiguration: WifiConfiguration?,
        fallback: String?
    ): String {
        return config.ssid
            ?: wifiConfiguration?.SSID?.trim('"')
            ?: fallback
            ?: error("无法获取手机热点 SSID")
    }

    private fun resolvePassphrase(
        config: SoftApConfiguration,
        wifiConfiguration: WifiConfiguration?,
        fallback: String?
    ): String {
        return config.passphrase
            ?: wifiConfiguration?.preSharedKey?.trim('"')
            ?: fallback
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

    private fun resolveCurrentWifiTarget(): CurrentWifiTarget? {
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }

        val host = connectivityManager.getLinkProperties(activeNetwork)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { address ->
                !address.isLoopbackAddress && address.isSiteLocalAddress
            }
            ?.hostAddress
            ?: return null
        val ssid = wifiManager.connectionInfo
            ?.ssid
            ?.trim('"')
            ?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
            .orEmpty()
        return CurrentWifiTarget(ssid = ssid, host = host)
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

    private fun randomCode(length: Int): String {
        return buildString(length) {
            repeat(length) {
                append(SHORT_CODE_ALPHABET[random.nextInt(SHORT_CODE_ALPHABET.length)])
            }
        }
    }

    private data class ShortHotspotConfig(
        val configuration: SoftApConfiguration,
        val ssid: String,
        val passphrase: String
    )

    private data class CurrentWifiTarget(
        val ssid: String,
        val host: String
    )

    companion object {
        private const val TOKEN_LENGTH = 8
        private const val SHORT_SSID_SUFFIX_LENGTH = 3
        private const val SHORT_PASSPHRASE_LENGTH = 8
        private const val SHORT_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    }
}
