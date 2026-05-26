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

class PhoneBluetoothSyncManager(
    context: Context,
    private val repository: PhoneCompanionRepository,
    private val deviceId: String,
    private val debugLog: BluetoothDebugLog
) {
    private val client = PhoneBluetoothSyncClient(context.applicationContext, debugLog)

    suspend fun sendRemoteInput(url: String): PhoneBluetoothSyncResult {
        val exchange = exchange(
            JSONObject().apply {
                put("version", 1)
                put("action", BluetoothSyncProtocol.ACTION_REMOTE_INPUT)
                put("nonce", System.currentTimeMillis().toString())
                put("url", url)
            }
        )
        requireSuccess(exchange.response)
        return PhoneBluetoothSyncResult(deviceName = exchange.deviceName)
    }

    suspend fun syncSavedItems(type: PhoneSavedItemType): PhoneBluetoothSyncResult {
        val exchange = exchange(
            JSONObject().apply {
                put("version", 1)
                put("action", BluetoothSyncProtocol.ACTION_PULL_SAVED_ITEMS)
                put("nonce", System.currentTimeMillis().toString())
                put("type", type.name)
            }
        )
        requireSuccess(exchange.response)
        val items = exchange.response.optJSONArray("items") ?: JSONArray()
        val importedCount = repository.replaceSavedItems(type, items)
        return PhoneBluetoothSyncResult(
            deviceName = exchange.deviceName,
            importedCount = importedCount
        )
    }

    suspend fun syncLibrary(): PhoneBluetoothSyncResult {
        val localArticles = repository.getArticlesForSync()
        val localSources = repository.getRssSourcesForSync()
        var sentArticles = emptyList<PhoneArticleEntity>()
        debugLog.append("syncLibrary start localArticles=${localArticles.size} localSources=${localSources.size}")
        return runCatching {
            val exchange = exchangeLibrary(
                LibrarySyncPayload.buildManifestRequest(
                    deviceId = deviceId,
                    articles = localArticles,
                    rssSources = localSources
                ),
                buildArticlesRequest = { manifestResponse ->
                    requireSuccess(manifestResponse)
                    val remoteManifest = LibrarySyncPayload.parseArticleManifest(manifestResponse)
                    sentArticles = LibrarySyncPayload.filterArticlesNeedingSync(localArticles, remoteManifest)
                    debugLog.append(
                        "syncLibrary manifest received remoteManifest=${remoteManifest.size} diffArticles=${sentArticles.size}"
                    )
                    LibrarySyncPayload.buildArticlesRequest(
                        deviceId = deviceId,
                        articles = sentArticles
                    )
                }
            )
            requireSuccess(exchange.response)
            val received = LibrarySyncPayload.parseArticles(exchange.response)
            val receivedSources = LibrarySyncPayload.parseRssSources(exchange.manifestResponse)
            val merged = repository.mergeArticlesFromSync(received)
            val mergedSources = repository.mergeRssSourcesFromSync(receivedSources)
            debugLog.append(
                "syncLibrary complete device=${exchange.deviceName} sent=${sentArticles.size} received=${received.size} merged=$merged sourcesSent=${localSources.size} sourcesReceived=${receivedSources.size} sourcesMerged=$mergedSources"
            )
            PhoneBluetoothSyncResult(
                deviceName = exchange.deviceName,
                libraryStats = LibrarySyncStats(
                    sent = sentArticles.size,
                    received = received.size,
                    merged = merged,
                    sourcesSent = localSources.size,
                    sourcesReceived = receivedSources.size,
                    sourcesMerged = mergedSources
                )
            )
        }.onFailure { throwable ->
            debugLog.append("syncLibrary failed: ${throwable.message}", throwable)
        }.getOrThrow()
    }

    private suspend fun exchange(
        request: JSONObject,
        timeoutMs: Long = QUICK_EXCHANGE_TIMEOUT_MS
    ): BluetoothSyncExchange {
        return try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    client.exchange(request)
                }
            }
        } catch (exception: TimeoutCancellationException) {
            throw IllegalStateException(
                "蓝牙同步超时，请确认手表端应用已打开并保持亮屏后重试",
                exception
            )
        }
    }

    private suspend fun exchangeLibrary(
        request: JSONObject,
        buildArticlesRequest: (JSONObject) -> JSONObject
    ): BluetoothLibrarySyncExchange {
        return try {
            withTimeout(LIBRARY_SYNC_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    client.exchangeLibrary(request, buildArticlesRequest)
                }
            }
        } catch (exception: TimeoutCancellationException) {
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

    companion object {
        private const val QUICK_EXCHANGE_TIMEOUT_MS = 30_000L
        private const val LIBRARY_SYNC_TIMEOUT_MS = 120_000L
    }
}
