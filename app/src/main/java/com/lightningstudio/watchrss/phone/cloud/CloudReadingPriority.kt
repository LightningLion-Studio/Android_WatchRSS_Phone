package com.lightningstudio.watchrss.phone.cloud

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/** The current bulk chunk may finish; subsequent chunks wait for foreground readers. */
internal class CloudReadingPriority {
    private val readers = AtomicInteger()
    suspend fun <T> foreground(block: suspend () -> T): T {
        readers.incrementAndGet()
        return try { block() } finally { readers.decrementAndGet() }
    }
    suspend fun awaitBackgroundTurn() {
        while (readers.get() > 0) delay(25)
    }
}
