package com.lightningstudio.watchrss.phone.account

import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Once HTTP headers exist, a broken body is a server-response failure, not
 * an unavailable transport. Keep the status and Retry-After even for 2xx bodies.
 * This also covers callers that read a successful response outside execute().
 */
internal fun Response.withAccountResponseStatus(): Response {
    val original = body ?: return this
    val status = code
    val retryAfter = deviceSessionRetryAfterMillis(header("Retry-After"))
    val guardedBody = object : ResponseBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        private val guardedSource by lazy {
            val source = try { original.source() } catch (error: IOException) {
                throw PhoneAccountHttpException(status, "", retryAfter).apply { initCause(error) }
            }
            object : ForwardingSource(source) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount)
                } catch (error: IOException) {
                    throw PhoneAccountHttpException(status, "", retryAfter).apply { initCause(error) }
                }
            }.buffer()
        }
        override fun source() = guardedSource
    }
    return newBuilder().body(guardedBody).build()
}
