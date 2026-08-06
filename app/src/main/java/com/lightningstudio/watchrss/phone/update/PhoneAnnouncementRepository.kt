package com.lightningstudio.watchrss.phone.update

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PhoneAnnouncement(
    val version: String,
    val changelogMarkdown: String,
    val forceUpdate: Boolean,
    val downloadUrl: String
)

class PhoneAnnouncementRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("watchrss_phone_announcement", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun check(): PhoneAnnouncement? = withContext(Dispatchers.IO) {
        val baseUrl = AccountEnvironment.active(context).backendBaseUrl
        if (baseUrl.isBlank()) return@withContext null
        runCatching {
            client.newCall(
                Request.Builder()
                    .url("$baseUrl/functions/v1/announcement?client=android_phone")
                    .get()
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val announcement = parse(JSONObject(body)) ?: return@use null
                if (compareVersions(announcement.version, BuildConfig.VERSION_NAME) <= 0) return@use null
                if (!announcement.forceUpdate &&
                    prefs.getString("dismissed_version", null) == announcement.version
                ) return@use null
                announcement
            }
        }.onFailure { Log.w("PhoneAnnouncement", "Unable to check update", it) }.getOrNull()
    }

    fun dismiss(version: String) {
        prefs.edit { putString("dismissed_version", version) }
    }

    companion object {
        internal fun parse(json: JSONObject): PhoneAnnouncement? {
            val version = json.optString("version").trim()
            val changelog = json.optString("changelog_md").trim()
            val url = json.optString("download_url").trim()
            if (version.isEmpty() || changelog.isEmpty() || url.isEmpty()) return null
            return PhoneAnnouncement(version, changelog, json.optBoolean("force_update"), url)
        }

        internal fun compareVersions(left: String, right: String): Int {
            val a = Regex("\\d+").findAll(left).map { it.value.toLongOrNull() ?: 0 }.toList()
            val b = Regex("\\d+").findAll(right).map { it.value.toLongOrNull() ?: 0 }.toList()
            for (index in 0 until maxOf(a.size, b.size)) {
                val result = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
                if (result != 0) return result
            }
            return 0
        }
    }
}
