package com.lightningstudio.watchrss.phone.cloud

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Pauses an existing automatic job without inventing a new cloud trigger for an unchanged BLE round. */
internal class BluetoothAutomaticCloudFence {
    private val foreground=AtomicBoolean(false)
    private val automatic=AtomicReference<Job?>(null)
    private val pending=AtomicBoolean(false)
    val active get()=foreground.get()
    val needsResume get()=!active&&pending.get()
    fun setActive(value:Boolean) {
        foreground.set(value)
        if(value)automatic.get()?.takeIf{it.isActive}?.let {pending.set(true);it.cancel(CancellationException("foreground Bluetooth sync"))}
    }
    fun started(manual:Boolean,job:Job?) {
        pending.set(false)
        if(!manual) {
            automatic.set(job)
            if(active){pending.set(true);job?.cancel(CancellationException("foreground Bluetooth sync"))}
        }
    }
    fun finished(job:Job?) {automatic.compareAndSet(job,null)}
}
