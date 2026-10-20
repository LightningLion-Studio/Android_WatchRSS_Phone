package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Checked at the shared sync entry and between complete snapshot transactions. */
internal class CloudSyncPauseGate(initial: Boolean, private val persist: (Boolean) -> Unit) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<Boolean> = mutable
    @Synchronized fun setPaused(paused: Boolean) {
        persist(paused)
        mutable.value = paused
    }
}
