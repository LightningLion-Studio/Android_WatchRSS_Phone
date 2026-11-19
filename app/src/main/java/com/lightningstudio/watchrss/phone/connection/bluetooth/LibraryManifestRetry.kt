package com.lightningstudio.watchrss.phone.connection.bluetooth

import java.io.IOException
import java.net.ProtocolException
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal open class LibraryNoProgressLimit(
    message: String = "连续 3 次重连后同步均无进展，请稍后重试。",
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

internal class RepeatedLibraryManifestFailure(cause: Throwable) :
    LibraryNoProgressLimit("资料库清单连续 3 次传输失败且无进展，请稍后重试。", cause)

/** Counts only failed intervals after reconnecting, and only once per Ready. */
internal class LibraryReconnectProgress {
    var consecutive = 0
        private set
    private var awaitingProgress = false

    fun ready() { awaitingProgress = true }

    fun confirmed() {
        awaitingProgress = false
        consecutive = 0
    }

    fun disconnected(cause: Throwable? = null) {
        if (!awaitingProgress) return
        awaitingProgress = false
        if (++consecutive >= 3) throw LibraryNoProgressLimit(cause = cause)
    }
}

/** Counts an identical failed wire boundary across physical reconnects, within one sync. */
internal class LibraryManifestRetry : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LibraryManifestRetry>

    internal data class Boundary(val request: String, val incoming: Boolean, val index: Int)
    private var failedBoundary: Boundary? = null
    private var failures = 0

    fun confirmedProgress() {
        failedBoundary = null
        failures = 0
    }

    fun begin(frames: List<JSONObject>): Attempt {
        val digest = MessageDigest.getInstance("SHA-256")
        for (frame in frames) {
            val stable = JSONObject()
            frame.keys().asSequence().filter { it != "sentAt" }.sorted().forEach {
                stable.put(it, frame.get(it))
            }
            digest.update(stable.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return Attempt(digest.digest().joinToString("") { "%02x".format(it) })
    }

    internal inner class Attempt(private val request: String) {
        private var boundary = Boundary(request, incoming = false, index = 0)

        fun writing(index: Int) { boundary = Boundary(request, incoming = false, index) }
        fun reading(index: Int) { boundary = Boundary(request, incoming = true, index) }

        fun failed(error: Exception) {
            if (error is ProtocolException || (error !is IOException && error !is TimeoutCancellationException)) return
            if (failedBoundary != boundary) {
                failures = 0
                failedBoundary = boundary
            }
            if (++failures >= 3) throw RepeatedLibraryManifestFailure(error)
        }

        fun complete() = confirmedProgress()
    }
}

internal suspend inline fun exchangeManifest(
    frames: List<JSONObject>,
    write: (Int, JSONObject) -> Unit,
    read: (LibraryManifestRetry.Attempt?) -> JSONObject
): JSONObject {
    val attempt = currentCoroutineContext()[LibraryManifestRetry]?.begin(frames)
    try {
        frames.forEachIndexed { index, frame ->
            attempt?.writing(index)
            write(index, frame)
        }
        return read(attempt).also { attempt?.complete() }
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        attempt?.failed(failure)
        throw failure
    }
}
