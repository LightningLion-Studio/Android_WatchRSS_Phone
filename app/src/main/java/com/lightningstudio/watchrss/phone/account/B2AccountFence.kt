package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import java.io.Closeable

/** A runtime keeps its original fence even when the same account signs in again. */
internal class B2AccountFence {
    @Volatile private var revoked = false
    private val sockets = mutableSetOf<() -> Unit>()

    fun check() {
        if (revoked) throw B2Exception("AUTH_REVOKED")
    }

    fun onRevoke(closeSocket: () -> Unit): Closeable {
        val closeNow = synchronized(this) {
            if (revoked) true else {
                sockets.add(closeSocket)
                false
            }
        }
        if (closeNow) closeSocket()
        return Closeable { synchronized(this) { sockets.remove(closeSocket) } }
    }

    fun revoke() {
        val closing = synchronized(this) {
            revoked = true
            sockets.toList().also { sockets.clear() }
        }
        closing.forEach { it() }
    }
}
