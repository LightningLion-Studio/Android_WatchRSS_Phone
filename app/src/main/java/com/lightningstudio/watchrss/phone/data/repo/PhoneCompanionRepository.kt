package com.lightningstudio.watchrss.phone.data.repo

import android.util.Base64
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemDao
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.data.model.WatchAbility
import com.lightningstudio.watchrss.phone.data.model.WatchEndpoint
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class PhoneCompanionRepository(
    private val client: OkHttpClient,
    private val savedItemDao: PhoneSavedItemDao
) {
    fun observeSavedItems(type: PhoneSavedItemType): Flow<List<PhoneSavedItemEntity>> {
        return savedItemDao.observeByType(type.name)
    }

    fun parseEndpointFromQr(rawText: String): WatchEndpoint {
        val payload = rawText.trim()
        val hostPort = when {
            payload.contains('\n') -> {
                val encoded = payload.substringAfterLast('\n').trim()
                String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
            }
            else -> payload
        }
        val host = hostPort.substringBefore(':').trim()
        val port = hostPort.substringAfter(':').trim().toIntOrNull()
            ?: error("二维码中未包含有效端口")
        require(host.isNotBlank()) { "二维码中未包含有效地址" }
        return WatchEndpoint(host = host, port = port)
    }

    fun fetchAbilities(endpoint: WatchEndpoint): List<WatchAbility> {
        val responseJson = executeJsonGet("${endpoint.baseUrl}/getAbilities")
        val data = responseJson.optJSONArray("abilities") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                add(
                    WatchAbility(
                        code = item.optString("code"),
                        name = item.optString("name"),
                        version = item.optString("version")
                    )
                )
            }
        }
    }

    fun verifyHealth(endpoint: WatchEndpoint) {
        val json = executeJsonGet("${endpoint.baseUrl}/health")
        require(json.optString("status") == "ok") { "手表连接检查失败" }
    }

    fun sendRemoteUrl(endpoint: WatchEndpoint, url: String) {
        val payload = JSONObject().apply {
            put("url", url)
        }.toString()
        val request = Request.Builder()
            .url("${endpoint.baseUrl}/remoteEnterRSSURL")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "发送 RSS 失败：${response.code}" }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body.ifBlank { "{}" })
            require(json.optBoolean("success", true)) { json.optString("message", "发送 RSS 失败") }
        }
    }

    suspend fun syncSavedItems(endpoint: WatchEndpoint, type: PhoneSavedItemType): Int {
        val responseJson = executeJsonGet("${endpoint.baseUrl}${type.wirePath}")
        require(responseJson.optBoolean("success", true)) { responseJson.optString("message", "同步失败") }
        val data = responseJson.optJSONArray("data") ?: JSONArray()
        return replaceSavedItems(type, data)
    }

    suspend fun replaceSavedItems(type: PhoneSavedItemType, data: JSONArray): Int {
        val syncedAt = System.currentTimeMillis()
        val entities = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val link = item.optString("link").trim()
                if (link.isBlank()) continue
                val remoteId = item.optLong("id")
                val title = item.optString("title").trim()
                val summary = item.optString("summary").trim()
                val channelTitle = item.optString("channelTitle").trim()
                val stableKey = when {
                    remoteId > 0L -> remoteId.toString()
                    link.isNotBlank() -> link
                    else -> "${type.name}-$index"
                }
                add(
                    PhoneSavedItemEntity(
                        type = type.name,
                        stableKey = stableKey,
                        remoteId = remoteId,
                        title = title.ifBlank { link },
                        link = link,
                        summary = summary,
                        channelTitle = channelTitle.ifBlank { hostLabel(link) },
                        pubDate = item.optString("pubDate"),
                        syncedAt = syncedAt
                    )
                )
            }
        }
        savedItemDao.deleteByType(type.name)
        savedItemDao.upsertAll(entities)
        return entities.size
    }

    private fun executeJsonGet(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "请求失败：${response.code}" }
            return JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
        }
    }

    private fun hostLabel(link: String): String {
        return runCatching { URI(link).host.orEmpty().removePrefix("www.") }
            .getOrDefault("")
            .trim()
    }
}
