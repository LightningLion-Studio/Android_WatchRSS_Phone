package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2CommitFence
import java.io.Closeable

/** A runtime keeps its original fence even when the same account signs in again. */
internal class B2AccountFence {
    val commitFence = B2CommitFence()
    private val sockets = mutableSetOf<() -> Unit>()

    fun check() = commitFence.check()

    fun onRevoke(closeSocket: () -> Unit): Closeable {
        val closeNow = synchronized(this) {
            if (commitFence.isRevoked) true else {
                sockets.add(closeSocket)
                false
            }
        }
        if (closeNow) closeSocket()
        return Closeable { synchronized(this) { sockets.remove(closeSocket) } }
    }

    fun revoke() {
        val closing = synchronized(this) {
            commitFence.revoke()
            sockets.toList().also { sockets.clear() }
        }
        closing.forEach { it() }
    }
}
