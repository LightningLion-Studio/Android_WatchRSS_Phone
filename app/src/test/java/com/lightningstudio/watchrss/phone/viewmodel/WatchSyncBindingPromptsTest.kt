package com.lightningstudio.watchrss.phone.viewmodel

import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchSyncBindingEvent
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchSyncBlocked
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WatchSyncBindingPromptsTest {
    @Test fun requireBindingPersistsUntilHandledAndOnlyUserActionRequestsNavigation() = runBlocking {
        val prompts = WatchSyncBindingPrompts()
        val events = mutableListOf<WatchSyncBindingEvent>()
        val collection = launch(start = CoroutineStart.UNDISPATCHED) { prompts.events.take(2).toList(events) }
        assertTrue(prompts.handle(WatchSyncBlocked(WatchSyncBindingEvent.RequireWatchBinding)))
        assertEquals(WatchSyncBindingEvent.RequireWatchBinding, prompts.prompt.value)
        kotlinx.coroutines.yield()
        assertEquals(listOf(WatchSyncBindingEvent.RequireWatchBinding), events)
        prompts.openWatchPairing(); collection.join()
        assertEquals(listOf(WatchSyncBindingEvent.RequireWatchBinding, WatchSyncBindingEvent.OpenWatchPairing), events)
        assertNull(prompts.prompt.value)
    }
    @Test fun mismatchCannotNavigateToPairingAndDismissNeverRetriesSync() {
        val prompts = WatchSyncBindingPrompts()
        assertTrue(prompts.handle(WatchSyncBlocked(WatchSyncBindingEvent.WatchAccountMismatch)))
        prompts.openWatchPairing(); assertEquals(WatchSyncBindingEvent.WatchAccountMismatch, prompts.prompt.value)
        prompts.dismiss(); assertNull(prompts.prompt.value)
    }
    @Test fun ordinaryFailuresDoNotBecomeBindingPromptsAndPausedUiStillHasPendingState() {
        val prompts = WatchSyncBindingPrompts()
        assertFalse(prompts.handle(IllegalStateException("offline"))); assertNull(prompts.prompt.value)
        prompts.handle(WatchSyncBlocked(WatchSyncBindingEvent.RequireWatchBinding))
        assertEquals(WatchSyncBindingEvent.RequireWatchBinding, prompts.prompt.value)
    }
}
