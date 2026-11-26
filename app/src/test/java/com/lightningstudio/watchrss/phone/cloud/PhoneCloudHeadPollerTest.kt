package com.lightningstudio.watchrss.phone.cloud

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneCloudHeadPollerTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun lifecycleStopsAnInFlightProbeAndPreventsRequestsInBackground() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            val poller = PhoneCloudHeadPoller({ testScheduler.currentTime }, { 0 })
            val mode = MutableStateFlow(true)
            var sent = 0
            var cancelled = 0
            val job = backgroundScope.launch {
                poller.run(owner.lifecycle, mode) {
                    poller.check(listOf("account"), {
                        sent++
                        try { awaitCancellation() } finally { cancelled++ }
                    }, { error("Unexpected sync") }, { false })
                }
            }
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertEquals(0, sent)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(1, sent)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertEquals(1, cancelled)
            advanceTimeBy(600_000)
            assertEquals(1, sent)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(2, sent)
            mode.value = false
            runCurrent()
            assertEquals(2, cancelled)
            advanceTimeBy(600_000)
            assertEquals(2, sent)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            job.join()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun rateLimitAndNetworkFailuresBackOffAndCapAtFiveMinutes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val poller = PhoneCloudHeadPoller({ testScheduler.currentTime }, { 0 })
            val requests = mutableListOf<Long>()
            val job = backgroundScope.launch {
                poller.run(owner.lifecycle, flowOf(true)) {
                    poller.check(listOf("account"), {
                        requests += testScheduler.currentTime
                        when (requests.size) {
                            1 -> CloudHeadReply.RateLimited(43_000)
                            2 -> throw IOException("Offline")
                            3 -> CloudHeadReply.RateLimited(999_999)
                            4 -> CloudHeadReply.Head("tag", 35_000)
                            else -> CloudHeadReply.Unchanged
                        }
                    }, { true }, { false })
                }
            }
            runCurrent()
            advanceTimeBy(42_999)
            assertEquals(listOf(0L), requests)
            advanceTimeBy(1_000_000)
            assertEquals(listOf(0L, 43_000, 83_000, 383_000, 418_000, 453_000), requests.take(6))
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            job.join()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun serverDisableFallsBackAfterAnAcceptedHeadAndResumesWithAFreshTag() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            val poller = PhoneCloudHeadPoller({ testScheduler.currentTime }, { 0 })
            val tags = mutableListOf<String?>()
            val job = backgroundScope.launch {
                poller.run(owner.lifecycle, flowOf(true)) {
                    poller.check(listOf("account"), {
                        tags += it
                        if (tags.size == 2) CloudHeadReply.Disabled else CloudHeadReply.Head("tag", null)
                    }, { true }, { false })
                }
            }
            runCurrent()
            assertTrue(poller.available.value)
            advanceTimeBy(20_000)
            runCurrent()
            assertFalse(poller.available.value)
            advanceTimeBy(599_999)
            assertEquals(listOf(null, "tag"), tags)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(null, "tag", null), tags)
            assertTrue(poller.available.value)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            job.join()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun failedRoundDoesNotAcknowledgeNewTagAndTagIsAccountScoped() = runTest {
        val poller = PhoneCloudHeadPoller({ 0 }, { 0 })
        suspend fun check(account: String, expectedTag: String?, completed: Boolean) =
            poller.check(listOf(account), {
                assertEquals(expectedTag, it)
                CloudHeadReply.Head("tag", null)
            }, { completed }, { false })
        check("first", null, false)
        check("first", null, true)
        check("first", "tag", true)
        check("second", null, true)
    }

    @Test fun editsPublishAfterThreeQuietSecondsAndCoalesce() = runTest {
        val edits = MutableSharedFlow<Long>()
        val mode = MutableStateFlow(true)
        val published = mutableListOf<Pair<Long, Long>>()
        backgroundScope.launch {
            debounceCloudChanges(edits, mode) { testScheduler.currentTime }
                .collect { (sequence, _) -> published += sequence to testScheduler.currentTime }
        }
        runCurrent()
        edits.emit(1)
        advanceTimeBy(2_000)
        edits.emit(2)
        advanceTimeBy(2_999)
        assertTrue(published.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(2L to 5_000L), published)

        mode.value = false
        runCurrent()
        edits.emit(3)
        advanceTimeBy(599_999)
        assertEquals(1, published.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(2L to 5_000L, 3L to 605_000L), published)
    }
}
