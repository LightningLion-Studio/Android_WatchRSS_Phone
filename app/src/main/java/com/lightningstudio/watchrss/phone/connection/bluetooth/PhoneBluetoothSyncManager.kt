package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.annotation.SuppressLint
import android.content.Context
import com.lightningstudio.watchrss.phone.account.accountHttpErrorCode
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
import java.security.MessageDigest

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
    val conflictsOnPhone: Int
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
    SYNCING_ACCOUNT("账号授权同步中"),
    TRANSFERRING("信息传输中"),
    SYNCING_READER_RESOURCES("阅读器资源同步中"),
    SYNCING_NOTES("备忘录同步中"),
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

private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

data class PhoneBluetoothSyncProgress(
    val stage: PhoneBluetoothSyncStage,
    val percent: Int,
    val bytesTransferred: Long = 0L,
    val bytesPerSecond: Long = 0L
)

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
    private val onLibrarySyncCompleted: suspend () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val client = PhoneBluetoothSyncClient(appContext, debugLog)
    private val watchBackgroundTranscoder =
        WatchBackgroundTranscoder(context.applicationContext, readerPresetRepository)

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
            val exchange = exchange(
                JSONObject().apply {
                    put("version", 1)
                    put("action", BluetoothSyncProtocol.ACTION_REMOTE_INPUT)
                    put("nonce", System.currentTimeMillis().toString())
                    put("url", url)
                },
                sessionId = sessionId
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
        finishSession: Boolean = true
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
        val sessionId = BluetoothDebugLog.newSessionId("syncAll")
        var syncSession = reusableSession ?: client.openPersistentSession(
            device,
            deviceId,
            "$sessionId-open"
        )
        if (syncSession != null) {
            syncSession = client.promoteLateIpSession(
                session = syncSession,
                sessionId = "$sessionId-ip-upgrade"
            )
        }
        val effectiveDevice = syncSession?.device ?: device
        var accountSync: AccountSyncResult? = null
        var accountSyncWarning: String? = null
        var tokenUsageSyncWarning: String? = null
        return try {
            if (mode == LibrarySyncMode.MERGE && canSyncAccount()) {
                reportProgress(onProgress, PhoneBluetoothSyncStage.SYNCING_ACCOUNT, 0)
                runCatching {
                    syncAccount(
                        device = effectiveDevice,
                        reusableSession = syncSession,
                        finishSession = false
                    )
                }
                    .onSuccess { accountSync = it.accountSync }
                    .onFailure { accountSyncWarning = it.message ?: "账号授权同步失败" }
                reportProgress(onProgress, PhoneBluetoothSyncStage.SYNCING_ACCOUNT, 100)
            }
            val libraryResult = syncLibrary(
                deviceAddress = effectiveDevice.address,
                syncSession = syncSession,
                forceFull = forceFull || mode != LibrarySyncMode.MERGE,
                mode = mode,
                onProgress = onProgress,
                resolveDeleteConflicts = resolveDeleteConflicts
            )
            if (mode == LibrarySyncMode.MERGE) {
                runCatching {
                    syncLlmTokenUsage(
                        device = effectiveDevice,
                        reusableSession = syncSession
                    )
                }.onFailure { throwable ->
                    tokenUsageSyncWarning = throwable.message ?: "词元用量同步失败"
                }
            }
            if (syncSession != null) {
                syncSession.complete("$sessionId-complete")
            }
            libraryResult.copy(
                accountSync = accountSync,
                accountSyncWarning = accountSyncWarning,
                tokenUsageSyncWarning = tokenUsageSyncWarning
            )
        } catch (throwable: Throwable) {
            if (syncSession != null) {
                runCatching { syncSession.abort("$sessionId-abort") }
            }
            throw throwable
        } finally {
            syncSession?.close()
        }
    }

    suspend fun syncSavedItems(type: PhoneSavedItemType): PhoneBluetoothSyncResult {
        val sessionId = BluetoothDebugLog.newSessionId("savedItems")
        debugLog.appendEvent(
            event = "sync.savedItems.start",
            sessionId = sessionId,
            fields = mapOf("type" to type.name)
        )
        return runCatching {
            val exchange = exchange(
                JSONObject().apply {
                    put("version", 1)
                    put("action", BluetoothSyncProtocol.ACTION_PULL_SAVED_ITEMS)
                    put("nonce", System.currentTimeMillis().toString())
                    put("type", type.name)
                },
                sessionId = sessionId
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
        resolveDeleteConflicts: suspend (List<PhoneSyncDeleteConflict>) -> Map<String, PhoneSyncConflictResolution> = {
            emptyMap()
        }
    ): PhoneBluetoothSyncResult {
        reportProgress(onProgress, PhoneBluetoothSyncStage.CONNECTING, 0)
        repository.repairImportedContentSourceStates()
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
        val sessionId = BluetoothDebugLog.newSessionId("syncLibrary")
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
                        peerAppliedLocalSeq = if (forceFull) 0L else remoteCursor.lastRemoteSeqApplied
                    )
                    val window = if (forceFull || mode != LibrarySyncMode.MERGE) {
                        preparedWindow.copy(
                            articleManifest = preparedWindow.fullArticleManifest,
                            rssSources = repository.getRssSourcesForSync(),
                            fullSnapshot = true,
                            fromSeqExclusive = 0L,
                            fallbackReason = if (mode == LibrarySyncMode.MERGE) "forcedFull" else mode.protocolValue
                        )
                    } else preparedWindow
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
                    remoteFullManifest = remoteManifest
                    val supportsChunkedBodies = manifestResponse.optBoolean("supportsChunkedBodies", false) &&
                        manifestResponse.optInt("version") > LibrarySyncPayload.LEGACY_PROTOCOL_VERSION
                    val supportsMetadataOnlyArticles = LibrarySyncPayload.supportsMetadataOnlyArticles(manifestResponse)
                    reportProgress(onProgress, PhoneBluetoothSyncStage.TRANSFERRING, 28)
                    val deleteConflicts = if (mode == LibrarySyncMode.MERGE) {
                        repository.findDeleteConflicts(remoteManifest)
                    } else {
                        emptyList()
                    }
                    val conflictResolutions = if (deleteConflicts.isNotEmpty()) {
                        resolveDeleteConflicts(deleteConflicts)
                    } else {
                        emptyMap()
                    }
                    val conflictPlan = repository.prepareDeleteConflictResolutions(
                        remoteManifest = remoteManifest,
                        resolutions = conflictResolutions
                    )
                    conflictMergeResolutions = conflictPlan.mergeResolutions
                    if (supportsChunkedBodies) {
                        val watchRequests = LibrarySyncPayload.parseBodyRequests(manifestResponse)
                        val phoneRequests = if (mode == LibrarySyncMode.PHONE_TO_WATCH_REPLACE) {
                            emptyList()
                        } else mergeBodyRequests(
                            defaultRequests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
                                localManifest = window.fullArticleManifest,
                                remoteManifest = remoteManifest,
                                maxBodyRequestChunks = LibrarySyncPayload.MAX_BODY_REQUEST_CHUNKS_PER_SYNC,
                                supportsMetadataOnlyArticles = supportsMetadataOnlyArticles
                            ).filterNot { it.articleId in conflictPlan.suppressedRemoteArticleIds },
                            forcedRequests = conflictPlan.forcedRemoteRequests
                        )
                        sentArticles = repository.getArticlesForSync(
                            watchRequests.map { it.articleId } + conflictPlan.outgoingArticleIds
                        )
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
                            useBatches = supportsArticleBatches
                        )
                    }
                    if (conflictPlan.forcedRemoteRequests.isNotEmpty()) {
                        error("手表端版本不支持本次删除冲突处理，请更新手表端后重试")
                    }
                    val localArticles = repository.getArticlesForSync()
                    val diffArticleIds = LibrarySyncPayload.filterArticlesNeedingSync(localArticles, remoteManifest)
                        .filterNot { it.articleId in conflictPlan.suppressedRemoteArticleIds }
                        .mapTo(linkedSetOf()) { it.articleId }
                    diffArticleIds += conflictPlan.outgoingArticleIds
                    sentArticles = repository.getArticlesForSync(diffArticleIds)
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
                        useBatches = supportsArticleBatches
                    )
                    if (!supportsArticleBatches && frames.any { BluetoothSyncProtocol.encodedSize(it) > BluetoothSyncProtocol.MAX_FRAME_BYTES }) {
                        error("手表端版本不支持分批同步，当前资料库超过蓝牙单帧限制，请更新手表端后重试")
                    }
                    frames
                },
                deviceAddress = deviceAddress,
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
                        val chunked = LibrarySyncPayload.parseChunkedArticles(exchange.response)
                        receivedArticles = chunked.size
                        repository.mergeChunkedArticlesFromSync(
                            chunked,
                            conflictMergeResolutions,
                            authoritative = mode == LibrarySyncMode.WATCH_TO_PHONE_REPLACE
                        )
                    } else {
                        val articles = LibrarySyncPayload.parseArticles(exchange.response)
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
                        repository.finalizeAuthoritativeLibrarySnapshot(
                            retainedArticleIds = remoteFullManifest.map { it.articleId },
                            retainedSourceUrls = receivedSources.map { it.url }
                        )
                    }
                    val remoteChangeSequence = LibrarySyncPayload.parseChangeSequence(exchange.manifestResponse)
                    remoteSeqApplied = remoteChangeSequence.toSeqInclusive
                    if (mode != LibrarySyncMode.MERGE) {
                        repository.resetLibrarySyncPeerState()
                    }
                    repository.markLibrarySyncSuccess(
                        peerDeviceId = activePeerDeviceId.ifBlank {
                            exchange.deviceAddress.ifBlank { exchange.deviceName }
                        },
                        localSeqToInclusive = if (mode == LibrarySyncMode.WATCH_TO_PHONE_REPLACE) 0L else window.toSeqInclusive,
                        remoteSeqToInclusive = if (mode == LibrarySyncMode.PHONE_TO_WATCH_REPLACE) 0L else remoteChangeSequence.toSeqInclusive,
                        remoteProtocolVersion = exchange.manifestResponse.optInt("version"),
                        fullSnapshot = window.fullSnapshot
                    )
                    reportProgress(onProgress, PhoneBluetoothSyncStage.VERIFYING, 96)
                }
            )
            val window = syncWindow ?: error("同步窗口尚未准备")
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
            val readerSyncWarning = if (mode == LibrarySyncMode.MERGE && exchange.manifestResponse.optInt("version") >= 11 &&
                exchange.manifestResponse.optBoolean("supportsReaderPresets", true)
            ) {
                runCatching {
                    syncReaderPresets(
                        deviceAddress = exchange.deviceAddress,
                        parentSessionId = sessionId,
                        syncSession = syncSession
                    ) { completed, total ->
                        val percent = if (total <= 0) 100 else (completed * 100 / total)
                        reportProgress(
                            onProgress,
                            PhoneBluetoothSyncStage.SYNCING_READER_RESOURCES,
                            percent
                        )
                    }
                }.exceptionOrNull()?.let { throwable ->
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
            val noteStats = if (mode == LibrarySyncMode.MERGE) {
                reportProgress(onProgress, PhoneBluetoothSyncStage.SYNCING_NOTES, 0)
                syncNotes(
                    deviceAddress = exchange.deviceAddress,
                    parentSessionId = sessionId,
                    syncSession = syncSession
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
            ).also { onLibrarySyncCompleted() }
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

    private suspend fun syncNotes(
        deviceAddress: String,
        parentSessionId: String,
        syncSession: PhoneSyncSession? = null
    ): NoteSyncStats {
        val sessionId = "$parentSessionId-notes"
        val localNotes = noteRepository.allNotes()
        debugLog.appendEvent(
            event = "sync.notes.start",
            sessionId = sessionId,
            fields = mapOf(
                "targetAddress" to deviceAddress,
                "sent" to localNotes.size
            )
        )
        return runCatching {
            val exchange = exchange(
                request = NoteSyncPayload.manifest(deviceId, localNotes),
                deviceAddress = deviceAddress,
                sessionId = sessionId,
                syncSession = syncSession
            )
            requireSuccess(exchange.response)
            require(exchange.response.optString("action") == NoteSyncPayload.ACTION_SYNC_NOTES) {
                "手表不支持备忘录同步，请升级手表端"
            }
            val remoteDeviceId = exchange.response.optString("deviceId").ifBlank { "watch" }
            val remoteNotes = NoteSyncPayload.decodeNotes(exchange.response)
            var conflicts = 0
            remoteNotes.forEach { note ->
                if (noteRepository.applyRemote(note, remoteDeviceId) is MarkdownMergeResult.Conflict) {
                    conflicts += 1
                }
            }
            syncNoteAssets(localNotes, deviceAddress, sessionId, syncSession)
            NoteSyncStats(
                sent = localNotes.size,
                received = remoteNotes.size,
                appliedOnWatch = exchange.response.optInt("applied"),
                conflictsOnPhone = conflicts
            ).also { stats ->
                debugLog.appendEvent(
                    event = "sync.notes.complete",
                    sessionId = sessionId,
                    fields = mapOf(
                        "device" to exchange.deviceName,
                        "sent" to stats.sent,
                        "received" to stats.received,
                        "appliedOnWatch" to stats.appliedOnWatch,
                        "conflictsOnPhone" to stats.conflictsOnPhone
                    )
                )
            }
        }.onFailure { throwable ->
            debugLog.appendEvent(
                event = "sync.notes.failed",
                sessionId = sessionId,
                fields = failureFields(throwable),
                throwable = throwable
            )
        }.getOrThrow()
    }

    private suspend fun syncNoteAssets(
        notes: List<com.lightningstudio.watchrss.phone.data.note.NoteEntity>,
        deviceAddress: String,
        parentSessionId: String,
        syncSession: PhoneSyncSession? = null
    ) {
        val storageKeys = linkedSetOf<String>()
        notes.filterNot { it.deleted }.forEach { note ->
            storageKeys += noteRepository.assets(note.noteId)
                .asSequence()
                .filterNot { it.deleted }
                .map { it.storageKey }
                .filter(NoteAssetSyncPayload::isSafeStorageKey)
                .toList()
            storageKeys += NoteAssetSyncPayload.referencedStorageKeys(note.markdown)
        }
        storageKeys.forEach assetLoop@ { storageKey ->
                val file = File(appContext.filesDir, "notes/assets/$storageKey")
                if (!file.isFile) return@assetLoop
                val sha256 = file.sha256()
                val chunkCount = ((file.length() + NoteAssetSyncPayload.CHUNK_BYTES - 1) /
                    NoteAssetSyncPayload.CHUNK_BYTES).toInt().coerceAtLeast(1)
                file.inputStream().buffered().use { input ->
                    repeat(chunkCount) { chunkIndex ->
                        val bytes = input.readNoteAssetChunk(NoteAssetSyncPayload.CHUNK_BYTES)
                        if (syncSession == null) delay(READER_RECONNECT_DELAY_MS)
                        val response = exchange(
                            request = NoteAssetSyncPayload.chunk(
                                storageKey = storageKey,
                                sha256 = sha256,
                                chunkIndex = chunkIndex,
                                chunkCount = chunkCount,
                                bytes = bytes
                            ),
                            deviceAddress = deviceAddress,
                            sessionId = "$parentSessionId-asset-$storageKey-$chunkIndex",
                            syncSession = syncSession
                        ).response
                        requireSuccess(response)
                        require(response.optString("action") == NoteAssetSyncPayload.ACTION) {
                            "手表不支持备忘录图片同步，请升级手表端"
                        }
                        if (response.optBoolean("alreadyPresent")) return@assetLoop
                    }
                }
            }
    }

    internal suspend fun syncReaderPresets(
        deviceAddress: String,
        parentSessionId: String = BluetoothDebugLog.newSessionId("syncReader"),
        syncSession: PhoneSyncSession? = null,
        onResourceProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
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

        val resourcesToPush = ReaderPresetSyncPayload.missingResources(manifest)
        val resourcesToPull = ReaderPresetSyncPayload.locallyMissing(readerPresetRepository)
        val totalChunks = (resourcesToPush + resourcesToPull).sumOf { resource ->
            ((resource.byteCount + ReaderPresetSyncPayload.CHUNK_BYTES - 1L) /
                ReaderPresetSyncPayload.CHUNK_BYTES).toInt().coerceAtLeast(1)
        }
        var completedChunks = 0
        onResourceProgress(completedChunks, totalChunks)

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

        resourcesToPull.forEachIndexed { resourceIndex, resource ->
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
                    chunkIndex += 1
                    completedChunks += 1
                    onResourceProgress(completedChunks.coerceAtMost(totalChunks), totalChunks)
                }
            }
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
        reusableSession: PhoneSyncSession? = null
    ): PhoneBluetoothSyncResult {
        val sessionId = BluetoothDebugLog.newSessionId("syncLlmTokenUsage")
        debugLog.appendEvent(
            event = "sync.llmTokenUsage.start",
            sessionId = sessionId,
            fields = mapOf("targetAddress" to device.address)
        )
        return runCatching {
            val request = JSONObject().apply {
                put("version", 1)
                put("action", BluetoothSyncProtocol.ACTION_SYNC_LLM_TOKEN_USAGE)
                put("nonce", System.currentTimeMillis().toString())
                put("limit", 200)
            }
            val exchange = exchange(
                request = request,
                deviceAddress = device.address,
                sessionId = sessionId,
                timeoutMs = 60_000L,
                syncSession = reusableSession
            )
            requireSuccess(exchange.response)
            val records = exchange.response.optJSONArray("records") ?: org.json.JSONArray()
            val recordsList = (0 until records.length()).map { records.getJSONObject(it) }
            llmTokenUsageRepository.replaceRecords(recordsList)
            debugLog.appendEvent(
                event = "sync.llmTokenUsage.complete",
                sessionId = sessionId,
                fields = mapOf(
                    "device" to exchange.deviceName,
                    "records" to recordsList.size
                )
            )
            PhoneBluetoothSyncResult(
                deviceName = exchange.deviceName,
                importedCount = recordsList.size
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
            withTimeout(LIBRARY_SYNC_TIMEOUT_MS) {
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
                fields = mapOf("timeoutMs" to LIBRARY_SYNC_TIMEOUT_MS),
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
            response.optString("message").ifBlank { "手表返回蓝牙同步失败" }
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
