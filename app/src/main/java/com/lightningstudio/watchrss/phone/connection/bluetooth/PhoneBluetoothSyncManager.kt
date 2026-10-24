package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.annotation.SuppressLint
import android.content.Context
import com.lightningstudio.watchrss.phone.account.accountHttpErrorCode
import com.lightningstudio.watchrss.phone.account.watchAccountConflictMessage
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageRepository
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.data.note.MarkdownMergeResult
import com.lightningstudio.watchrss.phone.data.note.NoteRepository
import com.lightningstudio.watchrss.phone.data.repo.PhoneLibrarySyncWindow
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetRepository
import com.lightningstudio.watchrss.phone.data.reader.ReaderPreset
import com.lightningstudio.watchrss.phone.data.reader.ReaderPresetSnapshot
import com.lightningstudio.watchrss.phone.data.reader.ReaderBackgroundType
import com.lightningstudio.watchrss.phone.data.reader.ReaderTypographyRole
import com.lightningstudio.watchrss.phone.data.reader.WatchBackgroundPreparationException
import com.lightningstudio.watchrss.phone.data.reader.WatchBackgroundTranscoder
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.ReceiveChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PhoneBluetoothSyncResult(
    val deviceName: String,
    val deviceAddress: String = "",
    val importedCount: Int? = null,
    val libraryStats: LibrarySyncStats? = null,
    val noteStats: NoteSyncStats? = null,
    val accountSync: AccountSyncResult? = null,
    val accountSyncWarning: String? = null,
    val readerSyncWarning: String? = null,
    val tokenUsageSyncWarning: String? = null
)

internal data class PhoneBluetoothProbeTargets(
    val devices: List<PhoneBluetoothWatchDevice>,
    val sessionLease: PhoneSyncSession? = null
)

data class NoteSyncStats(
    val sent: Int,
    val received: Int,
    val appliedOnWatch: Int,
    val conflictsOnPhone: Int,
    val appliedOnPhone: Int = 0
)

data class WatchDebugLog(
    val text: String,
    val truncated: Boolean
)

enum class LibrarySyncMode(val protocolValue: String) {
    MERGE(BluetoothSyncProtocol.LIBRARY_MODE_MERGE),
    PHONE_TO_WATCH_REPLACE(BluetoothSyncProtocol.LIBRARY_MODE_PHONE_TO_WATCH_REPLACE),
    WATCH_TO_PHONE_REPLACE(BluetoothSyncProtocol.LIBRARY_MODE_WATCH_TO_PHONE_REPLACE)
}

enum class PhoneBluetoothSyncStage(val displayName: String) {
    CONNECTING("建立连接中"),
    RECONNECTING("连接中断，正在重连"),
    SYNCING_ACCOUNT("账号授权同步中"),
    TRANSFERRING("信息传输中"),
    SYNCING_READER_RESOURCES("阅读器资源同步中"),
    SYNCING_NOTES("备忘录同步中"),
    SYNCING_BOOKS("书籍同步中"),
    VERIFYING("校验中")
}

private fun java.io.InputStream.readNoteAssetChunk(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var size = 0
    while (size < maxBytes) {
        val count = read(buffer, size, maxBytes - size)
        if (count < 0) break
        if (count == 0) continue
        size += count
    }
    return if (size == buffer.size) buffer else buffer.copyOf(size)
}

data class PhoneBluetoothSyncProgress(
    val stage: PhoneBluetoothSyncStage,
    val percent: Int,
    val bytesTransferred: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val reconnectAttempt: Int = 0
)

internal data class BleSyncBindingSnapshot(val address:String?,val owner:String?,val session:PhoneSyncSession?,val response:JSONObject) {
    val capabilities:BleSyncCapabilities get()=BleSyncCapabilities.read(response)
}

class PhoneBluetoothSyncManager(
    context: Context,
    private val repository: PhoneCompanionRepository,
    private val noteRepository: NoteRepository,
    private val readerPresetRepository: ReaderPresetRepository,
    private val llmTokenUsageRepository: PhoneLlmTokenUsageRepository,
    private val deviceId: String,
    private val debugLog: BluetoothDebugLog,
    private val buildAccountSyncRequest: suspend (watchDeviceId: String, watchInstallId: String?, watchDisplayName: String?) -> JSONObject =
        { _, _, _ -> error("账号同步未配置") },
    private val canSyncAccount: () -> Boolean = { false },
    private val onLibrarySyncCompleted: suspend () -> Unit = {},
    private val currentPhoneUserId: () -> String? = { null },
    private val onSyncActiveChanged: (Boolean) -> Unit = {},
    private val domainScope:()->String={""},
    private val accountDomainWitness:suspend(String)->String?={null},
    private val prepareAccountDomain:suspend(String,String?)->Unit={_,_->},
    private val acknowledgeAccountDomain:suspend(String,String)->Unit={_,_->}
) {
    private val appContext = context.applicationContext
    private val assetHashes=NoteAssetHashCache(appContext.getSharedPreferences("watchrss_ble_asset_hashes",android.content.Context.MODE_PRIVATE))
    private val client = PhoneBluetoothSyncClient(appContext, debugLog)
    private val watchBackgroundTranscoder =
        WatchBackgroundTranscoder(context.applicationContext, readerPresetRepository)

    fun foregroundSyncStarted() {
        debugLog.appendEvent("sync.ui.click",fields=mapOf("elapsedNanos" to SystemClock.elapsedRealtimeNanos()))
        onSyncActiveChanged(true)
    }
    fun foregroundSyncFinished() {
        debugLog.appendEvent("sync.ui.final",fields=mapOf("elapsedNanos" to SystemClock.elapsedRealtimeNanos()))
        onSyncActiveChanged(false)
    }

    // Injectable radio/scheduler boundary; production uses the paired device and cancellable delay.
    internal var reconnectSession: (suspend (PhoneBluetoothWatchDevice, String) -> PhoneSyncSession?)? = null
    internal var reconnectDelay: suspend (Long) -> Unit = { delay(it) }

    /** No sync-binding gate here: an unbound watch is precisely the target of this optional action. */
    internal suspend fun requestWatchPairingQr(reusableSession: PhoneSyncSession? = null): WatchQrRequest =
        OpenPairingRequest(
            selectTarget = { withContext(Dispatchers.IO) { client.pairingTarget(reusableSession?.device?.bluetoothAddress) } },
            exchange = { target, request ->
                withContext(Dispatchers.IO) {
                    val sessionId = BluetoothDebugLog.newSessionId("openPairing")
                    (reusableSession?.exchange(request, sessionId) ?: client.exchange(request,
                        deviceAddress = target.address, sessionId = sessionId, rememberDeviceOnSuccess = false)).response
                }
            }
        ).send()

    /** Read-only probe on the selected session/route, before sending library data or issuing watch tokens. */
    private suspend fun requireWatchBinding(sessionId: String, deviceAddress: String? = null,
        syncSession: PhoneSyncSession? = null): String? = checkWatchSyncBinding(currentPhoneUserId) {
        exchange(LibrarySyncPayload.buildProbeRequest(deviceId), sessionId = "$sessionId-binding",
            deviceAddress = deviceAddress, syncSession = syncSession)
    } ?: deviceAddress

    private suspend fun bindingSnapshot(sessionId:String,address:String?,session:PhoneSyncSession?,cached:BleSyncBindingSnapshot?=null):BleSyncBindingSnapshot {
        val owner=currentPhoneUserId()?.takeIf { it.isNotBlank() }
        if(cached!=null) {
            check(cached.owner==owner && cached.session===session && (address==null || cached.address==address)) { "蓝牙会话或手机账号已变化，请重新同步" }
            return cached
        }
        val probe=session?.takeIf{it.isPersistent}?.bindingProbe
        val result=if(probe!=null)BluetoothSyncExchange(session.device.name,session.device.address,LibrarySyncPayload.buildProbeRequest(deviceId),probe)
            else exchange(LibrarySyncPayload.buildProbeRequest(deviceId),sessionId="$sessionId-binding",deviceAddress=address,syncSession=session)
        requireSuccess(result.response)
        check(currentPhoneUserId()?.takeIf { it.isNotBlank() }==owner) { "手机账号已变化，请重新同步" }
        if(owner!=null)requireWatchSyncBinding(result.response,owner)
        return BleSyncBindingSnapshot(result.deviceAddress.ifBlank { address.orEmpty() },owner,session,result.response)
    }

    internal suspend fun probeLibrarySyncTargets(
        onProbe: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): PhoneBluetoothProbeTargets {
        val sessionId = BluetoothDebugLog.newSessionId("syncLibraryProbe")
        debugLog.appendEvent(
            event = "sync.library.probe.start",
            sessionId = sessionId,
            fields = mapOf("protocol" to LibrarySyncPayload.PROTOCOL_VERSION)
        )
        return runCatching {
            withTimeout(LIBRARY_PROBE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    client.probeLibrarySyncDevices(
                        deviceId = deviceId,
                        sessionId = sessionId,
                        onProbe = { completed, total, _ ->
                            onProbe(completed, total)
                        }
                    )
                }
            }.let { batch ->
                PhoneBluetoothProbeTargets(
                    devices = batch.results.filter { it.reachable }.map { it.device },
                    sessionLease = batch.sessionLease
                )
            }.also { targets ->
                    debugLog.appendEvent(
                        event = "sync.library.probe.complete",
                        sessionId = sessionId,
                        fields = mapOf(
                            "reachable" to targets.devices.size,
                            "reusableSession" to (targets.sessionLease != null)
                        )
                    )
                }
        }.onFailure { throwable ->
            debugLog.appendEvent(
                event = "sync.library.probe.failed",
                sessionId = sessionId,
                fields = failureFields(throwable),
                throwable = throwable
            )
        }.getOrElse { throwable ->
            if (throwable is TimeoutCancellationException) {
                throw IllegalStateException(
                    "探测手表超时，请确认手表端应用已打开并保持亮屏后重试",
                    throwable
                )
            }
            throw throwable
        }
    }

    /** Returns the newest part of the watch log. This is deliberately optional for sharing. */
    suspend fun collectWatchDebugLog(): WatchDebugLog {
        val targets = probeLibrarySyncTargets()
        val device = targets.devices.singleOrNull()
            ?: error(if (targets.devices.isEmpty()) "未连接到可用手表" else "发现多块手表，请先通过同步选择目标手表")
        val sessionId = BluetoothDebugLog.newSessionId("pullWatchLog")
        return try {
            val request = JSONObject().apply {
                put("version", LibrarySyncPayload.PROTOCOL_VERSION)
                put("action", BluetoothSyncProtocol.ACTION_PULL_DEBUG_LOG)
                put("maxChars", 700_000)
            }
            val exchange = targets.sessionLease?.exchange(request, sessionId)
                ?: client.exchange(
                    request = request,
                    deviceAddress = device.address,
                    deviceNameHint = device.name,
                    sessionId = sessionId
                )
            requireSuccess(exchange.response)
            WatchDebugLog(
                text = exchange.response.optString("log"),
                truncated = exchange.response.optBoolean("truncated")
            )
        } finally {
            targets.sessionLease?.runCatching { complete("$sessionId-complete") }
            targets.sessionLease?.close()
        }
    }

    suspend fun sendRemoteInput(url: String): PhoneBluetoothSyncResult {
        val sessionId = BluetoothDebugLog.newSessionId("remoteInput")
        debugLog.appendEvent(
            event = "sync.remoteInput.start",
            sessionId = sessionId,
            fields = mapOf("urlBytes" to url.toByteArray(Charsets.UTF_8).size)
        )
        return runCatching {
            val targetAddress = requireWatchBinding(sessionId)
            val exchange = exchange(
                JSONObject().apply {
                    put("version", 1)
                    put("action", BluetoothSyncProtocol.ACTION_REMOTE_INPUT)
                    put("nonce", System.currentTimeMillis().toString())
                    put("url", url)
                },
                sessionId = sessionId,
                deviceAddress = targetAddress
            )
            requireSuccess(exchange.response)
            debugLog.appendEvent(
                event = "sync.remoteInput.complete",
                sessionId = sessionId,
                fields = mapOf("device" to exchange.deviceName)
            )
            PhoneBluetoothSyncResult(deviceName = exchange.deviceName)
        }.onFailure { throwable ->
            debugLog.appendEvent(
                event = "sync.remoteInput.failed",
                sessionId = sessionId,
                fields = failureFields(throwable),
                throwable = throwable
            )
        }.getOrThrow()
    }

    internal suspend fun syncAccount(
        device: PhoneBluetoothWatchDevice,
        reusableSession: PhoneSyncSession? = null,
        finishSession: Boolean = true,
        binding: BleSyncBindingSnapshot? = null,
        reuseGrant:Boolean=false
    ): PhoneBluetoothSyncResult {
        val sessionId = BluetoothDebugLog.newSessionId("syncAccount")
        var syncSession = reusableSession ?: client.openPersistentSession(
            device,
            deviceId,
            "$sessionId-open"
        )
        if (finishSession && syncSession != null) {
            syncSession = client.promoteLateIpSession(
                session = syncSession,
                sessionId = "$sessionId-ip-upgrade"
            )
        }
        val effectiveDevice = syncSession?.device ?: device
        val watchDeviceId = effectiveDevice.remoteDeviceId.ifBlank { effectiveDevice.address }
        debugLog.appendEvent(
            event = "sync.account.start",
            sessionId = sessionId,
            fields = mapOf(
                "targetAddress" to effectiveDevice.address,
                "watchDeviceId" to watchDeviceId
            )
        )
        val result = runCatching {
            bindingSnapshot(sessionId,effectiveDevice.address,syncSession,binding)
            if(!reuseGrant)prepareAccountDomain(watchDeviceId,null)
            val request = buildAccountSyncRequest(
                watchDeviceId,
                null,
                effectiveDevice.name.ifBlank { "手表" }
            )
            val exchange = exchange(
                request = request,
                deviceAddress = effectiveDevice.address,
                sessionId = sessionId,
                syncSession = syncSession
            )
            requireSuccess(exchange.response)
            val accountSync = AccountSyncPayload.parseResponse(exchange.response)
            debugLog.appendEvent(
                event = "sync.account.complete",
                sessionId = sessionId,
                fields = mapOf(
                    "device" to exchange.deviceName,
                    "boundUserId" to accountSync.boundUserId,
                    "watchDeviceId" to accountSync.watchDeviceId,
                    "telemetryBacklog" to accountSync.telemetryBacklog
                )
            )
            PhoneBluetoothSyncResult(
                deviceName = exchange.deviceName,
                accountSync = accountSync
            )
        }.onFailure { throwable ->
            debugLog.appendEvent(
                event = "sync.account.failed",
                sessionId = sessionId,
                fields = failureFields(throwable),
                throwable = throwable
            )
        }
        if (finishSession && syncSession != null) {
            if (result.isSuccess) {
                runCatching { syncSession.complete("$sessionId-complete") }
            } else {
                runCatching { syncSession.abort("$sessionId-abort") }
            }
            syncSession.close()
        }
        return result.getOrThrow()
    }

    internal suspend fun syncAll(
        device: PhoneBluetoothWatchDevice,
        forceFull: Boolean = false,
        mode: LibrarySyncMode = LibrarySyncMode.MERGE,
        reusableSession: PhoneSyncSession? = null,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit = {},
        resolveDeleteConflicts: suspend (List<PhoneSyncDeleteConflict>) -> Map<String, PhoneSyncConflictResolution> = {
            emptyMap()
        }
    ): PhoneBluetoothSyncResult {
        onSyncActiveChanged(true)
        try {
        val sessionId = BluetoothDebugLog.newSessionId("syncAll")
        var syncSession = reusableSession
        var effectiveDevice = device
        var opened = reusableSession != null
        var sharedBinding: BleSyncBindingSnapshot? = null
        var libraryDeclarations: JSONObject? = null
        var fastPath:DomainFastPath?=null
        var rootWindow:com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.RootWindow?=null
        var fastPeer:String?=null
        var fastScope:String?=null
        val owner = currentPhoneUserId()
        val completedDomains=linkedSetOf<String>()
        suspend fun localRoots(peer:String):Map<String,String> = withContext(Dispatchers.IO) {
            check(currentPhoneUserId()==owner){"手机账号已变化，请重新同步"}
            val start=SystemClock.elapsedRealtimeNanos()
            // Only persisted version counters + resource witnesses here. R44 registration/R46
            // projection are prepared inside a non-skipped original domain, never by summary.
            val store=repository.bleDomains
            val roots=if(store==null)emptyMap()else {
                val window=rootWindow?:store.beginRootWindow(accountDomainWitness(peer)).also {rootWindow=it}
                val current=window.current(accountDomainWitness(peer))
                if(repository.libraryFastPathAllowed(peer))current else current-"library"
            }
            check(currentPhoneUserId()==owner){"手机账号已变化，请重新同步"}
            debugLog.appendEvent("sync.domains.local",sessionId,mapOf("prepareMs" to (SystemClock.elapsedRealtimeNanos()-start)/1_000_000,"rootCount" to roots.size))
            roots
        }
        fun skipped(domain:String)=fastPath?.skipped?.contains(domain)==true
        var booksApplied = 0
        var locallyApplied = false
        suspend fun connect() {
            syncSession?.close()
            syncSession = null
            check(currentPhoneUserId() == owner) { "手机账号已变化，请重新同步" }
            syncSession = reconnectSession?.invoke(device, "$sessionId-open")
                ?: if (reconnectSession == null) client.openPersistentSession(device, deviceId, "$sessionId-open") else null
            // All recovery for this operation uses the visible, bounded manager budget.
            syncSession?.recoveryGate?.tryAcquire()
            effectiveDevice = syncSession?.device ?: device
            sharedBinding = null
            libraryDeclarations = null
            fastPath=null;rootWindow=null;fastPeer=null;fastScope=null;completedDomains.clear()
            opened = true
        }
        suspend fun binding(): BleSyncBindingSnapshot {
            check(currentPhoneUserId()==owner){"手机账号已变化，请重新同步"}
            return sharedBinding ?: bindingSnapshot(sessionId, effectiveDevice.address, syncSession).also { sharedBinding = it }
        }
        val recovery = BluetoothSyncRecovery(reconnectDelay, ::connect) { attempt, wait, failure ->
            syncSession?.close()
            debugLog.appendEvent("sync.reconnect", sessionId,
                fields = failureFields(failure) + mapOf("attempt" to attempt, "delayMs" to wait), throwable = failure)
            onProgress(PhoneBluetoothSyncProgress(PhoneBluetoothSyncStage.RECONNECTING, 0, reconnectAttempt = attempt))
        }
        var accountSync: AccountSyncResult? = null
        var accountSyncWarning: String? = null
        var tokenUsageSyncWarning: String? = null
        var readerSyncWarning: String? = null
        suspend fun warningOrThrow(failure: Throwable, fallback: String): String {
            if (failure is CancellationException || failure is WatchSyncBlocked || isRetryableBluetoothDisconnect(failure)) throw failure
            // Explicit peer rejection is a terminal error, not a successful sync with a warning.
            if (failure is IllegalArgumentException) throw failure
            return failure.message ?: fallback
        }
        val completed = try {
            recovery.run {
                if (!opened) connect()
                syncSession = syncSession?.let { client.promoteLateIpSession(it, "$sessionId-ip-upgrade") }
                effectiveDevice = syncSession?.device ?: device
                syncSession?.recoveryGate?.tryAcquire()
                binding()
            }
            recovery.run {
            val active=binding()
            if(repository.bleDomains!=null && DomainFastPath.enabled(active.capabilities,syncSession?.isPersistent==true,forceFull,mode)) {
                val peer=active.response.getString("deviceId")
                val scope=domainScope()
                if(scope.isNotBlank()) {
                    val started=SystemClock.elapsedRealtimeNanos()
                    val roots=localRoots(peer)
                    val receipts=withContext(Dispatchers.IO){repository.bleDomains.receipts(scope,peer)}
                    val window=DomainFastPath(deviceId,peer,roots,receipts,requireNotNull(rootWindow).projectionVersions)
                    val preparedMs=(SystemClock.elapsedRealtimeNanos()-started)/1_000_000
                    withTimeout(READER_EXCHANGE_TIMEOUT_MS) {withContext(Dispatchers.IO) {
                        requireNotNull(syncSession).exchangeApplied(window.summaryRequest(),"$sessionId-domains-summary",window::acceptSummary)
                    }}
                    fastPath=window;fastPeer=peer;fastScope=scope;completedDomains.addAll(window.skipped)
                    debugLog.appendEvent("sync.domains.summary",sessionId,fields=mapOf("prepareMs" to preparedMs,"elapsedMs" to (SystemClock.elapsedRealtimeNanos()-started)/1_000_000,"rootCount" to roots.size,"remoteRootCount" to window.remoteRootCount,"missingRemote" to window.missingRemote.joinToString(","),"skipped" to window.skipped.joinToString(",")))
                }
            }
            }
            if (!skipped("account") && mode == LibrarySyncMode.MERGE && canSyncAccount()) {
                prepareAccountDomain(binding().response.getString("deviceId"),fastPath?.accountGrantWitness(fastPath?.remoteAccountRoot))
                reportProgress(onProgress, PhoneBluetoothSyncStage.SYNCING_ACCOUNT, 0)
                runCatching { recovery.run { syncAccount(effectiveDevice, syncSession, false, binding(),reuseGrant=fastPath!=null) } }
                    .onSuccess { accountSync = it.accountSync; completedDomains.add("account") }
                    .onFailure { accountSyncWarning = warningOrThrow(it, "账号授权同步失败") }
            }
            recovery.run {
                val active = binding()
                if (active.capabilities.books && repository.books != null && (!skipped("books") || !skipped("bookReading"))) {
                    reportProgress(onProgress,PhoneBluetoothSyncStage.SYNCING_BOOKS,0)
                    val bookSync = BookBluetoothSync(requireNotNull(repository.books), deviceId, File(appContext.cacheDir, "ble-books")) { request ->
                        val finishingBody=request.optString("phase")=="body" && request.optInt("chunkIndex")+1==request.optInt("chunkCount")
                        val timeout=if(request.optString("phase")=="manifest" || finishingBody)LIBRARY_FULL_SYNC_TIMEOUT_MS else READER_EXCHANGE_TIMEOUT_MS
                        exchange(request,timeoutMs=timeout, deviceAddress = effectiveDevice.address, sessionId = "$sessionId-books", syncSession = syncSession).response
                    }
                    bookSync.domainFastPath=fastPath!=null
                    bookSync.onApplied={if(it>0)locallyApplied=true}
                    bookSync.trace={stage->debugLog.appendEvent("sync.book.stage",sessionId,fields=mapOf("stage" to stage))}
                    val physicalSession=syncSession
                    if(active.capabilities.bookReadingDelta && physicalSession?.isPersistent==true) {
                        bookSync.enableReadingDelta(active.response.getString("deviceId")) { request,validate ->
                            withTimeout(READER_EXCHANGE_TIMEOUT_MS) {
                                withContext(Dispatchers.IO) { physicalSession.exchangeApplied(request,"$sessionId-books-commit",validate) }
                            }
                        }
                    }
                    booksApplied += bookSync.sync(syncBooks=!skipped("books"),syncReading=!skipped("bookReading"))
                    completedDomains.addAll(listOf("books","bookReading"))
                    reportProgress(onProgress,PhoneBluetoothSyncStage.SYNCING_BOOKS,100)
                }
            }
            // A retry starts a NEW cursor handshake. Never replay an old manifest/body closure.
            val libraryResult = if(skipped("library")) PhoneBluetoothSyncResult(effectiveDevice.name,effectiveDevice.address,libraryStats=LibrarySyncStats(0,0,0)) else recovery.run {
                syncLibrary(deviceAddress = effectiveDevice.address, syncSession = syncSession,
                    forceFull = forceFull || mode != LibrarySyncMode.MERGE, mode = mode,
                    onProgress = onProgress, binding = binding(), syncAuxiliary = false,
                    onLocalApplied = { if (it > 0) locallyApplied = true },
                    onLibraryManifest = { libraryDeclarations = it },
                    booksPrepared = true,
                    domainWitness = rootWindow,
                    resolveDeleteConflicts = resolveDeleteConflicts).also {completedDomains.add("library")}
            }
            var noteStats: NoteSyncStats? = null
            if (mode == LibrarySyncMode.MERGE) {
                runCatching { recovery.run {
                    val active = binding()
                    if (!skipped("reader") && (libraryDeclarations ?: active.response).optInt("version") >= 11 && (libraryDeclarations ?: active.response).optBoolean("supportsReaderPresets", true)) {
                        val confirmed=syncReaderPresets(effectiveDevice.address, sessionId, syncSession, active,onLocalApplied={if(it>0)locallyApplied=true}) { done, total ->
                            reportProgress(onProgress, PhoneBluetoothSyncStage.SYNCING_READER_RESOURCES, if (total <= 0) 100 else done * 100 / total)
                        }
                        if(confirmed)completedDomains.add("reader")
                    }
                } }.onFailure {
                    debugLog.appendEvent("sync.reader.failed",sessionId,fields=mapOf("errorClass" to it.javaClass.simpleName,"readerError" to it.message.orEmpty().replace(Regex("[0-9a-fA-F]{24,}"),"<id>").take(180)),throwable=it)
                    readerSyncWarning = warningOrThrow(it, "阅读器资源同步失败")
                }
                noteStats = if(skipped("notes")) NoteSyncStats(0,0,0,0) else recovery.run {
                    reportProgress(onProgress, PhoneBluetoothSyncStage.SYNCING_NOTES, 0)
                    syncNotes(effectiveDevice.address, sessionId, syncSession, libraryDeclarations?.let { binding().capabilities.withDeclarations(it) } ?: binding().capabilities,
                        onLocalApplied = { if (it > 0) locallyApplied = true }).also{completedDomains.add("notes")}
                }
                runCatching { recovery.run { if(!skipped("usage")){syncLlmTokenUsage(effectiveDevice, syncSession, binding());completedDomains.add("usage")} } }
                    .onFailure { tokenUsageSyncWarning = warningOrThrow(it, "词元用量同步失败") }
            }
            recovery.run { fastPath?.let { window->
                val roots=localRoots(requireNotNull(fastPeer))
                val request=window.commitRequest(roots,completedDomains)
                withTimeout(READER_EXCHANGE_TIMEOUT_MS) {withContext(Dispatchers.IO) {
                    requireNotNull(syncSession).exchangeApplied(request,"$sessionId-domains-commit") {window.acceptCommit(it,roots,completedDomains)}
                    debugLog.appendEvent("sync.domains.commit",sessionId,window.diagnostics(roots))
                }}
            }
            }
            recovery.run {
                binding()
                withTimeout(QUICK_EXCHANGE_TIMEOUT_MS) {
                    if(fastPath!=null) {
                        val ack=withContext(Dispatchers.IO){requireNotNull(syncSession).exchangeApplied(
                            BluetoothSyncProtocol.buildSessionControlRequest(LibrarySyncPayload.PROTOCOL_VERSION,BluetoothSyncProtocol.SESSION_PHASE_COMPLETE),"$sessionId-complete") {response->
                            require(response.opt("success")==true&&response.optString("action")=="syncSession"&&response.optString("phase")=="complete"&&response.optInt("version")==LibrarySyncPayload.PROTOCOL_VERSION){"手表未确认结束同步会话"}
                        }}
                        val proofs=requireNotNull(fastPath).publishable(localRoots(requireNotNull(fastPeer)),ack.appliedAckWritten)
                        withContext(Dispatchers.IO){requireNotNull(repository.bleDomains).publish(requireNotNull(fastScope),requireNotNull(fastPeer),proofs,completedDomains-requireNotNull(fastPath).skipped)}
                        if("account" in completedDomains)(accountSync?.accountStateRoot ?: fastPath?.finalRemoteAccountRoot)?.let{acknowledgeAccountDomain(requireNotNull(fastPeer),requireNotNull(fastPath?.accountGrantWitness(it)))}
                        debugLog.appendEvent("sync.domains.published",sessionId,fields=mapOf("domains" to proofs.keys.joinToString(",")))
                    } else syncSession?.complete("$sessionId-complete")
                }
            }
            libraryResult.copy(accountSync = accountSync, accountSyncWarning = accountSyncWarning,
                tokenUsageSyncWarning = tokenUsageSyncWarning, readerSyncWarning = readerSyncWarning, noteStats = noteStats)
        } catch (failure: Throwable) {
            // A broken transport is already closed; do not wait for a doomed abort round trip.
            if (!isRetryableBluetoothDisconnect(failure)) runCatching { withTimeout(QUICK_EXCHANGE_TIMEOUT_MS) { syncSession?.abort("$sessionId-abort") } }
            throw failure
        } finally {
            syncSession?.close()
            debugLog.appendEvent("sync.session.closed", sessionId)
        }
        onSyncActiveChanged(false) // The physical session is closed before any asynchronous cloud enqueue.
        val changed = completed.hasActualLibraryChanges() || locallyApplied || booksApplied > 0
        debugLog.appendEvent("sync.cloud.decision", sessionId, mapOf("actualChanges" to changed))
        if (changed) runCatching { onLibrarySyncCompleted() }.onFailure {
            debugLog.appendEvent("sync.cloud.schedule.failed", sessionId, fields = failureFields(it), throwable = it)
        }
        return completed
        } finally {onSyncActiveChanged(false)}
    }

    suspend fun syncSavedItems(type: PhoneSavedItemType): PhoneBluetoothSyncResult {
        val sessionId = BluetoothDebugLog.newSessionId("savedItems")
        debugLog.appendEvent(
            event = "sync.savedItems.start",
            sessionId = sessionId,
            fields = mapOf("type" to type.name)
        )
        return runCatching {
            val targetAddress = requireWatchBinding(sessionId)
            val exchange = exchange(
                JSONObject().apply {
                    put("version", 1)
                    put("action", BluetoothSyncProtocol.ACTION_PULL_SAVED_ITEMS)
                    put("nonce", System.currentTimeMillis().toString())
                    put("type", type.name)
                },
                sessionId = sessionId,
                deviceAddress = targetAddress
            )
            requireSuccess(exchange.response)
            val items = exchange.response.optJSONArray("items") ?: JSONArray()
            val importedCount = repository.replaceSavedItems(type, items)
            debugLog.appendEvent(
                event = "sync.savedItems.complete",
                sessionId = sessionId,
                fields = mapOf(
                    "device" to exchange.deviceName,
                    "type" to type.name,
                    "receivedItems" to items.length(),
                    "imported" to importedCount
                )
            )
            PhoneBluetoothSyncResult(
                deviceName = exchange.deviceName,
                importedCount = importedCount
            )
        }.onFailure { throwable ->
            debugLog.appendEvent(
                event = "sync.savedItems.failed",
                sessionId = sessionId,
                fields = mapOf("type" to type.name) + failureFields(throwable),
                throwable = throwable
            )
        }.getOrThrow()
    }

    suspend fun clearWatchLibrary(deviceAddress: String): PhoneBluetoothSyncResult {
        val sessionId = BluetoothDebugLog.newSessionId("clearWatchLibrary")
        val exchange = exchange(
            request = JSONObject().apply {
                put("version", LibrarySyncPayload.PROTOCOL_VERSION)
                put("action", BluetoothSyncProtocol.ACTION_CLEAR_LIBRARY)
                put("nonce", System.currentTimeMillis().toString())
            },
            timeoutMs = LIBRARY_SYNC_TIMEOUT_MS,
            sessionId = sessionId,
            deviceAddress = deviceAddress
        )
        requireSuccess(exchange.response)
        return PhoneBluetoothSyncResult(
            deviceName = exchange.deviceName,
            deviceAddress = exchange.deviceAddress
        )
    }

    internal suspend fun syncLibrary(
        deviceAddress: String? = null,
        syncSession: PhoneSyncSession? = null,
        forceFull: Boolean = false,
        mode: LibrarySyncMode = LibrarySyncMode.MERGE,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit = {},
        binding: BleSyncBindingSnapshot? = null,
        syncAuxiliary: Boolean = true,
        booksPrepared:Boolean=false,
        domainWitness:com.lightningstudio.watchrss.phone.data.ble.BleDomainStore.RootWindow?=null,
        onLocalApplied: (Int) -> Unit = {},
        onLibraryManifest: (JSONObject) -> Unit = {},
        resolveDeleteConflicts: suspend (List<PhoneSyncDeleteConflict>) -> Map<String, PhoneSyncConflictResolution> = {
            emptyMap()
        }
    ): PhoneBluetoothSyncResult {
        val sessionId = BluetoothDebugLog.newSessionId("syncLibrary")
        val activeBinding=bindingSnapshot(sessionId,deviceAddress,syncSession,binding)
        val targetAddress = activeBinding.address
        reportProgress(onProgress, PhoneBluetoothSyncStage.CONNECTING, 0)
        repository.repairImportedContentSourceStates()
        repository.repairBluetoothRssDuplicates()
        val bookStore=repository.books?.takeIf{activeBinding.capabilities.books}
        if(!booksPrepared)bookStore?.ensureRegistered()
        val exclusions=bookStore?.bluetoothExclusions()
        val bookChapterIds=exclusions?.first.orEmpty()
        val bookSources=exclusions?.second.orEmpty()
        var canonicalIds=activeBinding.capabilities.canonicalRssIds
        lateinit var identityProjection:BluetoothArticleIdentityProjection
        reportProgress(onProgress, PhoneBluetoothSyncStage.CONNECTING, 8)
        var sentArticles = emptyList<PhoneArticleEntity>()
        var receivedArticles = 0
        var merged = 0
        var receivedSourcesCount = 0
        var mergedSources = 0
        var remoteSeqApplied = 0L
        var activePeerDeviceId = ""
        var conflictMergeResolutions = emptyMap<String, PhoneSyncConflictResolution>()
        var syncWindow: PhoneLibrarySyncWindow? = null
        var remoteFullManifest = emptyList<ArticleSyncManifestEntry>()
        debugLog.appendEvent(
            event = "sync.library.start",
            sessionId = sessionId,
            fields = mapOf(
                "protocol" to LibrarySyncPayload.PROTOCOL_VERSION,
                "targetAddress" to deviceAddress.orEmpty()
            )
        )
        return runCatching {
            val initialCursor = repository.getLibrarySyncCursor(null)
            val exchange = exchangeLibrary(
                // A normal merge may become full only after cursor/identity negotiation.
                // Use the bounded full-transfer budget for that entire transaction too.
                timeoutMs = LIBRARY_FULL_SYNC_TIMEOUT_MS,
                cursorRequest = LibrarySyncPayload.buildCursorRequest(
                    deviceId = deviceId,
                    cursor = LibrarySyncCursor(
                        localMaxSeq = initialCursor.localMaxSeq,
                        lastRemoteSeqApplied = 0L,
                        lastLocalSeqAckedByPeer = 0L
                    )
                ),
                buildManifestRequest = { peerDeviceId, cursorResponse ->
                    activePeerDeviceId = peerDeviceId
                    canonicalIds=cursorResponse?.let { activeBinding.capabilities.withDeclarations(it).canonicalRssIds }?:activeBinding.capabilities.canonicalRssIds
                    identityProjection=repository.bluetoothIdentityProjection(canonicalIds)
                    val identityChanged=repository.bluetoothIdentityModeChanged(peerDeviceId,canonicalIds)
                    val remoteCursor = cursorResponse
                        ?.let(LibrarySyncPayload::parseCursor)
                        ?: LibrarySyncCursor(0L, 0L, 0L)
                    if (mode != LibrarySyncMode.MERGE) {
                        require(cursorResponse?.optBoolean("supportsAuthoritativeLibraryReplace") == true) {
                            "手表端版本不支持单向覆盖，请先升级手表端"
                        }
                    }
                    val preparedWindow = repository.prepareLibrarySyncWindow(
                        peerDeviceId = peerDeviceId,
                        peerAppliedLocalSeq = if (forceFull || identityChanged) 0L else remoteCursor.lastRemoteSeqApplied,
                        excludeRegisteredBooks=activeBinding.capabilities.books
                    )
                    val localWindow = if (forceFull || identityChanged || mode != LibrarySyncMode.MERGE) {
                        preparedWindow.copy(
                            articleManifest = preparedWindow.fullArticleManifest,
                            rssSources = repository.getRssSourcesForSync(),
                            fullSnapshot = true,
                            fromSeqExclusive = 0L,
                            fallbackReason = if (mode == LibrarySyncMode.MERGE) "forcedFull" else mode.protocolValue
                        )
                    } else preparedWindow
                    val window=localWindow.copy(articleManifest=localWindow.articleManifest.filterNot{it.articleId in bookChapterIds}.map(identityProjection::manifest),
                        fullArticleManifest=localWindow.fullArticleManifest.filterNot{it.articleId in bookChapterIds}.map(identityProjection::manifest),
                        rssSources=localWindow.rssSources.filterNot{it.url in bookSources || (activeBinding.capabilities.books && it.url==com.lightningstudio.watchrss.phone.data.model.ImportedContentIds.ROOT_SOURCE_URL)})
                    window.also {
                        syncWindow = window
                        debugLog.appendEvent(
                            event = "sync.library.window.prepared",
                            sessionId = sessionId,
                            fields = mapOf(
                                "peer" to peerDeviceId,
                                "deltaArticles" to window.articleManifest.size,
                                "fullArticles" to window.fullArticleManifest.size,
                                "deltaSources" to window.rssSources.size,
                                "fullSnapshot" to window.fullSnapshot,
                                "fromSeqExclusive" to window.fromSeqExclusive,
                                "toSeqInclusive" to window.toSeqInclusive,
                                "peerAckedSeq" to window.peerAckedSeq,
                                "remoteLocalMaxSeq" to remoteCursor.localMaxSeq,
                                "remoteLastRemoteSeqApplied" to remoteCursor.lastRemoteSeqApplied,
                                "remoteLastLocalSeqAckedByPeer" to remoteCursor.lastLocalSeqAckedByPeer,
                                "fallbackReason" to window.fallbackReason
                            )
                        )
                    }.let {
                        val localCursor = repository.getLibrarySyncCursor(peerDeviceId)
                        LibrarySyncPayload.buildManifestRequestFromEntries(
                            deviceId = deviceId,
                            articleManifest = window.articleManifest,
                            rssSources = window.rssSources,
                            changeSequence = LibraryChangeSequence(
                                fromSeqExclusive = window.fromSeqExclusive,
                                toSeqInclusive = window.toSeqInclusive,
                                fullSnapshot = window.fullSnapshot,
                                fallbackReason = window.fallbackReason
                            ),
                            cursor = LibrarySyncCursor(
                                localMaxSeq = localCursor.localMaxSeq,
                                lastRemoteSeqApplied = localCursor.lastRemoteSeqApplied,
                                lastLocalSeqAckedByPeer = localCursor.lastLocalSeqAckedByPeer
                            )
                        ).apply {
                            put("forceFull", forceFull || mode != LibrarySyncMode.MERGE)
                            put("libraryMode", mode.protocolValue)
                        }
                    }
                },
                buildArticleRequests = { manifestResponse, supportsArticleBatches ->
                    requireSuccess(manifestResponse)
                    val remoteProtocolVersion = manifestResponse.optInt("version", 0)
                    require(remoteProtocolVersion >= LibrarySyncPayload.MIN_SUPPORTED_WATCH_PROTOCOL_VERSION) {
                        "手表资料库同步协议为 v$remoteProtocolVersion，至少需要 v${LibrarySyncPayload.MIN_SUPPORTED_WATCH_PROTOCOL_VERSION}；请先升级手表端"
                    }
                    val window = syncWindow ?: error("同步窗口尚未准备")
                    val remoteManifest = LibrarySyncPayload.parseArticleManifest(manifestResponse)
                    if(canonicalIds && remoteManifest.isNotEmpty()) {
                        val migrations=repository.acceptBluetoothAliases(remoteManifest)
                        // Only proven migrations amend the projection; no second whole-database read.
                        identityProjection.acceptAliases(migrations)
                    }
                    remoteFullManifest = remoteManifest
                    val supportsChunkedBodies = manifestResponse.optBoolean("supportsChunkedBodies", false) &&
                        manifestResponse.optInt("version") > LibrarySyncPayload.LEGACY_PROTOCOL_VERSION
                    val supportsMetadataOnlyArticles = LibrarySyncPayload.supportsMetadataOnlyArticles(manifestResponse)
                    reportProgress(onProgress, PhoneBluetoothSyncStage.TRANSFERRING, 28)
                    val deleteConflicts = if (mode == LibrarySyncMode.MERGE) {
                        repository.findDeleteConflicts(remoteManifest.map(identityProjection::localManifest))
                    } else {
                        emptyList()
                    }
                    val conflictResolutions = if (deleteConflicts.isNotEmpty()) {
                        resolveDeleteConflicts(deleteConflicts)
                    } else {
                        emptyMap()
                    }
                    val conflictPlan = repository.prepareDeleteConflictResolutions(
                        remoteManifest = remoteManifest.map(identityProjection::localManifest),
                        resolutions = conflictResolutions
                    )
                    conflictMergeResolutions = conflictPlan.mergeResolutions
                    if (supportsChunkedBodies) {
                        val watchRequests = LibrarySyncPayload.parseBodyRequests(manifestResponse)
                        // The sync window was captured before the remote manifest round trip. Refresh
                        // the relevant local rows so a body deleted in the meantime cannot be treated
                        // as reusable and incorrectly turn a full-body request into metadata-only.
                        val remoteIds=remoteManifest.map { identityProjection.local(it.articleId) }
                        val currentLocalManifest = (if(activeBinding.capabilities.books && canonicalIds)repository.bluetoothManifestsForRequests(remoteIds,domainWitness)
                            else repository.getArticleManifestsForSync(remoteIds)).map(identityProjection::manifest)
                        val phoneRequests = if (mode == LibrarySyncMode.PHONE_TO_WATCH_REPLACE) {
                            emptyList()
                        } else mergeBodyRequests(
                            defaultRequests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
                                localManifest = currentLocalManifest,
                                remoteManifest = remoteManifest,
                                maxBodyRequestChunks = LibrarySyncPayload.MAX_BODY_REQUEST_CHUNKS_PER_SYNC,
                                supportsMetadataOnlyArticles = supportsMetadataOnlyArticles,
                                supportsFullMetadataOnlyArticles = activeBinding.capabilities.withDeclarations(manifestResponse).fullMetadataOnly
                            ).filterNot { identityProjection.local(it.articleId) in conflictPlan.suppressedRemoteArticleIds },
                            forcedRequests = conflictPlan.forcedRemoteRequests.map(identityProjection::request)
                        )
                        sentArticles = repository.getArticlesForSync(
                            (watchRequests.map { identityProjection.local(it.articleId) } + conflictPlan.outgoingArticleIds).filterNot {it in bookChapterIds}
                        ).map(identityProjection::outgoing)
                        debugLog.appendEvent(
                            event = "sync.library.manifest.received",
                            sessionId = sessionId,
                            fields = mapOf(
                                "remoteManifest" to remoteManifest.size,
                                "watchBodyRequests" to watchRequests.size,
                                "phoneBodyRequests" to phoneRequests.size,
                                "phoneMetadataOnlyRequests" to phoneRequests.count { it.metadataOnly },
                                "phoneBodyRequestChunks" to phoneRequests.sumOf { it.chunkIndexes.size },
                                "diffArticles" to sentArticles.size,
                                "deleteConflicts" to deleteConflicts.size,
                                "conflictOutgoing" to conflictPlan.outgoingArticleIds.size,
                                "conflictRemoteRequests" to conflictPlan.forcedRemoteRequests.size,
                                "chunked" to true
                            )
                        )
                        return@exchangeLibrary LibrarySyncPayload.buildChunkedArticleRequestFrames(
                            deviceId = deviceId,
                            articles = sentArticles,
                            articleRequests = watchRequests,
                            bodyRequests = phoneRequests,
                            useBatches = supportsArticleBatches,
                            articleAliases = identityProjection.outgoingAliases(sentArticles)
                        )
                    }
                    if (conflictPlan.forcedRemoteRequests.isNotEmpty()) {
                        error("手表端版本不支持本次删除冲突处理，请更新手表端后重试")
                    }
                    val localArticles = repository.getArticlesForSync().filterNot {it.articleId in bookChapterIds}.map(identityProjection::outgoing)
                    val diffArticleIds = LibrarySyncPayload.filterArticlesNeedingSync(localArticles, remoteManifest)
                        .filterNot { identityProjection.local(it.articleId) in conflictPlan.suppressedRemoteArticleIds }
                        .mapTo(linkedSetOf()) { it.articleId }
                    diffArticleIds += conflictPlan.outgoingArticleIds.map(identityProjection::wire)
                    sentArticles = repository.getArticlesForSync(diffArticleIds.map(identityProjection::local)).map(identityProjection::outgoing)
                    debugLog.appendEvent(
                        event = "sync.library.manifest.received",
                        sessionId = sessionId,
                        fields = mapOf(
                            "remoteManifest" to remoteManifest.size,
                            "diffArticles" to sentArticles.size,
                            "deleteConflicts" to deleteConflicts.size,
                            "conflictOutgoing" to conflictPlan.outgoingArticleIds.size,
                            "chunked" to false
                        )
                    )
                    val frames = LibrarySyncPayload.buildArticleRequestFrames(
                        deviceId = deviceId,
                        articles = sentArticles,
                        useBatches = supportsArticleBatches,
                        articleAliases = identityProjection.outgoingAliases(sentArticles)
                    )
                    if (!supportsArticleBatches && frames.any { BluetoothSyncProtocol.encodedSize(it) > BluetoothSyncProtocol.MAX_FRAME_BYTES }) {
                        error("手表端版本不支持分批同步，当前资料库超过蓝牙单帧限制，请更新手表端后重试")
                    }
                    frames
                },
                deviceAddress = targetAddress,
                syncSession = syncSession,
                onProgress = onProgress,
                sessionId = sessionId,
                applyResponse = { exchange ->
                    val window = syncWindow ?: error("同步窗口尚未准备")
                    reportProgress(onProgress, PhoneBluetoothSyncStage.VERIFYING, 90)
                    requireSuccess(exchange.response)
                    val chunkedResponse = exchange.response.optInt("version") > LibrarySyncPayload.LEGACY_PROTOCOL_VERSION &&
                        exchange.response.optJSONArray("articles") != null &&
                        (exchange.response.optJSONArray("articles")?.let { array ->
                            (0 until array.length()).any { index ->
                                array.optJSONObject(index)?.has("body") == true
                            }
                        } == true)
                    val phoneIsTarget = mode != LibrarySyncMode.PHONE_TO_WATCH_REPLACE
                    val received = if (!phoneIsTarget) {
                        receivedArticles = 0
                        0
                    } else if (chunkedResponse) {
                        val chunked = LibrarySyncPayload.parseChunkedArticles(exchange.response).map(identityProjection::incoming)
                        receivedArticles = chunked.size
                        repository.mergeChunkedArticlesFromSync(
                            chunked,
                            conflictMergeResolutions,
                            authoritative = mode == LibrarySyncMode.WATCH_TO_PHONE_REPLACE
                        )
                    } else {
                        val articles = LibrarySyncPayload.parseArticles(exchange.response).map(identityProjection::incoming)
                        receivedArticles = articles.size
                        repository.mergeArticlesFromSync(
                            articles,
                            conflictMergeResolutions,
                            authoritative = mode == LibrarySyncMode.WATCH_TO_PHONE_REPLACE
                        )
                    }
                    val receivedSources = LibrarySyncPayload.parseRssSources(exchange.manifestResponse)
                    reportProgress(onProgress, PhoneBluetoothSyncStage.VERIFYING, 94)
                    merged = received
                    receivedSourcesCount = receivedSources.size
                    mergedSources = when (mode) {
                        LibrarySyncMode.PHONE_TO_WATCH_REPLACE -> 0
                        LibrarySyncMode.WATCH_TO_PHONE_REPLACE -> repository.mergeAuthoritativeRssSources(receivedSources)
                        LibrarySyncMode.MERGE -> repository.mergeRssSourcesFromSync(receivedSources)
                    }
                    if (mode == LibrarySyncMode.WATCH_TO_PHONE_REPLACE) {
                        val pruned=repository.finalizeAuthoritativeLibrarySnapshot(
                            retainedArticleIds = remoteFullManifest.map { identityProjection.local(it.articleId) }+bookChapterIds,
                            retainedSourceUrls = receivedSources.map { it.url }+bookSources
                        )
                        merged+=pruned.first;mergedSources+=pruned.second
                    }
                    onLocalApplied(merged + mergedSources)
                    val remoteChangeSequence = LibrarySyncPayload.parseChangeSequence(exchange.manifestResponse)
                    remoteSeqApplied = remoteChangeSequence.toSeqInclusive
                    if (mode != LibrarySyncMode.MERGE) {
                        repository.resetLibrarySyncPeerState()
                    }
                    reportProgress(onProgress, PhoneBluetoothSyncStage.VERIFYING, 96)
                }
            )
            // Choosing a live watch copy is resolved only after its verified body arrives.
            // Return the newly persisted decision in this same user sync, before success.
            // On lost ACK the durable row/change journal is replayed idempotently next sync.
            if (conflictMergeResolutions.isNotEmpty()) {
                val resolved = repository.getArticlesForSync(conflictMergeResolutions.keys.toList()).map(identityProjection::outgoing)
                check(resolved.size == conflictMergeResolutions.size) { "冲突正文尚未完整保存，请直接重试同步" }
                val returnedDecision = exchangeLibrary(
                    timeoutMs = LIBRARY_SYNC_TIMEOUT_MS,
                    cursorRequest = LibrarySyncPayload.buildCursorRequest(deviceId, LibrarySyncCursor(0L, 0L, 0L)),
                    buildManifestRequest = { _, _ ->
                        LibrarySyncPayload.buildManifestRequestFromEntries(
                            deviceId = deviceId,
                            articleManifest = repository.getArticleManifestsForSync(conflictMergeResolutions.keys.toList()).map(identityProjection::manifest),
                            rssSources = emptyList(),
                            changeSequence = LibraryChangeSequence(0L, 0L, false, "conflictDecision")
                        )
                    },
                    buildArticleRequests = { response, batches ->
                        requireSuccess(response)
                        LibrarySyncPayload.buildChunkedArticleRequestFrames(deviceId, resolved, emptyList(), emptyList(), batches,
                            identityProjection.outgoingAliases(resolved))
                    },
                    deviceAddress = targetAddress,
                    syncSession = syncSession,
                    onProgress = onProgress,
                    sessionId = "$sessionId-conflict-return",
                    applyResponse = { response -> requireSuccess(response.response) }
                )
                requireSuccess(returnedDecision.manifestResponse)
                requireSuccess(returnedDecision.response)
                sentArticles = (sentArticles + resolved).distinctBy { it.articleId }
                debugLog.appendEvent("sync.library.conflict.returned", sessionId, mapOf("count" to resolved.size))
            }
            onLibraryManifest(exchange.manifestResponse)
            val window = syncWindow ?: error("同步窗口尚未准备")
            // Only a successful library response ACK may publish peer cursors/echo proof.
            repository.rememberBluetoothReceipts(activePeerDeviceId,remoteFullManifest.map(identityProjection::localManifest))
            repository.markLibrarySyncSuccess(
                peerDeviceId = activePeerDeviceId.ifBlank {
                    exchange.deviceAddress.ifBlank { exchange.deviceName }
                },
                localSeqToInclusive = if (mode == LibrarySyncMode.WATCH_TO_PHONE_REPLACE) 0L else window.toSeqInclusive,
                remoteSeqToInclusive = if (mode == LibrarySyncMode.PHONE_TO_WATCH_REPLACE) 0L else remoteSeqApplied,
                remoteProtocolVersion = exchange.manifestResponse.optInt("version"),
                fullSnapshot = window.fullSnapshot,
                canonicalIds = canonicalIds,
                projectionDefinition = window.projectionDefinition
            )
            debugLog.appendEvent(
                event = "sync.library.complete",
                sessionId = sessionId,
                fields = mapOf(
                    "device" to exchange.deviceName,
                    "sent" to sentArticles.size,
                    "received" to receivedArticles,
                    "merged" to merged,
                    "sourcesSent" to window.rssSources.size,
                    "sourcesReceived" to receivedSourcesCount,
                    "sourcesMerged" to mergedSources,
                    "localSeqMax" to window.toSeqInclusive,
                    "peerAckedSeq" to window.peerAckedSeq,
                    "remoteSeqApplied" to remoteSeqApplied,
                    "deltaArticleCount" to window.articleManifest.size,
                    "deltaSourceCount" to window.rssSources.size,
                    "fullSnapshot" to window.fullSnapshot,
                    "fallbackReason" to window.fallbackReason
                )
            )
            val readerSyncWarning = if (syncAuxiliary && mode == LibrarySyncMode.MERGE && exchange.manifestResponse.optInt("version") >= 11 &&
                exchange.manifestResponse.optBoolean("supportsReaderPresets", true)
            ) {
                runCatching {
                    syncReaderPresets(
                        deviceAddress = exchange.deviceAddress,
                        parentSessionId = sessionId,
                        syncSession = syncSession,
                        binding = activeBinding
                    ) { completed, total ->
                        val percent = if (total <= 0) 100 else (completed * 100 / total)
                        reportProgress(
                            onProgress,
                            PhoneBluetoothSyncStage.SYNCING_READER_RESOURCES,
                            percent
                        )
                    }
                }.exceptionOrNull()?.let { throwable ->
                    if (throwable is WatchSyncBlocked) throw throwable
                    debugLog.appendEvent(
                        event = "sync.reader.failed",
                        sessionId = sessionId,
                        fields = failureFields(throwable),
                        throwable = throwable
                    )
                    throwable.message ?: "阅读器资源同步失败"
                }
            } else {
                null
            }
            val noteStats = if (syncAuxiliary && mode == LibrarySyncMode.MERGE) {
                reportProgress(onProgress, PhoneBluetoothSyncStage.SYNCING_NOTES, 0)
                syncNotes(
                    deviceAddress = exchange.deviceAddress,
                    parentSessionId = sessionId,
                    syncSession = syncSession,
                    capabilities = activeBinding.capabilities.withDeclarations(exchange.manifestResponse)
                ).also {
                    reportProgress(onProgress, PhoneBluetoothSyncStage.SYNCING_NOTES, 100)
                }
            } else null
            reportProgress(onProgress, PhoneBluetoothSyncStage.VERIFYING, 100)
            PhoneBluetoothSyncResult(
                deviceName = exchange.deviceName,
                deviceAddress = exchange.deviceAddress,
                libraryStats = LibrarySyncStats(
                    sent = sentArticles.size,
                    received = receivedArticles,
                    merged = merged,
                    sourcesSent = window.rssSources.size,
                    sourcesReceived = receivedSourcesCount,
                    sourcesMerged = mergedSources
                ),
                noteStats = noteStats,
                readerSyncWarning = readerSyncWarning
            )
        }.onFailure { throwable ->
            debugLog.appendEvent(
                event = "sync.library.failed",
                sessionId = sessionId,
                fields = mapOf(
                    "errorClass" to throwable::class.java.name,
                    "message" to throwable.message.orEmpty()
                ),
                throwable = throwable
            )
        }.getOrThrow()
    }

    internal suspend fun syncNotes(
        deviceAddress: String,
        parentSessionId: String,
        syncSession: PhoneSyncSession? = null,
        capabilities: BleSyncCapabilities = BleSyncCapabilities(),
        onLocalApplied: (Int) -> Unit = {}
    ): NoteSyncStats = withContext(Dispatchers.IO) {
        val sessionId="$parentSessionId-notes"
        val localNotes=noteRepository.allNotes()
        val localAssets=noteAssetInventory(localNotes)
        var remoteAssets=emptyMap<String,NoteAssetInventoryEntry>()
        val changed=if(capabilities.noteManifest) {
            val inventory=exchange(NoteSyncPayload.inventory(deviceId,localNotes,localAssets.takeIf { capabilities.noteAssets }),
                sessionId="$sessionId-manifest",deviceAddress=deviceAddress,syncSession=syncSession).response
            requireSuccess(inventory)
            require(inventory.optString("action")==NoteSyncPayload.ACTION_SYNC_NOTES && inventory.optString("phase")=="manifest") { "手表笔记清单响应无效" }
            if(capabilities.noteAssets)remoteAssets=NoteAssetInventoryEntry.read(inventory.optJSONArray("assetManifest"))
            NoteSyncPayload.differingNotes(localNotes,inventory.getJSONArray("noteManifest"))
        } else localNotes
        val request=if(capabilities.noteManifest)NoteSyncPayload.delta(deviceId,localNotes,changed)
            else NoteSyncPayload.manifest(deviceId,localNotes).apply { if(capabilities.noteAssets)put("assetManifest",org.json.JSONArray().apply { localAssets.forEach { put(it.json()) } }) }
        val response=exchange(request,sessionId=sessionId,deviceAddress=deviceAddress,syncSession=syncSession).response
        requireSuccess(response)
        require(response.optString("action")==NoteSyncPayload.ACTION_SYNC_NOTES) { "手表不支持备忘录同步，请升级手表端" }
        if(capabilities.noteManifest)require(response.optString("phase")=="delta") { "手表笔记差量响应无效" }
        if(capabilities.noteAssets && !capabilities.noteManifest)remoteAssets=NoteAssetInventoryEntry.read(response.optJSONArray("assetManifest"))
        val remoteDevice=response.optString("deviceId").ifBlank { "watch" }
        val incoming=NoteSyncPayload.decodeNotes(response)
        var conflicts=0;var applied=0
        for(note in incoming) {
            val previous=noteRepository.note(note.noteId)
            if(noteRepository.applyRemote(note,remoteDevice) is MarkdownMergeResult.Conflict)conflicts++
            if(previous!=noteRepository.note(note.noteId))applied++
        }
        onLocalApplied(applied)
        syncNoteAssets(localAssets,remoteAssets,deviceAddress,sessionId,syncSession)
        NoteSyncStats(changed.size,incoming.size,response.optInt("applied"),conflicts,applied).also { stats ->
            debugLog.appendEvent("sync.notes.complete",sessionId,fields=mapOf("sent" to stats.sent,"received" to stats.received,
                "appliedOnWatch" to stats.appliedOnWatch,"appliedOnPhone" to applied,"assetChunksSkipped" to localAssets.count { remoteAssets[it.storageKey]==it }))
        }
    }

    private suspend fun noteAssetInventory(notes:List<com.lightningstudio.watchrss.phone.data.note.NoteEntity>):List<NoteAssetInventoryEntry> {
        val keys=linkedSetOf<String>()
        for(note in notes.filterNot { it.deleted }) {
            keys+=noteRepository.assets(note.noteId).filterNot { it.deleted }.map { it.storageKey }.filter(NoteAssetSyncPayload::isSafeStorageKey)
            keys+=NoteAssetSyncPayload.referencedStorageKeys(note.markdown)
        }
        return keys.mapNotNull { key -> assetHashes.inventory(File(appContext.filesDir,"notes/assets/$key"),key) }
    }

    private suspend fun syncNoteAssets(
        localAssets:List<NoteAssetInventoryEntry>,remoteAssets:Map<String,NoteAssetInventoryEntry>,
        deviceAddress:String,parentSessionId:String,syncSession:PhoneSyncSession?
    ) {
        for(asset in localAssets) {
            if(remoteAssets[asset.storageKey]==asset)continue
            val file=File(appContext.filesDir,"notes/assets/${asset.storageKey}")
            check(assetHashes.inventory(file,asset.storageKey)==asset) { "笔记图片已变化，请重试" }
            val chunks=((asset.size+NoteAssetSyncPayload.CHUNK_BYTES-1)/NoteAssetSyncPayload.CHUNK_BYTES).toInt().coerceAtLeast(1)
            file.inputStream().buffered().use { input ->
                for(index in 0 until chunks) {
                    if(syncSession==null)delay(READER_RECONNECT_DELAY_MS)
                    val response=exchange(NoteAssetSyncPayload.chunk(asset.storageKey,asset.sha256,index,chunks,input.readNoteAssetChunk(NoteAssetSyncPayload.CHUNK_BYTES)),
                        deviceAddress=deviceAddress,sessionId="$parentSessionId-asset-${asset.storageKey}-$index",syncSession=syncSession).response
                    requireSuccess(response);require(response.optString("action")==NoteAssetSyncPayload.ACTION) { "手表不支持备忘录图片同步，请升级手表端" }
                    if(response.optBoolean("alreadyPresent"))break
                }
            }
        }
    }

    internal suspend fun syncReaderPresets(
        deviceAddress: String,
        parentSessionId: String = BluetoothDebugLog.newSessionId("syncReader"),
        syncSession: PhoneSyncSession? = null,
        binding: BleSyncBindingSnapshot? = null,
        onLocalApplied:(Int)->Unit={},
        onResourceProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ):Boolean {
        bindingSnapshot(parentSessionId,deviceAddress,syncSession,binding)
        if (readerPresetRepository.exportSnapshot().backgrounds.any {
                !it.deleted && readerPresetRepository.resourceStore.backgroundFile(it.masterFileName) != null
            }) {
            val capabilities = client.capabilitiesFor(deviceAddress)
                ?: throw WatchBackgroundPreparationException("未获取到手表能力，请重新连接手表")
            watchBackgroundTranscoder.prepareAll(capabilities)
        }
        val snapshot = readerPresetRepository.exportSnapshot()
        if (syncSession == null) delay(READER_RECONNECT_DELAY_MS)
        val manifest = exchangeReaderFrame(
            request = ReaderPresetSyncPayload.buildManifest(snapshot, deviceId),
            sessionId = "$parentSessionId-reader-manifest",
            deviceAddress = deviceAddress,
            syncSession = syncSession
        ).response
        requireSuccess(manifest)
        ReaderPresetSyncPayload.mergeManifest(manifest, readerPresetRepository)

        var resourcesToPush = ReaderPresetSyncPayload.missingResources(manifest)
        val resourcesToPull = ReaderPresetSyncPayload.locallyMissing(readerPresetRepository)
        fun describe(rows:List<ResourceDescriptor>)=rows.joinToString(","){"${it.kind}:${it.sha256.take(8)}:${it.byteCount}"}
        debugLog.appendEvent("sync.reader.inventory",parentSessionId,mapOf("phoneMissing" to describe(resourcesToPull),"watchMissing" to describe(resourcesToPush)) + ReaderPresetSyncPayload.backgroundInventory(manifest))
        val totalChunks = (resourcesToPush + resourcesToPull).sumOf { resource ->
            ((resource.byteCount + ReaderPresetSyncPayload.CHUNK_BYTES - 1L) /
                ReaderPresetSyncPayload.CHUNK_BYTES).toInt().coerceAtLeast(1)
        }
        var completedChunks = 0
        onResourceProgress(completedChunks, totalChunks)

        resourcesToPull.forEachIndexed { resourceIndex, resource ->
                check(resourcesToPush.none{it.kind==resource.kind&&it.fileName==resource.fileName&&it.sha256==resource.sha256}) {
                    "两端均缺少${if(resource.kind=="background")"背景原图" else "字体"}（${resource.sha256.take(8)}），请重新导入原文件；资源记录已保留"
                }
                var chunkIndex = 0
                var complete = false
                while (!complete) {
                    if (syncSession == null) delay(READER_RECONNECT_DELAY_MS)
                    val response = exchangeReaderFrame(
                        request = ReaderPresetSyncPayload.pullRequest(resource, chunkIndex),
                        sessionId = "$parentSessionId-reader-pull-$resourceIndex-$chunkIndex",
                        deviceAddress = deviceAddress,
                        syncSession = syncSession
                    ).response
                    requireSuccess(response)
                    complete = ReaderPresetSyncPayload.applyPulledChunk(
                        response,
                        resource,
                        readerPresetRepository
                    )
                    if(complete)onLocalApplied(1)
                    chunkIndex += 1
                    completedChunks += 1
                    onResourceProgress(completedChunks.coerceAtMost(totalChunks), totalChunks)
                }
            }
        // Pull a peer's master before trying to push a derived variant that is missing locally.
        // Otherwise two complementary inventories can fail despite holding every required original.
        if(resourcesToPull.any{it.kind=="background"}) {
            val capabilities=client.capabilitiesFor(deviceAddress)
                ?: throw WatchBackgroundPreparationException("未获取到手表能力，请重新连接手表")
            watchBackgroundTranscoder.prepareAll(capabilities)
            val refreshed=exchangeReaderFrame(ReaderPresetSyncPayload.buildManifest(readerPresetRepository.exportSnapshot(),deviceId),"$parentSessionId-reader-recovered-manifest",deviceAddress,syncSession).response
            requireSuccess(refreshed);ReaderPresetSyncPayload.mergeManifest(refreshed,readerPresetRepository)
            resourcesToPush=ReaderPresetSyncPayload.missingResources(refreshed)
        }
        resourcesToPush.forEachIndexed { resourceIndex, resource ->
            ReaderPresetSyncPayload.pushFrames(readerPresetRepository, resource)
                .forEachIndexed { chunkIndex, frame ->
                    if (syncSession == null) delay(READER_RECONNECT_DELAY_MS)
                    val ack = exchangeReaderFrame(
                        request = frame,
                        sessionId = "$parentSessionId-reader-push-$resourceIndex-$chunkIndex",
                        deviceAddress = deviceAddress,
                        syncSession = syncSession
                    ).response
                    requireSuccess(ack)
                    require(ack.optBoolean("received")) { "手表未确认收到资源分块" }
                    require(ack.optString("chunkSha256") == frame.optString("chunkSha256")) {
                        "手表资源分块 ACK 校验失败"
                    }
                    if (chunkIndex == frame.optInt("chunkCount") - 1) {
                        require(ack.optBoolean("applied")) { "手表未确认资源完整落盘" }
                    }
                    completedChunks += 1
                    onResourceProgress(completedChunks, totalChunks)
                }
        }

        if(resourcesToPush.isNotEmpty() && binding?.capabilities?.domainFastPath==true) {
            val confirmed=exchangeReaderFrame(ReaderPresetSyncPayload.buildManifest(readerPresetRepository.exportSnapshot(),deviceId),"$parentSessionId-reader-confirm",deviceAddress,syncSession).response
            requireSuccess(confirmed)
            ReaderPresetSyncPayload.mergeManifest(confirmed,readerPresetRepository)
            return ReaderPresetSyncPayload.missingResources(confirmed).isEmpty()
        }
        return resourcesToPush.isEmpty()
    }

    private suspend fun exchangeReaderFrame(
        request: JSONObject,
        sessionId: String,
        deviceAddress: String,
        syncSession: PhoneSyncSession? = null
    ): BluetoothSyncExchange {
        var lastFailure: Throwable? = null
        repeat(READER_EXCHANGE_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(READER_EXCHANGE_RETRY_DELAY_MS)
            }
            val attemptSessionId = if (attempt == 0) sessionId else "$sessionId-retry-$attempt"
            try {
                return exchange(
                    request = request,
                    timeoutMs = READER_EXCHANGE_TIMEOUT_MS,
                    sessionId = attemptSessionId,
                    deviceAddress = deviceAddress,
                    syncSession = syncSession
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                lastFailure = throwable
                debugLog.appendEvent(
                    event = "sync.reader.exchange.retry",
                    sessionId = attemptSessionId,
                    fields = failureFields(throwable) + mapOf(
                        "attempt" to attempt + 1,
                        "maxAttempts" to READER_EXCHANGE_ATTEMPTS
                    ),
                    throwable = throwable
                )
            }
        }
        throw checkNotNull(lastFailure)
    }

    suspend fun updateReaderPresetPreview(
        deviceAddress: String,
        sessionId: String,
        sequence: Long,
        preset: ReaderPreset
    ): String {
        requireWatchBinding(sessionId, deviceAddress)
        delay(READER_RECONNECT_DELAY_MS)
        val exchange = exchange(
            request = ReaderPresetPreviewPayload.update(sessionId, sequence, preset),
            timeoutMs = PREVIEW_EXCHANGE_TIMEOUT_MS,
            sessionId = "$sessionId-preview-$sequence",
            deviceAddress = deviceAddress
        )
        requireSuccess(exchange.response)
        require(exchange.response.optString("sessionId") == sessionId) {
            "手表预览会话校验失败"
        }
        require(exchange.response.optLong("sequence") == sequence) {
            "手表预览更新序号校验失败"
        }
        return exchange.deviceName
    }

    suspend fun streamReaderPresetPreview(
        deviceAddress: String,
        sessionId: String,
        initialPreset: ReaderPreset,
        updates: ReceiveChannel<ReaderPreset>,
        onConnected: (String) -> Unit,
        onApplied: (Long) -> Unit,
        onStage: (String) -> Unit = {}
    ) {
        requireWatchBinding(sessionId, deviceAddress)
        try {
            streamPreparedReaderPresetPreview(deviceAddress, sessionId, initialPreset, updates, onConnected, onApplied, onStage)
        } catch (failure: Exception) {
            // Preparation can be cancelled before a streaming socket exists. Clear the
            // watch's transfer state as well instead of leaving it until its idle timeout.
            withContext(kotlinx.coroutines.NonCancellable) {
                withTimeoutOrNull(3_000L) {
                    runCatching { stopReaderPresetPreview(deviceAddress, sessionId) }
                }
            }
            throw failure
        }
    }

    private suspend fun streamPreparedReaderPresetPreview(
        deviceAddress: String,
        sessionId: String,
        initialPreset: ReaderPreset,
        updates: ReceiveChannel<ReaderPreset>,
        onConnected: (String) -> Unit,
        onApplied: (Long) -> Unit,
        onStage: (String) -> Unit
    ) {
        var sequence = 0L
        var latestPreset = initialPreset
        var lastSentPreset = initialPreset
        var connected = false
        var lastPresetChangeAt = SystemClock.elapsedRealtime()
        while (true) {
            sequence = syncReaderPreviewResources(deviceAddress, latestPreset, sessionId, onStage, sequence)
            val queued = updates.tryReceive().getOrNull() ?: break
            val resourceChanged = queued.previewResourceSignature() != latestPreset.previewResourceSignature()
            latestPreset = queued
            lastSentPreset = queued
            if (!resourceChanged) break
        }
        onStage("正在连接预览…")
        delay(READER_RECONNECT_DELAY_MS)
        withContext(Dispatchers.IO) {
            client.streamReaderPresetPreview(
                initialRequest = ReaderPresetPreviewPayload.streamStart(
                    sessionId = sessionId,
                    sequence = sequence,
                    preset = latestPreset
                ),
                deviceAddress = deviceAddress,
                sessionId = "$sessionId-preview-stream",
                nextRequest = {
                    val active = SystemClock.elapsedRealtime() - lastPresetChangeAt <
                        PREVIEW_ACTIVE_TAIL_MS
                    val next = withTimeoutOrNull(
                        if (active) PREVIEW_ACTIVE_FRAME_INTERVAL_MS else PREVIEW_HEARTBEAT_MS
                    ) {
                        updates.receiveCatching()
                    }
                    if (next == null) {
                        sequence += 1L
                        ReaderPresetPreviewPayload.heartbeat(sessionId, sequence)
                    } else {
                        val preset = next.getOrNull()
                        if (preset == null) {
                            ReaderPresetPreviewPayload.stop(sessionId)
                        } else {
                            val resourceChanged =
                                preset.previewResourceSignature() !=
                                    lastSentPreset.previewResourceSignature()
                            latestPreset = preset
                            lastPresetChangeAt = SystemClock.elapsedRealtime()
                            sequence += 1L
                            val nextFrame = if (resourceChanged) {
                                ReaderPresetPreviewPayload.resourceHandoff(
                                    sessionId = sessionId,
                                    sequence = sequence,
                                    preset = latestPreset
                                )
                            } else {
                                ReaderPresetPreviewPayload.delta(
                                    sessionId = sessionId,
                                    sequence = sequence,
                                    previous = lastSentPreset,
                                    current = latestPreset
                                )
                            }
                            lastSentPreset = latestPreset
                            if (
                                nextFrame.optString("phase") ==
                                ReaderPresetPreviewPayload.PHASE_UPDATE &&
                                nextFrame.getJSONObject("changes").length() == 0
                            ) {
                                ReaderPresetPreviewPayload.heartbeat(sessionId, sequence)
                            } else {
                                nextFrame
                            }
                        }
                    }
                },
                onResponse = { deviceName, response ->
                    require(response.optString("sessionId") == sessionId) {
                        "手表预览会话校验失败"
                    }
                    if (!connected) {
                        connected = true
                        onConnected(deviceName)
                    }
                    if (response.optString("phase") == ReaderPresetPreviewPayload.PHASE_UPDATE) {
                        onApplied(response.optLong("sequence"))
                    }
                }
            )
        }
    }

    private suspend fun syncReaderPreviewResources(
        deviceAddress: String,
        preset: ReaderPreset,
        parentSessionId: String,
        onStage: (String) -> Unit,
        startSequence: Long
    ): Long {
        val sequence = java.util.concurrent.atomic.AtomicLong(startSequence)
        val referencedFontIds = buildSet {
            preset.body.fontAssetId?.let(::add)
            ReaderTypographyRole.entries.forEach { role ->
                preset.resolvedStyle(role).fontAssetId?.let(::add)
            }
        }
        val referencedBackgroundIds = buildSet {
            preset.background.assetId?.let(::add)
            preset.background.posterAssetId?.let(::add)
        }
        val resourceTransferStarted =
            referencedFontIds.isNotEmpty() || referencedBackgroundIds.isNotEmpty()
        if (resourceTransferStarted) {
            delay(READER_RECONNECT_DELAY_MS)
            val transferResponse = exchangeReaderFrame(
                request = ReaderPresetPreviewPayload.resourceTransfer(
                    sessionId = parentSessionId,
                    sequence = sequence.get(),
                    preset = preset
                ),
                sessionId = "$parentSessionId-preview-resource-status",
                deviceAddress = deviceAddress
            ).response
            requireSuccess(transferResponse)
        }
        var fullSnapshot = readerPresetRepository.exportSnapshot()
        val backgrounds = fullSnapshot.backgrounds.filter { it.id in referencedBackgroundIds && !it.deleted }
        if (backgrounds.isNotEmpty()) {
            onStage("正在预处理背景…")
            val capabilities = client.capabilitiesFor(deviceAddress)
                ?: throw WatchBackgroundPreparationException("未获取到手表能力，请重新连接手表")
            kotlinx.coroutines.coroutineScope {
                val keepAlive = launch {
                    while (true) {
                        delay(30_000L)
                        val heartbeat = exchangeReaderFrame(
                            request = ReaderPresetPreviewPayload.resourceTransfer(parentSessionId,
                                sequence.incrementAndGet(), preset),
                            sessionId = "$parentSessionId-preview-preparation-keepalive",
                            deviceAddress = deviceAddress
                        ).response
                        requireSuccess(heartbeat)
                    }
                }
                try { backgrounds.forEach { watchBackgroundTranscoder.prepare(it, capabilities) } }
                finally { keepAlive.cancel(); keepAlive.join() }
            }
            fullSnapshot = readerPresetRepository.exportSnapshot()
        }
        onStage("正在传输预览资源…")
        val previewSnapshot = ReaderPresetSnapshot(
            presets = emptyList(),
            fonts = fullSnapshot.fonts.filter { it.id in referencedFontIds && !it.deleted },
            backgrounds = fullSnapshot.backgrounds.filter {
                it.id in referencedBackgroundIds && !it.deleted
            },
            deletions = emptyList()
        )
        if (previewSnapshot.fonts.isEmpty() && previewSnapshot.backgrounds.isEmpty()) {
            return if (resourceTransferStarted) sequence.incrementAndGet() else sequence.get()
        }
        delay(READER_RECONNECT_DELAY_MS)
        val manifest = exchangeReaderFrame(
            request = ReaderPresetSyncPayload.buildManifest(previewSnapshot, deviceId),
            sessionId = "$parentSessionId-preview-resource-manifest",
            deviceAddress = deviceAddress
        ).response
        requireSuccess(manifest)
        val previewFontFiles = previewSnapshot.fonts.mapTo(mutableSetOf()) { it.fileName }
        val previewBackgroundFiles = buildSet {
            previewSnapshot.backgrounds.forEach { background ->
                val variants = runCatching { JSONObject(background.variantsJson) }.getOrNull()
                if (background.kind == ReaderBackgroundType.VIDEO.name) {
                    listOf("watch", "watchPoster").forEach { key ->
                        variants?.optJSONObject(key)?.optString("fileName")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::add)
                    }
                } else {
                    val watchVariant = variants?.optJSONObject("watch")?.optString("fileName")
                        ?.takeIf(String::isNotBlank)
                    add(requireNotNull(watchVariant) { "缺少已预处理的图片版本" })
                }
            }
        }
        val missing = ReaderPresetSyncPayload.missingResources(manifest).filter { resource ->
            (resource.kind == "font" && resource.fileName in previewFontFiles) ||
                (resource.kind in setOf("background", "variant") &&
                    resource.fileName in previewBackgroundFiles)
        }
        val fontResources = missing.filter { it.kind == "font" }
        val backgroundResources = missing.filter { it.kind != "font" }
        pushReaderPreviewResources(
            deviceAddress = deviceAddress,
            parentSessionId = parentSessionId,
            label = "font",
            resources = fontResources
        )
        pushReaderPreviewResources(
            deviceAddress = deviceAddress,
            parentSessionId = parentSessionId,
            label = "background",
            resources = backgroundResources
        )
        return if (resourceTransferStarted) sequence.incrementAndGet() else sequence.get()
    }

    private suspend fun pushReaderPreviewResources(
        deviceAddress: String,
        parentSessionId: String,
        label: String,
        resources: List<ResourceDescriptor>
    ) {
        resources.forEachIndexed { resourceIndex, resource ->
            ReaderPresetSyncPayload.pushFrames(readerPresetRepository, resource)
                .forEachIndexed { chunkIndex, frame ->
                    delay(READER_RECONNECT_DELAY_MS)
                    val ack = exchangeReaderFrame(
                        request = frame,
                        sessionId =
                            "$parentSessionId-preview-$label-$resourceIndex-$chunkIndex",
                        deviceAddress = deviceAddress
                    ).response
                    requireSuccess(ack)
                    require(ack.optBoolean("received")) { "手表未确认收到预览资源分块" }
                    require(ack.optString("chunkSha256") == frame.optString("chunkSha256")) {
                        "手表预览资源分块 ACK 校验失败"
                    }
                    if (chunkIndex == frame.optInt("chunkCount") - 1) {
                        require(ack.optBoolean("applied")) { "手表未确认预览资源完整落盘" }
                    }
                }
        }
    }

    suspend fun stopReaderPresetPreview(
        deviceAddress: String,
        sessionId: String
    ) {
        delay(READER_RECONNECT_DELAY_MS)
        val response = exchange(
            request = ReaderPresetPreviewPayload.stop(sessionId),
            timeoutMs = PREVIEW_EXCHANGE_TIMEOUT_MS,
            sessionId = "$sessionId-preview-stop",
            deviceAddress = deviceAddress
        ).response
        requireSuccess(response)
    }

    internal suspend fun syncLlmTokenUsage(
        device: PhoneBluetoothWatchDevice,
        reusableSession: PhoneSyncSession? = null,
        binding: BleSyncBindingSnapshot? = null
    ): PhoneBluetoothSyncResult {
        val sessionId = BluetoothDebugLog.newSessionId("syncLlmTokenUsage")
        debugLog.appendEvent(
            event = "sync.llmTokenUsage.start",
            sessionId = sessionId,
            fields = mapOf("targetAddress" to device.address)
        )
        return runCatching {
            val activeBinding=bindingSnapshot(sessionId,device.address,reusableSession,binding)
            val peer="${activeBinding.owner.orEmpty()}:${device.remoteDeviceId.ifBlank { device.address }}"
            var recordsCount=0;var deviceName=device.name
            do {
                val cursor=if(activeBinding.capabilities.tokenCursor)llmTokenUsageRepository.cursor(peer)else null
                val request=JSONObject().put("version",1).put("action",BluetoothSyncProtocol.ACTION_SYNC_LLM_TOKEN_USAGE).put("limit",200)
                if(activeBinding.capabilities.tokenCursor) {
                    request.put("afterId",cursor?.afterId?:0L)
                    cursor?.let { request.put("usageEpoch",it.usageEpoch) }
                }
                val response=exchange(request,deviceAddress=device.address,sessionId=sessionId,timeoutMs=60_000L,syncSession=reusableSession)
                requireSuccess(response.response);deviceName=response.deviceName
                require(response.response.optString("action")==BluetoothSyncProtocol.ACTION_SYNC_LLM_TOKEN_USAGE) { "手表词元用量响应无效" }
                val records=response.response.getJSONArray("records")
                recordsCount+=records.length()
                if(activeBinding.capabilities.tokenCursor)llmTokenUsageRepository.applyIncremental(peer,response.response)
                else llmTokenUsageRepository.replaceRecords((0 until records.length()).map { records.getJSONObject(it) })
                val more=activeBinding.capabilities.tokenCursor && response.response.getBoolean("hasMore")
            } while(more)
            debugLog.appendEvent(
                event = "sync.llmTokenUsage.complete",
                sessionId = sessionId,
                fields = mapOf(
                    "device" to deviceName,
                    "records" to recordsCount
                )
            )
            PhoneBluetoothSyncResult(
                deviceName = deviceName,
                importedCount = recordsCount
            )
        }.onFailure { throwable ->
            debugLog.appendEvent(
                event = "sync.llmTokenUsage.failed",
                sessionId = sessionId,
                fields = failureFields(throwable),
                throwable = throwable
            )
        }.getOrThrow()
    }

    private suspend fun exchange(
        request: JSONObject,
        timeoutMs: Long = QUICK_EXCHANGE_TIMEOUT_MS,
        sessionId: String,
        deviceAddress: String? = null,
        syncSession: PhoneSyncSession? = null
    ): BluetoothSyncExchange {
        return try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    syncSession?.exchange(request, sessionId) ?: client.exchange(
                        request = request,
                        deviceAddress = deviceAddress,
                        sessionId = sessionId
                    )
                }
            }
        } catch (exception: TimeoutCancellationException) {
            debugLog.appendEvent(
                event = "sync.exchange.timeout",
                sessionId = sessionId,
                fields = mapOf("timeoutMs" to timeoutMs),
                throwable = exception
            )
            throw IllegalStateException(
                "蓝牙同步超时，请确认手表端应用已打开并保持亮屏后重试",
                exception
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun exchangeLibrary(
        timeoutMs: Long,
        cursorRequest: JSONObject,
        buildManifestRequest: suspend (String, JSONObject?) -> JSONObject,
        buildArticleRequests: suspend (JSONObject, Boolean) -> List<JSONObject>,
        deviceAddress: String? = null,
        syncSession: PhoneSyncSession? = null,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit,
        sessionId: String,
        applyResponse: suspend (BluetoothLibrarySyncExchange) -> Unit
    ): BluetoothLibrarySyncExchange {
        return try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    syncSession?.exchangeLibrary(
                        cursorRequest = cursorRequest,
                        buildManifestRequest = buildManifestRequest,
                        buildArticleRequests = buildArticleRequests,
                        sessionId = sessionId,
                        onProgress = onProgress,
                        applyResponse = applyResponse
                    ) ?: client.exchangeLibrary(
                        cursorRequest = cursorRequest,
                        buildManifestRequest = buildManifestRequest,
                        buildArticleRequests = buildArticleRequests,
                        deviceAddress = deviceAddress,
                        sessionId = sessionId,
                        onProgress = onProgress,
                        applyResponse = applyResponse
                    )
                }
            }
        } catch (exception: TimeoutCancellationException) {
            debugLog.appendEvent(
                event = "sync.library.timeout",
                sessionId = sessionId,
                fields = mapOf("timeoutMs" to timeoutMs),
                throwable = exception
            )
            throw IllegalStateException(
                "蓝牙同步超时，请确认手表端应用已打开并保持亮屏后重试",
                exception
            )
        }
    }

    private fun requireSuccess(response: JSONObject) {
        require(response.optBoolean("success")) {
            watchBluetoothFailureMessage(response)
        }
    }

    private fun failureFields(throwable: Throwable): Map<String, Any?> {
        return mapOf(
            "errorClass" to throwable::class.java.name,
            "message" to throwable.message.orEmpty(),
            "errorCode" to throwable.accountHttpErrorCode()
        )
    }

    private fun reportProgress(
        onProgress: (PhoneBluetoothSyncProgress) -> Unit,
        stage: PhoneBluetoothSyncStage,
        percent: Int
    ) {
        onProgress(PhoneBluetoothSyncProgress(stage, percent.coerceIn(0, 100)))
    }

    private fun mergeBodyRequests(
        defaultRequests: List<ArticleBodyRequest>,
        forcedRequests: List<ArticleBodyRequest>
    ): List<ArticleBodyRequest> {
        if (forcedRequests.isEmpty()) return defaultRequests
        val byId = linkedMapOf<String, ArticleBodyRequest>()
        defaultRequests.forEach { request ->
            byId[request.articleId] = request
        }
        forcedRequests.forEach { request ->
            byId[request.articleId] = request
        }
        return byId.values.toList()
    }

    companion object {
        // Nine sequential ColorOS SDP probes can each consume a full PAGE timeout.
        private const val LIBRARY_PROBE_TIMEOUT_MS = 90_000L
        private const val QUICK_EXCHANGE_TIMEOUT_MS = 30_000L
        // Allow the initial exchange plus the single supported recovery attempt.
        private const val LIBRARY_SYNC_TIMEOUT_MS =
            BluetoothSyncProtocol.PERSISTENT_SESSION_IDLE_TIMEOUT_MS * 2
        // A real 5,378-chapter authoritative transfer delivered all 134 body
        // frames just before the 30-minute deadline, leaving no time to apply.
        private const val LIBRARY_FULL_SYNC_TIMEOUT_MS = 60 * 60 * 1_000L
        private const val READER_EXCHANGE_TIMEOUT_MS = 90_000L
        private const val READER_RECONNECT_DELAY_MS = 180L
        private const val READER_EXCHANGE_RETRY_DELAY_MS = 700L
        private const val READER_EXCHANGE_ATTEMPTS = 3
        private const val PREVIEW_EXCHANGE_TIMEOUT_MS = 15_000L
        private const val PREVIEW_HEARTBEAT_MS = 10_000L
        // Aim slightly above 30 Hz so RFCOMM write overhead does not pull the stream below it.
        private const val PREVIEW_ACTIVE_FRAME_INTERVAL_MS = 30L
        private const val PREVIEW_ACTIVE_TAIL_MS = 250L
    }
}

internal fun watchBluetoothFailureMessage(response: JSONObject): String {
    val code = response.optString("error").ifBlank { response.optString("errorCode") }
    return watchAccountConflictMessage(code) ?: response.optString("message").ifBlank { "手表返回蓝牙同步失败" }
}

internal fun PhoneBluetoothSyncResult.hasActualLibraryChanges():Boolean =
    (libraryStats?.merged?:0)>0 || (libraryStats?.sourcesMerged?:0)>0 ||
        (noteStats?.appliedOnPhone?:0)>0 || (noteStats?.appliedOnWatch?:0)>0
