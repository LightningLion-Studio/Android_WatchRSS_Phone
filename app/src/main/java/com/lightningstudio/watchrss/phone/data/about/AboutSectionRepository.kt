package com.lightningstudio.watchrss.phone.data.about

import android.content.Context
import com.lightningstudio.watchrss.phone.BuildConfig
import com.lightningstudio.watchrss.phone.network.withWatchRssAppVersionHeader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

internal data class AboutSectionItem(val title: String?, val body: String)

internal class AboutSectionRepository(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val readCache: () -> String?,
    private val writeCache: (String) -> Unit
) {
    suspend fun load(): List<AboutSectionItem> = withContext(Dispatchers.IO) {
        val remote = fetchSnapshot()
        val latest = synchronized(this@AboutSectionRepository) {
            val cached = readCache()?.let(::parseSnapshot)
            when {
                remote == null -> cached
                cached != null && cached.revision > remote.revision -> cached
                else -> remote.also { writeCache(it.json) }
            }
        }
        latest?.items.orEmpty()
    }

    private fun fetchSnapshot(): Snapshot? = try {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/functions/v1/about-section?platform=phone")
            .withWatchRssAppVersionHeader()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string()?.let(::parseSnapshot) else null
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private data class Snapshot(val json: String, val revision: Long, val items: List<AboutSectionItem>)

    private fun parseSnapshot(json: String): Snapshot? = try {
        val root = JSONObject(json)
        val revision = root.get("revision")
        require(revision is Int || revision is Long)
        val entries = root.getJSONArray("items")
        require(entries.length() <= 20)
        val items = List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val title = entry.opt("title").takeUnless { it == JSONObject.NULL }
            val body = entry.get("body")
            require(title == null || title is String && title.codePointCount(0, title.length) <= 40)
            require(body is String && body.codePointCount(0, body.length) <= 1000)
            AboutSectionItem(title as String?, body)
        }
        Snapshot(json, (revision as Number).toLong(), items)
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        fun create(context: Context): AboutSectionRepository {
            val preferences = context.getSharedPreferences("about_section", Context.MODE_PRIVATE)
            return AboutSectionRepository(
                baseUrl = BuildConfig.WATCHRSS_PRODUCTION_BACKEND_BASE_URL,
                client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build(),
                readCache = { preferences.getString("phone", null) },
                writeCache = { preferences.edit().putString("phone", it).apply() }
            )
        }
    }
}
