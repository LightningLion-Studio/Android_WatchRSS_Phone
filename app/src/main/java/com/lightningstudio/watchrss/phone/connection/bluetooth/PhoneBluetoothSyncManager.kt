package com.lightningstudio.watchrss.phone.connection.bluetooth

import android.content.Context
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.data.repo.PhoneCompanionRepository
import kotlinx.coroutines.Dispatchers
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
    private val deviceId: String
) {
    private val client = PhoneBluetoothSyncClient(context.applicationContext)

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
        val exchange = exchange(
            LibrarySyncPayload.buildRequest(
                deviceId = deviceId,
                articles = localArticles
            )
        )
        requireSuccess(exchange.response)
        val received = LibrarySyncPayload.parseArticles(exchange.response)
        val merged = repository.mergeArticlesFromSync(received)
        return PhoneBluetoothSyncResult(
            deviceName = exchange.deviceName,
            libraryStats = LibrarySyncStats(
                sent = localArticles.size,
                received = received.size,
                merged = merged
            )
        )
    }

    private suspend fun exchange(request: JSONObject): BluetoothSyncExchange {
        return withTimeout(CONNECT_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                client.exchange(request)
            }
        }
    }

    private fun requireSuccess(response: JSONObject) {
        require(response.optBoolean("success")) {
            response.optString("message").ifBlank { "手表返回蓝牙同步失败" }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L
    }
}
