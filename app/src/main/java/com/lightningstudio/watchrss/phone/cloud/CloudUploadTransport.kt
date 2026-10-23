package com.lightningstudio.watchrss.phone.cloud

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/** Diagnostics contain only an opaque lease hash, byte counts and timings. */
internal class CloudUploadAttempt(reservationId: String, private val record: (String) -> Unit) : EventListener() {
    private val id = CloudSnapshotCodec.sha256(reservationId.toByteArray()).take(12)
    private val started = System.nanoTime()
    @Volatile private var protocol: Protocol? = null

    private fun event(value: String) {
        // An unavailable diagnostic sink must never turn a successful write into a retry.
        runCatching { record("$id:$value:ms=${(System.nanoTime() - started) / 1_000_000}") }
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        protocol = connection.protocol()
        event("connection:$protocol")
    }
    override fun requestHeadersEnd(call: Call, request: Request) = event("headers-sent")
    override fun requestBodyStart(call: Call) = event("body-start")
    override fun requestBodyEnd(call: Call, byteCount: Long) = event("body-sent:bytes=$byteCount")
    override fun responseHeadersEnd(call: Call, response: Response) = event("response:${response.code}")
    override fun callFailed(call: Call, ioe: IOException) = event("failed:${ioe.javaClass.simpleName}")

    fun shouldUseHttp1(error: IOException): Boolean {
        if (protocol != Protocol.HTTP_2) return false
        val stalled = when (error) {
            is CloudHttpException -> error.status == 408 && runCatching {
                val body = JSONObject(error.detail)
                body.optString("error") == "cloud_request_timeout" &&
                    body.optString("detail") == "upload_body_idle_timeout"
            }.getOrDefault(false)
            is SocketTimeoutException, is SocketException, is EOFException -> true
            else -> false
        }
        if (stalled) event("fallback-http1")
        return stalled
    }
}
