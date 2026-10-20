package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CloudSyncSingleFlightTest {
    @Test fun automaticTriggersCoalesceWhileManualRequestStillRuns() = runBlocking {
        val gate=CloudSyncSingleFlight();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var calls=0
        val first=async { gate.run(false, { -1 }) { calls++;entered.complete(Unit);release.await();1 } }
        entered.await()
        assertEquals(-1,gate.run(false, { -1 }) { calls++;2 })
        val manual=async { gate.run(true, { -1 }) { calls++;3 } }
        delay(30);assertFalse(manual.isCompleted)
        release.complete(Unit);assertEquals(1,first.await());assertEquals(3,manual.await());assertEquals(2,calls)
        assertFalse(gate.isLocked)
    }
    @Test fun cancellationReleasesAutomaticSyncSlot() = runBlocking {
        val gate=CloudSyncSingleFlight();val entered=CompletableDeferred<Unit>()
        val first=launch { gate.run(false, {}) { entered.complete(Unit);awaitCancellation() } }
        entered.await();first.cancelAndJoin()
        assertEquals(2,gate.run(false, { -1 }) { 2 })
    }
}
