package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CloudReadingPriorityTest {
    @Test fun foregroundPausesBulkAndCancellationReleasesIt() = runBlocking {
        val priority = CloudReadingPriority()
        val entered = CompletableDeferred<Unit>()
        val reader = launch { priority.foreground { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        var bulkStarted = false
        val bulk = launch { priority.awaitBackgroundTurn(); bulkStarted = true }
        delay(60)
        assertFalse(bulkStarted)
        reader.cancelAndJoin()
        withTimeout(1000) { bulk.join() }
        assertTrue(bulkStarted)
    }

    @Test fun oneFinishedReaderDoesNotReleaseAnotherActiveReader() = runBlocking {
        val priority = CloudReadingPriority()
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        val entered1 = CompletableDeferred<Unit>(); val entered2 = CompletableDeferred<Unit>()
        val a = launch { priority.foreground { entered1.complete(Unit); first.await() } }
        val b = launch { priority.foreground { entered2.complete(Unit); second.await() } }
        entered1.await(); entered2.await()
        val bulk = async { priority.awaitBackgroundTurn(); true }
        first.complete(Unit); a.join(); delay(60); assertFalse(bulk.isCompleted)
        second.complete(Unit); b.join(); assertTrue(withTimeout(1000) { bulk.await() })
    }
}
