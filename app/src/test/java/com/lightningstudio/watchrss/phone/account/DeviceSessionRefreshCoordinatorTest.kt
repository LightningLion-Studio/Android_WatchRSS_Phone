package com.lightningstudio.watchrss.phone.account

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DeviceSessionRefreshCoordinatorTest {
    private class Fixture {
        var owner: String? = "owner"
        var cached = AppAuthorization("old", Long.MAX_VALUE, "lease", Long.MAX_VALUE, "", AppAccessSummary())
        var elapsed = 0L
        var wall = 100_000L
        var calls = 0
        var saves = 0
        var refresh: suspend (AppAuthorization) -> AppAuthorization = { delay(10); it.copy(deviceAccessToken = "new-$calls") }
        val gate = DeviceSessionRefreshCoordinator(
            load = { cached }, save = { cached = it; saves++ }, owner = { owner },
            refresh = { calls++; refresh(it) }, elapsedMillis = { elapsed }, wallMillis = { wall }
        )
    }

    @Test fun concurrentReconcileAndQueuedForegroundCallsReuseOneRotation() = runBlocking {
        val f = Fixture()
        val values = (1..40).map { async { f.gate.refresh("owner") } }.awaitAll()
        assertEquals(1, f.calls); assertEquals(1, f.saves)
        assertTrue(values.all { it.deviceAccessToken == "new-1" })
        repeat(20) { f.gate.refresh("owner") }
        assertEquals(1, f.calls)
        f.elapsed = 60_000
        f.gate.refresh("owner")
        assertEquals(2, f.calls)
    }

    @Test fun rejectedTokenWaveReusesThePublishedResultInsteadOfRotatingItAgain() = runBlocking {
        val f = Fixture()
        val values = (1..40).map { async { f.gate.refresh("owner", "old") } }.awaitAll()
        assertEquals(1, f.calls)
        assertTrue(values.all { it.deviceAccessToken == "new-1" })
        assertEquals("new-1", f.gate.withSession("owner") { it })
    }

    @Test fun validShortSessionIsSharedButExpiredSessionBypassesReconcileReuse() = runBlocking {
        val f = Fixture()
        f.refresh = { it.copy(deviceAccessToken = "new-${f.calls}", deviceAccessTokenExpiresAt = 100_000 + f.calls * 20_000L) }
        f.gate.refresh("owner"); f.gate.refresh("owner")
        assertEquals(1, f.calls)
        f.wall = 120_000
        f.gate.refresh("owner")
        assertEquals(2, f.calls)
    }

    @Test fun failedRefreshIsSharedWithoutAQueuedFailureStorm() = runBlocking {
        val f = Fixture(); f.refresh = { delay(10); throw IOException("offline") }
        val errors = (1..40).map { async { runCatching { f.gate.refresh("owner", "old") }.exceptionOrNull() } }.awaitAll()
        assertTrue(errors.all { it is IOException }); assertEquals(1, f.calls); assertEquals(0, f.saves)
        f.elapsed = 1_000
        runCatching { f.gate.refresh("owner", "old") }
        assertEquals(2, f.calls)
    }

    @Test fun rateLimitWithoutRetryAfterBlocksAllRefreshPathsForOneMinute() = runBlocking {
        val f = Fixture(); f.refresh = { throw PhoneAccountHttpException(429, "{}") }
        runCatching { f.gate.refresh("owner") }
        repeat(30) {
            assertTrue(runCatching { f.gate.refresh("owner", "old") }.exceptionOrNull() is DeviceSessionRefreshBackoffException)
        }
        assertEquals(1, f.calls); assertEquals("old", f.cached.deviceAccessToken)
        f.elapsed = 59_999
        val error = runCatching { f.gate.checkBackoff("owner") }.exceptionOrNull() as DeviceSessionRefreshBackoffException
        assertEquals(1, error.retryAfterMillis)
        f.elapsed = 60_000; runCatching { f.gate.refresh("owner") }
        assertEquals(2, f.calls)
    }

    @Test fun retryAfterLongerThanOneMinuteIsHonored() = runBlocking {
        val f = Fixture(); f.refresh = { throw PhoneAccountHttpException(429, "{}", 120_000) }
        runCatching { f.gate.refresh("owner", "old") }
        f.elapsed = 60_001
        assertTrue(runCatching { f.gate.refresh("owner") }.exceptionOrNull() is DeviceSessionRefreshBackoffException)
        assertEquals(1, f.calls)
        f.elapsed = 120_000; runCatching { f.gate.refresh("owner") }
        assertEquals(2, f.calls)
    }

    @Test fun refreshWaitsForInflightRequestsAndNewRequestsWaitForPublication() = runBlocking {
        val f = Fixture(); val oldSent = CompletableDeferred<Unit>(); val finishOld = CompletableDeferred<Unit>()
        val refreshing = CompletableDeferred<Unit>(); val finishRefresh = CompletableDeferred<Unit>()
        f.refresh = { refreshing.complete(Unit); finishRefresh.await(); it.copy(deviceAccessToken = "fresh") }
        val first = async { f.gate.withSession("owner") { token -> oldSent.complete(Unit); finishOld.await(); token } }
        oldSent.await()
        val rotation = async { f.gate.refresh("owner", "old") }
        yield()
        val next = async { f.gate.withSession("owner") { it } }
        yield(); assertFalse(refreshing.isCompleted); assertFalse(next.isCompleted)
        finishOld.complete(Unit); assertEquals("old", first.await()); refreshing.await()
        assertFalse(next.isCompleted)
        finishRefresh.complete(Unit); rotation.await(); assertEquals("fresh", next.await())
    }

    @Test fun accountSwitchDuringRefreshCannotSaveOrExposeAnotherOwnersSession() = runBlocking {
        val f = Fixture(); f.refresh = { f.owner = "other"; it.copy(deviceAccessToken = "must-not-save") }
        assertTrue(runCatching { f.gate.refresh("owner") }.isFailure)
        assertEquals(0, f.saves); assertEquals("old", f.cached.deviceAccessToken)
        assertTrue(runCatching { f.gate.withSession("owner") { fail("request must not run") } }.isFailure)
    }

    @Test fun cancelledReaderReleasesItsPermitSoRefreshCanProceed() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>()
        val request = launch { f.gate.withSession("owner") { entered.complete(Unit); awaitCancellation() } }
        entered.await(); request.cancelAndJoin()
        withTimeout(1_000) { f.gate.refresh("owner", "old") }
        assertEquals(1, f.calls)
    }

    @Test fun cancellingWaitingWriterDoesNotLeakAdmissionOrDrainPermits() = runBlocking {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val request = launch { f.gate.withSession("owner") { entered.complete(Unit); finish.await() } }
        entered.await(); val rotation = launch { f.gate.refresh("owner", "old") }; yield()
        rotation.cancelAndJoin(); finish.complete(Unit); request.join()
        withTimeout(1_000) { f.gate.refresh("owner", "old") }
        assertEquals(1, f.calls)
    }

    @Test fun retryAfterParsesSecondsAndHttpDateWithoutUnboundedValues() {
        assertEquals(120_000L, deviceSessionRetryAfterMillis("120", 0))
        assertEquals(120_000L, deviceSessionRetryAfterMillis("Thu, 1 Jan 1970 00:02:00 GMT", 0))
        assertEquals(86_400_000L, deviceSessionRetryAfterMillis(Long.MAX_VALUE.toString(), 0))
        assertNull(deviceSessionRetryAfterMillis("-1", 0)); assertNull(deviceSessionRetryAfterMillis("bad", 0))
    }
}
