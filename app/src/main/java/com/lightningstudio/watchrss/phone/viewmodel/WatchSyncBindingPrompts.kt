package com.lightningstudio.watchrss.phone.viewmodel

import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchSyncBindingEvent
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchSyncBlocked
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pending prompt survives a paused UI collector; navigation is always an explicit UI action. */
internal class WatchSyncBindingPrompts {
    private val pending = MutableStateFlow<WatchSyncBindingEvent?>(null)
    val prompt = pending.asStateFlow()
    private val emitted = MutableSharedFlow<WatchSyncBindingEvent>(extraBufferCapacity = 1)
    val events = emitted.asSharedFlow()
    fun handle(failure: Throwable): Boolean {
        if (failure !is WatchSyncBlocked) return false
        pending.value = failure.event
        emitted.tryEmit(failure.event)
        return true
    }
    fun dismiss() { pending.value = null }
    fun openWatchPairing() {
        if (pending.value != WatchSyncBindingEvent.RequireWatchBinding) return
        pending.value = null
        emitted.tryEmit(WatchSyncBindingEvent.OpenWatchPairing)
    }
}
