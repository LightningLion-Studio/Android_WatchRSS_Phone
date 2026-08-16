package com.lightningstudio.watchrss.phone.update

import android.content.Context
import android.util.Log
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.network.withWatchRssAppVersionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Queries the backend proxy for the newest full version code listed in the OPPO
 * store. The backend performs the OPPO open-platform auth + version lookup
 * (client_secret stays server-side) and caches the answer for 10 minutes.
 */
class PhoneStoreVersionRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** null when the backend is unreachable, self-update is not configured, or the package is not listed. */
    suspend fun check(): Int? = withContext(Dispatchers.IO) {
        val baseUrl = AccountEnvironment.active(context).backendBaseUrl
        if (baseUrl.isBlank()) return@withContext null
        runCatching {
            client.newCall(
                Request.Builder()
                    .url("$baseUrl/functions/v1/phone/store-version")
                    .withWatchRssAppVersionHeader()
                    .get()
                    .build()
            ).execute().use { response ->
                if (response.code == 204 || !response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                parse(JSONObject(body))
            }
        }.onFailure { Log.w("PhoneStoreVersion", "Unable to check store version", it) }.getOrNull()
    }

    companion object {
        internal fun parse(json: JSONObject): Int? {
            val code = json.optInt("fullVersionCode", -1)
            return code.takeIf { it >= 0 }
        }
    }
}
