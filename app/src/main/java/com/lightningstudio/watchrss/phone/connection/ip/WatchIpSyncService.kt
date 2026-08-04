package com.lightningstudio.watchrss.phone.connection.ip

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD

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
    private val server = WatchIpSyncServer(endpointProvider)
    private val nsd = IpSyncNsdAdvertiser(context, endpointProvider)

    @Synchronized
    fun start() {
        if (server.isAlive) return
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        port = server.listeningPort
        check(port > 0) { "IP 同步服务未取得监听端口" }
        nsd.start(port)
        Log.i(TAG, "IP sync server listening port=$port")
    }

    @Synchronized
    override fun close() {
        nsd.close()
        server.close()
        port = 0
    }

    companion object {
        private const val TAG = "WatchRSS_IpService"
    }
}
