package com.lightningstudio.watchrss.phone.viewmodel

import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchSyncBindingEvent
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchSyncBlocked
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired
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
        val event = when (failure) {
            is com.lightningstudio.watchrss.phone.account.PhoneDeviceVerificationRequired -> WatchSyncBindingEvent.RequirePhoneDeviceVerification
            is PhoneLoginRequired -> WatchSyncBindingEvent.RequirePhoneLogin
            is WatchSyncBlocked -> failure.event
            else -> return false
        }
        pending.value = event
        emitted.tryEmit(event)
        return true
    }
    fun dismiss() { pending.value = null }
    fun openWatchPairing() {
        if (pending.value != WatchSyncBindingEvent.RequireWatchBinding) return
        pending.value = null
        emitted.tryEmit(WatchSyncBindingEvent.OpenWatchPairing)
    }
}
