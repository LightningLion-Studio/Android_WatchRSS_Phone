package com.lightningstudio.watchrss.phone.data.ai

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

internal enum class AiDisclosureFeature(val key: String) {
    SUPPORT("ai_support"),
    ARTICLE_SUMMARY("article_summary")
}

internal class AiDisclosureRepository(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val readCache: () -> String?,
    private val writeCache: (String) -> Unit,
    private val readBundled: () -> String?
) {
    suspend fun displayText(feature: AiDisclosureFeature): String = withContext(Dispatchers.IO) {
        val remote = fetchSnapshot()
        // Concurrent screens can finish in a different order than their requests started.
        val latest = synchronized(this@AiDisclosureRepository) {
            val cached = readCache()?.let(::parseSnapshot)
            when {
                remote == null -> cached
                cached != null && cached.revision > remote.revision -> cached
                else -> remote.also { writeCache(it.json) }
            }
        }
        (latest ?: bundledSnapshot()).displayText(feature)
    }

    private fun bundledSnapshot(): Snapshot? = try {
        readBundled()?.takeUnless { PLACEHOLDER in it }?.let(::parseSnapshot)
    } catch (_: IOException) {
        null
    }

    private fun fetchSnapshot(): Snapshot? = try {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/functions/v1/ai-disclosure?platform=phone")
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

    private data class Snapshot(val json: String, val revision: Long, val features: Map<AiDisclosureFeature, List<String>>)

    private fun Snapshot?.displayText(feature: AiDisclosureFeature): String =
        this?.features?.get(feature)?.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: UNAVAILABLE

    private fun parseSnapshot(json: String): Snapshot? = try {
        val root = JSONObject(json)
        val revision = root.get("revision")
        val updatedAt = root.get("updatedAt")
        require(revision is Int || revision is Long)
        require(updatedAt is Int || updatedAt is Long)
        val features = root.getJSONObject("features")
        val texts = AiDisclosureFeature.entries.associateWith { feature ->
            if (!features.has(feature.key)) {
                emptyList()
            } else {
                val entries = features.getJSONArray(feature.key)
                List(entries.length()) { index ->
                    val entry = entries.getJSONObject(index)
                    val text = entry.get("displayText")
                    require(text is String && text.isNotBlank() && PLACEHOLDER !in text)
                    text
                }
            }
        }
        Snapshot(json, (revision as Number).toLong(), texts)
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        const val UNAVAILABLE = "所用模型信息暂不可用，请联网后重试"
        private const val PLACEHOLDER = "__FILL_BEFORE_RELEASE__"
        private const val ASSET = "ai-disclosure-default.json"

        fun create(context: Context): AiDisclosureRepository {
            val preferences = context.getSharedPreferences("ai_disclosure", Context.MODE_PRIVATE)
            val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
            return AiDisclosureRepository(
                baseUrl = BuildConfig.WATCHRSS_PRODUCTION_BACKEND_BASE_URL,
                client = client,
                readCache = { preferences.getString("phone", null) },
                writeCache = { preferences.edit().putString("phone", it).apply() },
                readBundled = { context.assets.open(ASSET).bufferedReader().use { it.readText() } }
            )
        }
    }
}
