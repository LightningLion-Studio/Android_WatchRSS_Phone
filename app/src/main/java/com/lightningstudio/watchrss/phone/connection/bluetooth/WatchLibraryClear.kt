package com.lightningstudio.watchrss.phone.connection.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Probe counts describe discovery only. No percentage is known for remote deletion. */
enum class WatchLibraryClearPhase { PROBING, SELECTING, CLEARING, COMPLETE, CANCELLED, FAILED }

data class WatchLibraryClearState(
    val phase: WatchLibraryClearPhase = WatchLibraryClearPhase.PROBING,
    val probeCompleted: Int = 0,
    val probeTotal: Int = 0,
    val clearPercent: Int? = null,
    val deviceName: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val remoteMayHaveChanged: Boolean = false
)

internal class WatchLibraryClearException(
    val errorCode: String,
    val retryable: Boolean = true,
    cause: Throwable? = null
) : IllegalStateException(errorCode, cause)

internal class ProbeTransportException(val stage: String, cause: Throwable) : IOException("probe_$stage", cause)

internal fun watchLibraryClearFailure(failure: Throwable, phase: WatchLibraryClearPhase): WatchLibraryClearException {
    val chain = generateSequence(failure) { it.cause }.toList()
    chain.filterIsInstance<WatchLibraryClearException>().firstOrNull()?.let { return it }
    val probe = phase == WatchLibraryClearPhase.PROBING || phase == WatchLibraryClearPhase.SELECTING
    val code = when {
        chain.any { it is TimeoutCancellationException || it is java.net.SocketTimeoutException } ->
            if (probe) "clear_probe_timeout" else "clear_library_transport_timeout"
        chain.any { it is CancellationException } -> if (probe) "clear_probe_cancelled" else "clear_library_cancelled"
        chain.any { it is SecurityException || it.message == "缺少 BLUETOOTH_CONNECT 权限" } -> "clear_probe_permission"
        probe && chain.any { it.message == "蓝牙未开启" } -> "clear_probe_bluetooth_off"
        probe && chain.any { it.message == "此设备没有蓝牙适配器" } -> "clear_probe_bluetooth_unavailable"
        probe && chain.any { it is ProbeTransportException && it.stage == "connect" } -> "clear_probe_connect"
        chain.any { it is IOException } -> if (probe) "clear_probe_read" else "clear_library_disconnected"
        chain.any { it is JSONException || it is IllegalArgumentException } ->
            if (probe) "clear_probe_protocol" else "clear_library_protocol"
        else -> if (probe) "clear_probe_failed" else "clear_library_failed"
    }
    return WatchLibraryClearException(code, code !in setOf("clear_probe_permission", "clear_probe_bluetooth_off", "clear_probe_bluetooth_unavailable"), failure)
}

internal fun requireWatchLibraryCleared(response: JSONObject) {
    if (!response.optBoolean("success", false) || response.optBoolean("fatalSession", false)) {
        val remote = response.optString("errorCode")
        val known = setOf("clear_library_cancelled", "clear_library_timeout", "clear_library_busy", "clear_library_database",
            "clear_library_files", "clear_library_changed", "clear_library_cleanup")
        throw WatchLibraryClearException(if (remote in known) remote else "clear_library_rejected", response.optBoolean("retryable", true))
    }
    require(!response.has("action") || response.optString("action") == BluetoothSyncProtocol.ACTION_CLEAR_LIBRARY) {
        "invalid clearLibrary response"
    }
}

/** One owner spans discovery, optional selection, destructive request and session release. */
internal class WatchLibraryClearOperation(
    private val probe: suspend ((Int, Int) -> Unit) -> PhoneBluetoothProbeTargets,
    private val clear: suspend (PhoneBluetoothWatchDevice, PhoneSyncSession?) -> Unit,
    private val onActive: (Boolean) -> Unit
) {
    suspend fun run(
        select: suspend (List<PhoneBluetoothWatchDevice>) -> PhoneBluetoothWatchDevice?,
        onState: (WatchLibraryClearState) -> Unit
    ) {
        var state = WatchLibraryClearState()
        var lease: PhoneSyncSession? = null
        fun publish(next: WatchLibraryClearState) { state = next; onState(next) }
        try {
            onActive(true)
            publish(state)
            val targets = probe { completed, total ->
                publish(state.copy(probeCompleted = completed.coerceIn(0, total.coerceAtLeast(0)), probeTotal = total.coerceAtLeast(0)))
            }
            lease = targets.sessionLease
            lease?.bindCancellation(currentCoroutineContext()[Job])
            currentCoroutineContext().ensureActive()
            val device = when (targets.devices.size) {
                0 -> throw WatchLibraryClearException(targets.failureCode ?: "clear_probe_not_found")
                1 -> targets.devices.single()
                else -> {
                    publish(state.copy(phase = WatchLibraryClearPhase.SELECTING))
                    select(targets.devices) ?: throw CancellationException("clear selection dismissed")
                }
            }
            require(device in targets.devices) { "invalid clear target" }
            publish(state.copy(phase = WatchLibraryClearPhase.CLEARING, clearPercent = null, deviceName = device.name))
            clear(device, lease)
            currentCoroutineContext().ensureActive()
            lease?.close()
            lease = null
            publish(state.copy(phase = WatchLibraryClearPhase.COMPLETE, clearPercent = 100))
        } catch (failure: Throwable) {
            val classified = watchLibraryClearFailure(failure, state.phase)
            val mayHaveChanged = state.phase == WatchLibraryClearPhase.CLEARING &&
                classified.errorCode !in setOf("clear_library_busy", "clear_library_database")
            publish(state.copy(phase = if (failure is CancellationException && failure !is TimeoutCancellationException)
                WatchLibraryClearPhase.CANCELLED else WatchLibraryClearPhase.FAILED,
                clearPercent = null, errorCode = classified.errorCode, retryable = classified.retryable,
                remoteMayHaveChanged = mayHaveChanged))
            if (failure is CancellationException) throw failure
            throw classified
        } finally {
            try { lease?.close() } finally { onActive(false) }
        }
    }
}
