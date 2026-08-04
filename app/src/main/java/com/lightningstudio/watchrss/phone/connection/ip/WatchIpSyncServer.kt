package com.lightningstudio.watchrss.phone.connection.ip

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal class WatchIpSyncServer(
    private val descriptorProvider: IpEndpointProvider
) : NanoWSD(0), AutoCloseable {
    override fun openWebSocket(handshake: IHTTPSession): WebSocket = SyncWebSocket(handshake)

    override fun serveHttp(session: IHTTPSession): Response {
        return when (session.uri) {
            "/health" -> newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject().put("status", "ok").put("version", IpSyncProtocol.VERSION).toString()
            )
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    override fun close() {
        stop()
        PhoneIpSyncSessionRegistry.activeSessions().forEach(PhoneIpSyncSession::close)
    }

    private inner class SyncWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private var authenticatedSession: PhoneIpSyncSession? = null

        override fun onOpen() {
            Log.i(TAG, "IP WebSocket opened remote=${handshakeRequest.remoteIpAddress}")
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode,
            reason: String,
            initiatedByRemote: Boolean
        ) {
            authenticatedSession?.let { session ->
                PhoneIpSyncSessionRegistry.unregister(session)
                session.close()
            }
            authenticatedSession = null
            Log.i(TAG, "IP WebSocket closed code=$code remote=$initiatedByRemote reason=$reason")
        }

        override fun onMessage(frame: WebSocketFrame) {
            try {
                if (authenticatedSession == null) {
                    if (frame.opCode != WebSocketFrame.OpCode.Text) {
                        reject("首帧必须是 HELLO")
                        return
                    }
                    authenticate(JSONObject(frame.textPayload))
                    return
                }
                if (frame.opCode == WebSocketFrame.OpCode.Binary) {
                    authenticatedSession?.acceptBinary(frame.binaryPayload)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "IP WebSocket message failed", error)
                reject(error.message ?: "消息处理失败")
            }
        }

        override fun onPong(frame: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            Log.w(TAG, "IP WebSocket exception", exception)
        }

        private fun authenticate(json: JSONObject) {
            require(json.optString("type") == IpSyncProtocol.TYPE_HELLO) { "缺少 HELLO" }
            require(json.optInt("version") == IpSyncProtocol.VERSION) { "IP 同步协议版本不兼容" }
            val hello = IpHello.fromJson(json)
            require(hello.watchDeviceId.isNotBlank() && hello.clientNonce.isNotBlank()) {
                "HELLO 身份字段缺失"
            }
            val descriptor = descriptorProvider.descriptor()
            require(descriptor.verify()) { "端点描述已失效" }
            require(hello.endpointEpoch == descriptor.epoch) { "端点地址已变化，请重新发现" }
            require(
                IpSyncProtocol.constantTimeEquals(
                    IpSyncProtocol.hmac(descriptor.authToken, hello.canonicalPayload()),
                    hello.hmac
                )
            ) { "HELLO 认证失败" }

            val routeKind = routeKindForRemote(
                handshakeRequest.remoteIpAddress,
                descriptor.endpoints
            )
            val sessionId = hello.resumeSessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val serverNonce = randomNonce()
            val unsignedAck = IpHelloAck(
                serverDeviceId = descriptor.serverDeviceId,
                sessionId = sessionId,
                routeKind = routeKind,
                acceptedResumeSeq = hello.lastAckSeq.coerceAtLeast(0),
                serverNonce = serverNonce,
                hmac = ""
            )
            val ack = unsignedAck.copy(
                hmac = IpSyncProtocol.hmac(descriptor.authToken, unsignedAck.canonicalPayload())
            )
            val session = PhoneIpSyncSession(
                watchDeviceId = hello.watchDeviceId,
                sessionId = sessionId,
                routeKind = routeKind,
                remoteAddress = handshakeRequest.remoteIpAddress.orEmpty(),
                sendBinary = { bytes -> send(bytes) },
                closeSocket = {
                    runCatching {
                        close(WebSocketFrame.CloseCode.NormalClosure, "session replaced", false)
                    }
                }
            )
            authenticatedSession = session
            send(ack.toJson().toString())
            PhoneIpSyncSessionRegistry.register(session)
            Log.i(
                TAG,
                "IP HELLO accepted watch=${hello.watchDeviceId} route=${routeKind.wireName} " +
                    "remote=${handshakeRequest.remoteIpAddress} resume=${hello.resumeSessionId != null}"
            )
        }

        private fun reject(message: String) {
            runCatching {
                send(
                    JSONObject()
                        .put("type", IpSyncProtocol.TYPE_ERROR)
                        .put("message", message)
                        .toString()
                )
                close(WebSocketFrame.CloseCode.PolicyViolation, message, false)
            }
        }
    }

    companion object {
        private const val TAG = "WatchRSS_IpServer"
        private val random = SecureRandom()

        internal fun routeKindForRemote(
            remoteAddress: String?,
            endpoints: List<IpEndpointCandidate>
        ): IpTransportKind {
            val remotePrefix = remoteAddress?.substringBeforeLast('.', missingDelimiterValue = "")
            return endpoints.firstOrNull { endpoint ->
                remotePrefix?.isNotBlank() == true && endpoint.address.substringBeforeLast('.') == remotePrefix
            }?.transportKind ?: IpTransportKind.UNKNOWN_LOCAL
        }

        private fun randomNonce(): String = ByteArray(16).also(random::nextBytes).let {
            Base64.getUrlEncoder().encodeToString(it)
        }
    }
}
