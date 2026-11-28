package com.lightningstudio.watchrss.phone.network

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class CloudHttpDeadline private constructor(val untilNanos: Long) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CloudHttpDeadline> {
        suspend fun after(millis: Long): CloudHttpDeadline {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            val parent = currentCoroutineContext()[Key]
            return CloudHttpDeadline(parent?.let { minOf(it.untilNanos, until) } ?: until)
        }

        suspend fun <T> runOrNull(millis: Long, work: suspend () -> T): T? {
            val deadline = after(millis)
            return withTimeoutOrNull(millis) { withContext(deadline) { work() } }
        }
    }

    fun expired(): Boolean = untilNanos - System.nanoTime() <= 0
}

/** Sequential remote phases reserve time for each revocation within 10 seconds. */
internal object LogoutHttpTimeout {
    const val CLOUD_DEVICE_MILLIS = 4_000L
    const val PHONE_AUTHORIZATION_MILLIS = 3_000L
    const val ACCOUNT_SESSION_MILLIS = 3_000L
}

internal fun Call.limitToDeadline(untilNanos: Long) {
    if (untilNanos - System.nanoTime() <= 0) throw InterruptedIOException("timeout")
    val timeout = timeout()
    timeout.deadlineNanoTime(if (timeout.hasDeadline()) minOf(untilNanos, timeout.deadlineNanoTime()) else untilNanos)
}

/** Keeps cancellation attached to synchronous OkHttp work through the last byte of the response. */
internal class CloudCallCancellation private constructor(
    private val continuation: CancellableContinuation<*>,
    private val deadline: CloudHttpDeadline?
) {
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun checkActive() {
        if (!continuation.isActive) throw CancellationException("Cloud request cancelled")
    }

    fun <T> execute(call: Call, read: (Response) -> T): T {
        return track(call) { call.execute().use(read) }
    }

    fun <T> track(call: Call, work: () -> T): T {
        deadline?.let { call.limitToDeadline(it.untilNanos) }
        activeCalls.add(call)
        try {
            checkActive()
            return work().also { checkActive() }
        } catch (error: Exception) {
            checkActive()
            throw error
        } finally {
            activeCalls.remove(call)
        }
    }

    companion object {
        // The caller runs on IO. The cancellation handler can close its socket from another thread.
        suspend fun <T> run(work: (CloudCallCancellation) -> T): T {
            val deadline = currentCoroutineContext()[CloudHttpDeadline]
            return suspendCancellableCoroutine { continuation ->
                val scope = CloudCallCancellation(continuation, deadline)
                continuation.invokeOnCancellation { scope.activeCalls.forEach { it.cancel() } }
                val result = runCatching { scope.checkActive(); work(scope) }
                result.fold(continuation::resume, continuation::resumeWithException)
            }
        }
    }
}

internal suspend fun Call.executeBufferedCloudResponse(
    onHeaders: (Response) -> Unit = {}
): Response = CloudCallCancellation.run { cancellation ->
    cancellation.execute(this) { response ->
        onHeaders(response)
        val body = response.body?.let { it.bytes().toResponseBody(it.contentType()) }
        response.newBuilder().body(body).build()
    }
}
