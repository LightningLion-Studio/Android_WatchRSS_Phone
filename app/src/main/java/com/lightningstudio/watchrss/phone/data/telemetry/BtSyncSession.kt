package com.lightningstudio.watchrss.phone.data.telemetry

import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryBluetoothUnavailable
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryDisconnectLimit
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryReconnectLimit
import com.lightningstudio.watchrss.phone.connection.bluetooth.LibraryTransportFailure
import com.lightningstudio.watchrss.phone.connection.bluetooth.ProbeTransportException
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchLibraryClearException
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchAccountSyncRequired
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchSyncBindingEvent
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchSyncBlocked
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2ApplyStop
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Exception
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2Wire
import com.lightningstudio.watchrss.phone.connection.bluetooth.b2.B2TransferObserver
import com.lightningstudio.watchrss.phone.connection.bluetooth.isTerminalAccountSyncFailure
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import com.lightningstudio.watchrss.phone.rollout.B2Cohort
import com.lightningstudio.watchrss.phone.rollout.B2RolloutDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONException
import org.json.JSONObject
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.util.BitSet
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal enum class BtSyncStage(val wire: String) {
    DISCOVER("discover"), CONNECT("connect"), READY("ready"), ACCOUNT("account"), NEGOTIATE("negotiate"),
    TRANSFER("transfer"), COMMIT("commit"), ACK("ack"), OTHER("other")
}

internal enum class BtSyncTrigger(val wire: String) { MANUAL("manual"), AUTO("auto"), RESUME("resume") }

internal enum class BtSyncProtocol(val wire: String) { B2("b2"), V1("v1"), COMPOSE("compose"), NONE("none") }

internal enum class BtSyncError(val wire: String) {
    UNKNOWN("unknown"), NOT_FOUND("not_found"), TIMEOUT("timeout"), CANCELLED("cancelled"),
    PERMISSION("permission"), BLUETOOTH_OFF("bluetooth_off"), BLUETOOTH_UNAVAILABLE("bluetooth_unavailable"),
    CONNECTION_FAILED("connection_failed"), DISCONNECTED("disconnected"), INVALID_FRAME("invalid_frame"),
    ACCOUNT_REQUIRED("account_required"), ACCOUNT_MISMATCH("account_mismatch"),
    ACCOUNT_SYNC_REQUIRED("account_sync_required"), AUTH_REVOKED("auth_revoked"),
    CAPABILITY_MISMATCH("capability_mismatch"), PEER_IMPLEMENTATION_UNKNOWN("peer_implementation_unknown"),
    BUSINESS_REJECTED("business_rejected"), LOCAL_IO("local_io"), STORAGE_PRESSURE("storage_pressure"),
    HASH_MISMATCH("hash_mismatch"), LIMIT_EXCEEDED("limit_exceeded"), FLOW_CONTROL("flow_control"),
    BUSY("busy"), RECOVERY_FAILED("recovery_failed"), RECOVERY_FLAPPING("recovery_flapping"),
    CHUNKS_MISSING("chunks_missing"), DEPENDENCIES_PENDING("dependencies_pending"),
    EXPIRED("expired"), JOB_CONFLICT("job_conflict"), JOURNAL_CORRUPT("journal_corrupt"),
    LEASE_EXPIRED("lease_expired"), MANIFEST_INCOMPLETE("manifest_incomplete"),
    OBJECT_CONFLICT("object_conflict"), PEER_MISMATCH("peer_mismatch"), PLAN_CONFLICT("plan_conflict"),
    RATE_LIMITED("rate_limited"), RECEIPT_CONFLICT("receipt_conflict"), REQUEST_CONFLICT("request_conflict"),
    SOURCE_CHANGED("source_changed"), STALE_EPOCH("stale_epoch"), UNSUPPORTED_KIND("unsupported_kind"),
    UNSUPPORTED_PHASE("unsupported_phase"), UNSUPPORTED_VERSION("unsupported_version")
}

internal data class BtSyncEvent(val data: JSONObject) {
    val id: String get() = data.getString("eventId")
    val occurredAt: Long get() = data.getLong("occurredAt")
}

internal data class BtSyncDevice(
    val installId: String,
    val versionName: String,
    val versionCode: Int,
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val sdkInt: Int
)

internal class BtSyncSession(
    private val device: BtSyncDevice,
    private val trigger: BtSyncTrigger,
    private val record: suspend (BtSyncEvent) -> Unit,
    private val now: () -> Long,
    private val monotonic: () -> Long
) : AbstractCoroutineContextElement(Key), B2TransferObserver {
    companion object Key : CoroutineContext.Key<BtSyncSession>

    private val started = monotonic()
    private var finished = false
    @Volatile var stage = BtSyncStage.DISCOVER
    @Volatile var protocol = BtSyncProtocol.NONE
    var decision = B2RolloutDecision(false, B2Cohort.INELIGIBLE, 0)
    private var peer: JSONObject? = null
    private var wireBytes: Long? = null
    private var disconnects = 0
    private var resumed = trigger == BtSyncTrigger.RESUME
    private var binaryUsed = false
    private var resent = 0
    private var effectiveBytes = 0L
    private val seenChunks = mutableMapOf<Pair<Boolean, String>, BitSet>()
    private val acceptedChunks = mutableMapOf<Pair<Boolean, String>, BitSet>()
    private val descriptors = mutableMapOf<Pair<Boolean, String>, String>()
    private val transferred = mutableSetOf<Pair<Boolean, String>>()
    private val kinds = mutableMapOf<String, Int>()

    @Synchronized
    fun observePeer(probe: JSONObject) {
        if (peer != null || probe.opt("success") != true) return
        val details = probe.optJSONObject("watchCapabilities")
        val version = probe.opt("appVersionCode") ?: details?.opt("appVersionCode")
        val model = (details?.opt("model") ?: probe.opt("model")) as? String
        peer = JSONObject().put("platform", "watch")
            .put("appVersionCode", when (version) {
                is Int -> version.takeIf { it >= 0 } ?: JSONObject.NULL
                is Long -> version.takeIf { it in 0..Int.MAX_VALUE.toLong() } ?: JSONObject.NULL
                else -> JSONObject.NULL
            })
            .put("model", telemetryLabel(model) ?: JSONObject.NULL)
    }

    @Synchronized
    override fun bytes(count: Long) { wireBytes = (wireBytes ?: 0L) + count }

    @Synchronized
    fun disconnected() { disconnects++ }

    @Synchronized
    fun binaryStarted() {
        protocol = BtSyncProtocol.B2
        stage = BtSyncStage.NEGOTIATE
        binaryUsed = true
    }

    fun legacyRequest(request: JSONObject) {
        stage = when (request.optString("action")) {
            "syncAccount" -> BtSyncStage.ACCOUNT
            "ack", "syncSession" -> if (request.optString("phase") == "abort") return else BtSyncStage.ACK
            "syncLibrary" -> when (request.optString("phase")) {
                "probe" -> BtSyncStage.READY
                "commit" -> BtSyncStage.COMMIT
                else -> BtSyncStage.TRANSFER
            }
            else -> if (protocol == BtSyncProtocol.NONE) stage else BtSyncStage.TRANSFER
        }
    }

    @Synchronized
    override fun binaryRequest(request: JSONObject, incoming: Boolean) {
        val body = request.optJSONObject("payload") ?: return
        when (request.optString("phase")) {
            "prepare" -> stage = BtSyncStage.NEGOTIATE
            "apply" -> stage = BtSyncStage.COMMIT
            "complete" -> stage = BtSyncStage.ACK
            "begin" -> {
                stage = BtSyncStage.TRANSFER
                if (body.optString("intent") == "resume") resumed = true
            }
            "manifestPage" -> {
                stage = BtSyncStage.TRANSFER
                val entries = body.optJSONArray("entries") ?: return
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val kind = entry.optString("kind")
                    binaryDescriptor(entry.optString("objectId"), kind, incoming)
                }
            }
        }
    }

    @Synchronized
    override fun chunk(request: JSONObject, incoming: Boolean, length: Int, accepted: Boolean) {
        val body = request.optJSONObject("payload") ?: return
        val key = incoming to body.optString("objectId")
        val index = body.optInt("chunkIndex", -1)
        if (index !in 0..255) return
        val recorded = (if (accepted) acceptedChunks else seenChunks).getOrPut(key) { BitSet() }
        val repeat = recorded[index]
        recorded.set(index)
        if (accepted) {
            if (!repeat) effectiveBytes += length
            binaryApplied(request, incoming)
        } else {
            stage = BtSyncStage.TRANSFER
            if (repeat) resent++
        }
    }

    @Synchronized
    override fun binaryDescriptor(id: String, kind: String, incoming: Boolean) {
        if (kind !in BINARY_KINDS || id.isBlank()) return
        val key = incoming to id
        if (descriptors.putIfAbsent(key, kind) == null && key in transferred) {
            kinds[kind] = (kinds[kind] ?: 0) + 1
        }
    }

    @Synchronized
    override fun binaryApplied(request: JSONObject, incoming: Boolean) {
        val id = request.optJSONObject("payload")?.optString("objectId") ?: return
        val key = incoming to id
        if (!transferred.add(key)) return
        val kind = descriptors[key] ?: return
        kinds[kind] = (kinds[kind] ?: 0) + 1
    }

    @Synchronized
    fun legacyObjects(articles: Int, sources: Int, notes: Int) {
        legacyKind("articleBody", articles)
        legacyKind("rssSource", sources)
        legacyKind("note", notes)
    }

    private fun legacyKind(kind: String, count: Int) {
        if (count > 0) kinds[kind] = (kinds[kind] ?: 0) + count
    }

    suspend fun finishSuccess() = finish("success", null, null)

    suspend fun finishFailure(error: Throwable) {
        val cancelled = error is CancellationException && error !is TimeoutCancellationException ||
            error is B2Exception && error.code == "JOB_CANCELLED"
        val mapped = mapBtSyncFailure(error, stage)
        finish(if (cancelled) "cancelled" else "failure", mapped.first,
            if (cancelled) BtSyncError.CANCELLED else mapped.second)
    }

    suspend fun finishDiscoveryFailure(code: String?) {
        val (stage, error) = mapProbeFailure(code)
        finish(if (error == BtSyncError.CANCELLED) "cancelled" else "failure", stage, error)
    }

    suspend fun finishCancelledIfOpen() = finish("cancelled", stage, BtSyncError.CANCELLED)

    private suspend fun finish(result: String, failureStage: BtSyncStage?, error: BtSyncError?) {
        val event = synchronized(this) {
            if (finished) return
            finished = true
            val transfer = JSONObject()
                .put("bytesEffective", if (binaryUsed) effectiveBytes else JSONObject.NULL)
                .put("bytesWire", wireBytes ?: JSONObject.NULL)
                .put("objects", if (binaryUsed || kinds.isNotEmpty() || transferred.isNotEmpty()) {
                    kinds.values.sum() + transferred.count { it !in descriptors }
                } else JSONObject.NULL)
                .put("disconnects", disconnects)
                .put("resumed", resumed)
                .put("resentChunks", if (binaryUsed) resent else JSONObject.NULL)
            BtSyncEvent(JSONObject()
                .put("eventId", UUID.randomUUID().toString())
                .put("occurredAt", now())
                .put("installId", device.installId)
                .put("platform", "phone")
                .put("appVersionName", telemetryLabel(device.versionName).orEmpty())
                .put("appVersionCode", device.versionCode)
                .put("device", JSONObject().put("manufacturer", telemetryLabel(device.manufacturer) ?: JSONObject.NULL)
                    .put("model", telemetryLabel(device.model) ?: JSONObject.NULL).put("osName", "Android")
                    .put("osVersion", telemetryLabel(device.osVersion) ?: JSONObject.NULL)
                    .put("sdkInt", device.sdkInt))
                .put("peer", peer ?: JSONObject.NULL)
                .put("configVersion", decision.configVersion)
                .put("configStale", decision.configStale)
                .put("features", JSONObject().put(FeatureRegistry.b2.key, if (decision.enabled) "on" else "off"))
                .put("cohort", decision.cohort.wire)
                .put("protocol", protocol.wire)
                .put("trigger", trigger.wire)
                .put("result", result)
                .put("failureStage", failureStage?.wire ?: JSONObject.NULL)
                .put("errorCode", error?.wire ?: JSONObject.NULL)
                .put("durationMs", (monotonic() - started).coerceAtLeast(0))
                .put("transfer", transfer)
                .put("kinds", JSONObject(kinds)))
        }
        record(event)
    }
}

private val BINARY_KINDS = B2Wire.inheritedKinds + B2Wire.bookKinds
internal const val BT_SYNC_EVENT_LIMIT_MS = 7L * 24 * 60 * 60 * 1000

internal fun mapProbeFailure(code: String?): Pair<BtSyncStage, BtSyncError> = when (code) {
    null, "clear_probe_not_found" -> BtSyncStage.DISCOVER to BtSyncError.NOT_FOUND
    "clear_probe_timeout" -> BtSyncStage.DISCOVER to BtSyncError.TIMEOUT
    "clear_probe_cancelled" -> BtSyncStage.DISCOVER to BtSyncError.CANCELLED
    "clear_probe_permission" -> BtSyncStage.DISCOVER to BtSyncError.PERMISSION
    "clear_probe_bluetooth_off" -> BtSyncStage.DISCOVER to BtSyncError.BLUETOOTH_OFF
    "clear_probe_bluetooth_unavailable" -> BtSyncStage.DISCOVER to BtSyncError.BLUETOOTH_UNAVAILABLE
    "clear_probe_connect" -> BtSyncStage.CONNECT to BtSyncError.CONNECTION_FAILED
    "clear_probe_read" -> BtSyncStage.READY to BtSyncError.DISCONNECTED
    "clear_probe_protocol" -> BtSyncStage.READY to BtSyncError.INVALID_FRAME
    else -> BtSyncStage.DISCOVER to BtSyncError.UNKNOWN
}

internal fun mapBtSyncFailure(error: Throwable, stage: BtSyncStage): Pair<BtSyncStage, BtSyncError> {
    val chain = generateSequence(error) { it.cause }.toList()
    if (chain.any(::isTerminalAccountSyncFailure)) {
        val code = when {
            chain.any { it is WatchSyncBlocked && it.event == WatchSyncBindingEvent.WatchAccountMismatch } ->
                BtSyncError.ACCOUNT_MISMATCH
            chain.any { it is WatchAccountSyncRequired } -> BtSyncError.ACCOUNT_SYNC_REQUIRED
            else -> BtSyncError.ACCOUNT_REQUIRED
        }
        return BtSyncStage.ACCOUNT to code
    }
    chain.filterIsInstance<WatchLibraryClearException>().firstOrNull()?.let { return mapProbeFailure(it.errorCode) }
    val observed = chain.filterIsInstance<ProbeTransportException>().firstOrNull()?.let {
        if (it.stage == "connect") BtSyncStage.CONNECT else BtSyncStage.READY
    } ?: stage
    chain.filterIsInstance<B2Exception>().firstOrNull()?.let {
        val code = when (it.code) {
            "JOB_CANCELLED" -> BtSyncError.CANCELLED
            "FLOW_CONTROL_VIOLATION", "WINDOW_FULL" -> BtSyncError.FLOW_CONTROL
            else -> BtSyncError.entries.firstOrNull { known -> known.name == it.code } ?: BtSyncError.UNKNOWN
        }
        val failureStage = when {
            code == BtSyncError.AUTH_REVOKED -> BtSyncStage.ACCOUNT
            it is B2ApplyStop -> BtSyncStage.COMMIT
            else -> observed
        }
        return failureStage to code
    }
    val code = when {
        chain.any { it is TimeoutCancellationException || it is SocketTimeoutException } -> BtSyncError.TIMEOUT
        chain.any { it is CancellationException } -> BtSyncError.CANCELLED
        chain.any { it is LibraryBluetoothUnavailable } -> BtSyncError.BLUETOOTH_OFF
        chain.any { it is SecurityException } -> BtSyncError.PERMISSION
        chain.any { it is LibraryDisconnectLimit } -> BtSyncError.RECOVERY_FLAPPING
        chain.any { it is LibraryReconnectLimit } -> BtSyncError.RECOVERY_FAILED
        chain.any { it is LibraryTransportFailure } -> BtSyncError.DISCONNECTED
        chain.any { it is ProtocolException || it is JSONException } -> BtSyncError.INVALID_FRAME
        observed in setOf(BtSyncStage.DISCOVER, BtSyncStage.CONNECT, BtSyncStage.READY) ->
            mapProbeFailure(com.lightningstudio.watchrss.phone.connection.bluetooth.watchLibraryClearFailure(
                error, com.lightningstudio.watchrss.phone.connection.bluetooth.WatchLibraryClearPhase.PROBING
            ).errorCode).second
        else -> BtSyncError.UNKNOWN
    }
    return observed to code
}

// Keep this ASCII whitelist in sync with the backend telemetry label validator.
private fun telemetryLabel(value: String?): String? = value?.asSequence()?.filter {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in " ._-+()[],&"
}?.take(64)?.joinToString("")?.takeIf(String::isNotEmpty)

internal suspend fun <T> BtSyncSession?.runBtSync(block: suspend () -> T): T {
    if (this == null) return block()
    return try {
        kotlinx.coroutines.withContext(this) { block() }.also { finishSuccess() }
    } catch (error: Throwable) {
        finishFailure(error)
        throw error
    }
}
