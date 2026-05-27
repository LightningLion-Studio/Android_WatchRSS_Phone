package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.content.Context
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.log.BluetoothDebugLog
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class PhoneBluetoothSyncResult(
    val deviceName: String,
    val importedCount: Int? = null,
    val libraryStats: LibrarySyncStats? = null
)

enum class PhoneBluetoothSyncStage(val displayName: String) {
    CONNECTING("建立连接中"),
    TRANSFERRING("信息传输中"),
    VERIFYING("校验中")
}

data class PhoneBluetoothSyncProgress(
    val stage: PhoneBluetoothSyncStage,
    val percent: Int
)

class PhoneBluetoothSyncManager(
    context: Context,
    private val repository: PhoneCompanionRepository,
    private val deviceId: String,
    private val debugLog: BluetoothDebugLog
) {
    private val client = PhoneBluetoothSyncClient(context.applicationContext, debugLog)

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

    suspend fun syncLibrary(
        onProgress: (PhoneBluetoothSyncProgress) -> Unit = {},
        resolveDeleteConflicts: suspend (List<PhoneSyncDeleteConflict>) -> Map<String, PhoneSyncConflictResolution> = {
            emptyMap()
        }
    ): PhoneBluetoothSyncResult {
        reportProgress(onProgress, PhoneBluetoothSyncStage.CONNECTING, 0)
        repository.repairImportedContentSourceStates()
        val localManifest = repository.getArticleManifestsForSync()
        val localSources = repository.getRssSourcesForSync()
        reportProgress(onProgress, PhoneBluetoothSyncStage.CONNECTING, 8)
        var sentArticles = emptyList<PhoneArticleEntity>()
        var receivedArticles = 0
        var conflictMergeResolutions = emptyMap<String, PhoneSyncConflictResolution>()
        val sessionId = BluetoothDebugLog.newSessionId("syncLibrary")
        debugLog.appendEvent(
            event = "sync.library.start",
            sessionId = sessionId,
            fields = mapOf(
                "localArticles" to localManifest.size,
                "localSources" to localSources.size
            )
        )
        return runCatching {
            val exchange = exchangeLibrary(
                LibrarySyncPayload.buildManifestRequestFromEntries(
                    deviceId = deviceId,
                    articleManifest = localManifest,
                    rssSources = localSources
                ),
                buildArticleRequests = { manifestResponse, supportsArticleBatches ->
                    requireSuccess(manifestResponse)
                    val remoteManifest = LibrarySyncPayload.parseArticleManifest(manifestResponse)
                    val supportsChunkedBodies = manifestResponse.optBoolean("supportsChunkedBodies", false) &&
                        manifestResponse.optInt("version") >= LibrarySyncPayload.PROTOCOL_VERSION
                    reportProgress(onProgress, PhoneBluetoothSyncStage.TRANSFERRING, 28)
                    val deleteConflicts = repository.findDeleteConflicts(remoteManifest)
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
                        val phoneRequests = mergeBodyRequests(
                            defaultRequests = LibrarySyncPayload.buildBodyRequestsForRemoteArticles(
                                localManifest = localManifest,
                                remoteManifest = remoteManifest,
                                maxBodyRequestChunks = LibrarySyncPayload.MAX_BODY_REQUEST_CHUNKS_PER_SYNC
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
                onProgress = onProgress,
                sessionId = sessionId
            )
            reportProgress(onProgress, PhoneBluetoothSyncStage.VERIFYING, 90)
            requireSuccess(exchange.response)
            val chunkedResponse = exchange.response.optInt("version") >= LibrarySyncPayload.PROTOCOL_VERSION &&
                exchange.response.optJSONArray("articles") != null &&
                (exchange.response.optJSONArray("articles")?.let { array ->
                    (0 until array.length()).any { index ->
                        array.optJSONObject(index)?.has("body") == true
                    }
                } == true)
            val received = if (chunkedResponse) {
                val chunked = LibrarySyncPayload.parseChunkedArticles(exchange.response)
                receivedArticles = chunked.size
                repository.mergeChunkedArticlesFromSync(chunked, conflictMergeResolutions)
            } else {
                val articles = LibrarySyncPayload.parseArticles(exchange.response)
                receivedArticles = articles.size
                repository.mergeArticlesFromSync(articles, conflictMergeResolutions)
            }
            val receivedSources = LibrarySyncPayload.parseRssSources(exchange.manifestResponse)
            reportProgress(onProgress, PhoneBluetoothSyncStage.VERIFYING, 94)
            val merged = received
            val mergedSources = repository.mergeRssSourcesFromSync(receivedSources)
            reportProgress(onProgress, PhoneBluetoothSyncStage.VERIFYING, 100)
            debugLog.appendEvent(
                event = "sync.library.complete",
                sessionId = sessionId,
                fields = mapOf(
                    "device" to exchange.deviceName,
                    "sent" to sentArticles.size,
                    "received" to receivedArticles,
                    "merged" to merged,
                    "sourcesSent" to localSources.size,
                    "sourcesReceived" to receivedSources.size,
                    "sourcesMerged" to mergedSources
                )
            )
            PhoneBluetoothSyncResult(
                deviceName = exchange.deviceName,
                libraryStats = LibrarySyncStats(
                    sent = sentArticles.size,
                    received = receivedArticles,
                    merged = merged,
                    sourcesSent = localSources.size,
                    sourcesReceived = receivedSources.size,
                    sourcesMerged = mergedSources
                )
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

    private suspend fun exchange(
        request: JSONObject,
        timeoutMs: Long = QUICK_EXCHANGE_TIMEOUT_MS,
        sessionId: String
    ): BluetoothSyncExchange {
        return try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    client.exchange(request, sessionId = sessionId)
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

    private suspend fun exchangeLibrary(
        request: JSONObject,
        buildArticleRequests: suspend (JSONObject, Boolean) -> List<JSONObject>,
        onProgress: (PhoneBluetoothSyncProgress) -> Unit,
        sessionId: String
    ): BluetoothLibrarySyncExchange {
        return try {
            withTimeout(LIBRARY_SYNC_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    client.exchangeLibrary(
                        manifestRequest = request,
                        buildArticleRequests = buildArticleRequests,
                        sessionId = sessionId,
                        onProgress = onProgress
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
            "message" to throwable.message.orEmpty()
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
        private const val QUICK_EXCHANGE_TIMEOUT_MS = 30_000L
        private const val LIBRARY_SYNC_TIMEOUT_MS = 300_000L
    }
}
