package com.lightningstudio.watchrss.phone.cloud

import com.lightningstudio.watchrss.phone.data.db.PhoneCompanionDatabase
import com.lightningstudio.watchrss.phone.data.db.CloudLocalChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

internal class PhoneCloudChangeScheduler(
    database: PhoneCompanionDatabase,
    private val cloudSyncService: PhoneCloudSyncService,
    private val scope: CoroutineScope,
    private val fastMode: Flow<Boolean>,
    private val fastEnabled: () -> Boolean
) {
    private val changes=CloudLocalChanges(database)
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true
    private val bluetoothGate=BluetoothCloudChangeGate()
    fun setBluetoothSyncActive(active:Boolean) {bluetoothGate.setActive(active)}
    suspend fun acknowledgeBluetoothChanges() = withContext(Dispatchers.IO) {bluetoothGate.acknowledge(changes.sequence())}
    fun start() {
        if (job != null) return
        job = scope.launch {
            debounceCloudChanges(changes.observeSequence().drop(1), fastMode, android.os.SystemClock::elapsedRealtime)
                .collect { (sequence, observedAt) ->
                    if (!bluetoothGate.awaitAutomaticSync(sequence)) return@collect
                    if (!fastEnabled()) {
                        delay((observedAt + LEGACY_CHANGE_INTERVAL_MS - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        if (!bluetoothGate.awaitAutomaticSync(sequence)) return@collect
                    }
                    android.util.Log.i("WatchRSSCloud", "sync.origin=changeLogDebounce")
                    try {
                        cloudSyncService.syncDebouncedChanges(fastEnabled())
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                    }
                }
        }
    }

    fun stop() { job?.cancel(); job = null }
}

private const val LEGACY_CHANGE_INTERVAL_MS = 10 * 60 * 1_000L
private const val FAST_CHANGE_INTERVAL_MS = 1_500L

@OptIn(ExperimentalCoroutinesApi::class)
internal fun debounceCloudChanges(
    changes: Flow<Long>,
    fastMode: Flow<Boolean>,
    elapsed: () -> Long
): Flow<Pair<Long, Long>> = changes
    .map { sequence -> sequence to elapsed() }
    .combine(fastMode.distinctUntilChanged()) { change, fast -> change to fast }
    .transformLatest { (change, fast) ->
        val interval = if (fast) FAST_CHANGE_INTERVAL_MS else LEGACY_CHANGE_INTERVAL_MS
        delay((change.second + interval - elapsed()).coerceAtLeast(0))
        emit(change)
    }
    .distinctUntilChanged()

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
