package com.lightningstudio.watchrss.phone.connection.bluetooth.b2

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

/** Pre-begin source preparation has no authorized heartbeat slot. It has its own
 * real-work watchdog, independent of any socket read timeout or total task duration. */
object B2LocalWork {
    suspend fun <T> run(block: suspend (() -> Unit) -> T): T = supervisorScope {
        val watch = B2LocalWatchdog { System.nanoTime() / 1_000_000 }
        val work = async(Dispatchers.IO) { block(watch::completedWorkUnit) }
        val monitor = async { while (true) { delay(100); watch.check() } }
        try { select<T> { work.onAwait { it }; monitor.onAwait { fail("LOCAL_IO") } } }
        finally { work.cancel(); monitor.cancel() }
    }
}
