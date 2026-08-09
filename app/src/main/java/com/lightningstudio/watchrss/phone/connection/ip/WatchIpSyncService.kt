package com.lightningstudio.watchrss.phone.connection.ip

import android.content.Context
import android.util.Log
import org.json.JSONObject

internal class WatchIpSyncService(
    context: Context,
    serverDeviceId: String
) : AutoCloseable {
    private var port = 0
    val endpointProvider = IpEndpointProvider(
        context = context,
        serverDeviceId = serverDeviceId,
        portProvider = { port }
    )
    private var server: WatchIpSyncServer? = null
    private val nsd = IpSyncNsdAdvertiser(context, endpointProvider)

    @Synchronized
    fun start() {
        if (server?.isAlive == true) return
        server = startOnBluetoothProxySafePort()
        port = requireNotNull(server).listeningPort
        check(port > 0) { "IP 同步服务未取得监听端口" }
        nsd.start(port)
        Log.i(TAG, "IP sync server listening port=$port")
    }

    @Synchronized
    fun currentEndpointDescriptorJson(): JSONObject? =
        if (server?.isAlive == true && port > 0) endpointProvider.descriptor().toJson() else null

    @Synchronized
    override fun close() {
        nsd.close()
        server?.close()
        server = null
        port = 0
    }

    private fun startOnBluetoothProxySafePort(): WatchIpSyncServer {
        var lastFailure: Throwable? = null
        for (candidatePort in IP_SYNC_PORT_CANDIDATES) {
            val candidate = WatchIpSyncServer(endpointProvider, candidatePort)
            try {
                candidate.start(IP_SYNC_SOCKET_READ_TIMEOUT_MS, false)
                return candidate
            } catch (error: Throwable) {
                candidate.close()
                lastFailure = error
            }
        }
        throw IllegalStateException("没有可用的 IP 同步端口", lastFailure)
    }

    companion object {
        private const val TAG = "WatchRSS_IpService"
    }
}

// Keep this inside signed 16-bit range. The HeyTap Bluetooth network proxy stores
// the destination port in a short and passes the sign-extended value onward.
// See docs/bluetooth-proxy-ip-sync.md before changing this back to port 0.
internal val IP_SYNC_PORT_CANDIDATES: IntRange = 30_000..30_015
internal const val IP_SYNC_SOCKET_READ_TIMEOUT_MS = 10 * 60 * 1_000
