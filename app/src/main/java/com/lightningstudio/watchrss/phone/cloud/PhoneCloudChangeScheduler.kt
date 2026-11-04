package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.CloudLocalChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

class PhoneCloudChangeScheduler(
    database: PhoneCompanionDatabase,
    private val cloudSyncService: PhoneCloudSyncService,
    private val scope: CoroutineScope
) {
    private val changes=CloudLocalChanges(database)
    private var job: Job? = null
    private val bluetoothGate=BluetoothCloudChangeGate()
    fun setBluetoothSyncActive(active:Boolean) {bluetoothGate.setActive(active)}
    suspend fun acknowledgeBluetoothChanges() = withContext(Dispatchers.IO) {bluetoothGate.acknowledge(changes.sequence())}
    @OptIn(FlowPreview::class)
    fun start() {
        if (job != null) return
        job = scope.launch {
            changes.observeSequence()
                .drop(1)
                .debounce(10 * 60 * 1000L)
                .collect { sequence ->
                    if(!bluetoothGate.awaitAutomaticSync(sequence))return@collect
                    android.util.Log.i("WatchRSSCloud", "sync.origin=changeLogDebounce")
                    runCatching { cloudSyncService.syncNow() }
                }
        }
    }

    fun stop() { job?.cancel(); job = null }
}

/** Keeps the independent debounce from racing a long Bluetooth merge or its completion callback. */
internal class BluetoothCloudChangeGate {
    private val active=MutableStateFlow(false)
    private val acknowledged=AtomicLong(0)
    fun setActive(value:Boolean) {active.value=value}
    fun acknowledge(sequence:Long) {acknowledged.updateAndGet{maxOf(it,sequence)}}
    suspend fun awaitAutomaticSync(sequence:Long):Boolean {
        active.filter{!it}.first()
        return sequence>acknowledged.get()
    }
}
