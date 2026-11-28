package com.lightningstudio.watchrss.phone.cloud

import android.app.Application
import com.lightningstudio.watchrss.phone.cloud.CloudHeadSyncOutcome.COMPLETE
import com.lightningstudio.watchrss.phone.cloud.CloudHeadSyncOutcome.RETRY
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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

    @Test fun serverRetryDelaySurvivesBackgroundAndLocalBackoffStaysCapped() = runTest {
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
                            in 4..8 -> throw IOException("Offline")
                            else -> CloudHeadReply.Head("tag", 35_000)
                        }
                    }, { COMPLETE }, { false })
                }
            }
            runCurrent()
            advanceTimeBy(42_999)
            assertEquals(listOf(0L), requests)
            advanceTimeBy(40_001)
            runCurrent()
            assertEquals(listOf(0L, 43_000, 83_000), requests)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            advanceTimeBy(500_000)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            advanceTimeBy(499_999)
            assertEquals(listOf(0L, 43_000, 83_000), requests)
            runCurrent()
            assertEquals(1_082_999L, requests.last())
            advanceTimeBy(1_520_000)
            assertEquals(listOf(1_082_999L, 1_242_999, 1_542_999, 1_842_999, 2_142_999, 2_442_999, 2_477_999), requests.drop(3).take(7))
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
                    }, { COMPLETE }, { false })
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
            }, { if (completed) COMPLETE else RETRY }, { false })
        check("first", null, false)
        check("first", null, true)
        check("first", "tag", true)
        check("second", null, true)
    }

    @Test fun worstPollPhaseLeavesThreeSecondsForTransferAndRendering() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val edits = MutableSharedFlow<Long>()
            val owner = Owner()
            var published = false
            var publishedAt = -1L
            var displayedAt = -1L
            val polls = mutableListOf<Long>()
            // Exercise the upper jitter bound, including a positive sample from the old range.
            val poller = PhoneCloudHeadPoller({ testScheduler.currentTime }, { 2_000 })
            backgroundScope.launch {
                debounceCloudChanges(edits, flowOf(true)) { testScheduler.currentTime }.collect {
                    // Give the sender 500 ms to publish after the debounce.
                    delay(500)
                    publishedAt = testScheduler.currentTime
                    published = true
                }
            }
            backgroundScope.launch {
                // The receiver checks the old head 1 ms before the publication.
                delay(1_999)
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                poller.run(owner.lifecycle, flowOf(true)) {
                    poller.check(listOf("account"), { confirmed ->
                        polls += testScheduler.currentTime
                        val tag = if (published) "new" else "old"
                        if (!published) delay(500)
                        if (tag == confirmed) CloudHeadReply.Unchanged else CloudHeadReply.Head(tag, 20_000)
                    }, {
                        if (published) {
                            // Spend the remaining 2 s on fetch, apply and rendering.
                            delay(2_000)
                            displayedAt = testScheduler.currentTime
                        }
                        COMPLETE
                    }, { false })
                }
            }
            runCurrent()
            edits.emit(1)
            advanceTimeBy(25_000)
            runCurrent()
            assertEquals(2_000L, publishedAt)
            assertEquals(listOf(1_999L, 22_499L), polls)
            assertEquals(24_499L, displayedAt)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun cursorBatchesAndImportantEditsHaveIndependentDeadlines() = runTest {
        val edits = MutableStateFlow(com.lightningstudio.watchrss.phone.data.db.CloudSchedulingSequence(1, 1))
        val sent = mutableListOf<Pair<Long, Long>>()
        backgroundScope.launch {
            debounceScheduledCloudChanges(edits, flowOf(true)) { testScheduler.currentTime }.collect {
                sent += it.first to testScheduler.currentTime
            }
        }
        runCurrent()
        for (sequence in 2L..31L) {
            edits.value = com.lightningstudio.watchrss.phone.data.db.CloudSchedulingSequence(sequence, 1)
            advanceTimeBy(1_500)
        }
        assertTrue(sent.isEmpty())
        runCurrent()
        assertEquals(listOf(31L to 45_000L), sent)

        edits.value = com.lightningstudio.watchrss.phone.data.db.CloudSchedulingSequence(32, 32)
        advanceTimeBy(1_000)
        edits.value = com.lightningstudio.watchrss.phone.data.db.CloudSchedulingSequence(33, 32)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(33L to 46_500L, sent.last())
    }

    @Test fun editsPublishAfterOneAndAHalfQuietSecondsAndCoalesce() = runTest {
        val edits = MutableSharedFlow<Long>()
        val mode = MutableStateFlow(true)
        val published = mutableListOf<Pair<Long, Long>>()
        backgroundScope.launch {
            debounceCloudChanges(edits, mode) { testScheduler.currentTime }
                .collect { (sequence, _) -> published += sequence to testScheduler.currentTime }
        }
        runCurrent()
        edits.emit(1)
        advanceTimeBy(1_000)
        edits.emit(2)
        advanceTimeBy(1_499)
        assertTrue(published.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(2L to 2_500L), published)

        mode.value = false
        runCurrent()
        edits.emit(3)
        advanceTimeBy(44_999)
        assertEquals(1, published.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(2L to 2_500L, 3L to 47_500L), published)
    }

    @Test fun scheduledFallbackDebounceUsesFortyFiveSecondsWhenHeadPollUnavailable() = runTest {
        val edits = MutableStateFlow(com.lightningstudio.watchrss.phone.data.db.CloudSchedulingSequence(1, 1))
        val mode = MutableStateFlow(false)
        val sent = mutableListOf<Pair<Long, Long>>()
        backgroundScope.launch {
            debounceScheduledCloudChanges(edits, mode) { testScheduler.currentTime }.collect {
                sent += it.first to testScheduler.currentTime
            }
        }
        runCurrent()

        edits.value = com.lightningstudio.watchrss.phone.data.db.CloudSchedulingSequence(2, 2)
        advanceTimeBy(44_999)
        assertTrue(sent.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(2L to 45_000L), sent)

        edits.value = com.lightningstudio.watchrss.phone.data.db.CloudSchedulingSequence(3, 2)
        advanceTimeBy(44_999)
        assertEquals(1, sent.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(2L to 45_000L, 3L to 90_000L), sent)
    }
}
