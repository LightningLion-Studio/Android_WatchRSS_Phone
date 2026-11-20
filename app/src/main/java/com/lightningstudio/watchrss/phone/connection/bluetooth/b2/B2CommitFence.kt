package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

/** Account revocation and each connection's STOP share the commit admission monitor. */
internal class B2CommitFence(private val account: B2CommitFence? = null) {
    private val monitor: Any = account?.monitor ?: Any()
    @Volatile private var revoked = false

    val isRevoked: Boolean get() = revoked || account?.isRevoked == true

    fun check() {
        if (isRevoked) fail("AUTH_REVOKED")
    }

    fun revoke() = synchronized(monitor) { revoked = true }

    // Call at the end of the transaction body. An admitted SQLite commit can
    // finish after STOP; the store's writer lane delays cancellation and new epochs.
    // Never hold this monitor during SQLite or file I/O.
    fun admitCommit() = synchronized(monitor) { check() }
}
