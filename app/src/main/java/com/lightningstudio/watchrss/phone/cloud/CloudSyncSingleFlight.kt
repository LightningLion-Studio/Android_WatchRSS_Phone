package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.sync.Mutex

/** Foreground/startup/periodic automatic triggers must not queue duplicate full backups. */
internal class CloudSyncSingleFlight {
    private val mutex = Mutex()
    suspend fun <T> run(manual: Boolean, skipped: () -> T, block: suspend () -> T): T {
        if (manual) mutex.lock() else if (!mutex.tryLock()) return skipped()
        return try { block() } finally { mutex.unlock() }
    }
    suspend fun <T> withLock(block: suspend () -> T): T = run(true, { error("unreachable") }, block)
    val isLocked: Boolean get() = mutex.isLocked
}
