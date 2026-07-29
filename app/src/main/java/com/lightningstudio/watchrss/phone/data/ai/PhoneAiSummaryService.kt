package com.lightningstudio.watchrss.phone.data.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class PhoneAiConfig(
    val enabled: Boolean,
    val autoSummarize: Boolean,
    val showTokenUsage: Boolean,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val prompt: String
)

data class PhoneAiSummaryResult(
    val text: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?
)

class PhoneAiSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val plain = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val encrypted by lazy {
        val key = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECRET_PREFS,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun config(): PhoneAiConfig = PhoneAiConfig(
        enabled = plain.getBoolean("ai_enabled", false),
        autoSummarize = plain.getBoolean("ai_auto", false),
        showTokenUsage = plain.getBoolean("ai_token_usage", false),
        provider = plain.getString("ai_provider", "OpenAI compatible").orEmpty(),
        model = plain.getString("ai_model", "gpt-4o-mini").orEmpty(),
        baseUrl = plain.getString("ai_base_url", "https://api.openai.com/v1").orEmpty(),
        prompt = plain.getString("ai_prompt", DEFAULT_PROMPT).orEmpty()
    )

    fun saveConfig(value: PhoneAiConfig) {
        plain.edit()
            .putBoolean("ai_enabled", value.enabled)
            .putBoolean("ai_auto", value.autoSummarize)
            .putBoolean("ai_token_usage", value.showTokenUsage)
            .putString("ai_provider", value.provider.trim())
            .putString("ai_model", value.model.trim())
            .putString("ai_base_url", value.baseUrl.trim())
            .putString("ai_prompt", value.prompt)
            .apply()
    }

    fun apiKey(): String = encrypted.getString("api_key", "").orEmpty()
    fun saveApiKey(value: String) = encrypted.edit().putString("api_key", value.trim()).apply()
    fun saveDouyinCookie(value: String) =
        encrypted.edit().putString("douyin_cookie", value).apply()
    fun douyinCookie(): String = encrypted.getString("douyin_cookie", "").orEmpty()

    companion object {
        private const val PREFS = "phone_feature_settings"
        private const val SECRET_PREFS = "phone_local_secrets"
        private const val DEFAULT_PROMPT = "请准确、简洁地总结文章重点。"
    }
}

class PhoneAiSummaryService(
    private val store: PhoneAiSettingsStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun summarize(article: PhoneArticleEntity): PhoneAiSummaryResult =
        withContext(Dispatchers.IO) {
            val config = store.config()
            require(config.enabled) { "AI 总结未启用" }
            val apiKey = store.apiKey()
            require(apiKey.isNotBlank()) { "未配置 API Key" }
            require(config.baseUrl.startsWith("https://") || config.baseUrl.startsWith("http://")) {
                "Base URL 无效"
            }
            require(config.model.isNotBlank()) { "模型不能为空" }
            val source = if (!article.contentHtml.isNullOrBlank()) {
                Jsoup.parse(article.contentHtml.orEmpty()).text()
            } else {
                article.contentText.ifBlank { article.excerpt }
            }
            val chunks = source.chunkForSummary()
            val partials = chunks.mapIndexed { index, chunk ->
                coroutineContext.ensureActive()
                complete(
                    config = config,
                    apiKey = apiKey,
                    prompt = "${config.prompt}\n这是第 ${index + 1}/${chunks.size} 段：\n$chunk"
                )
            }
            if (partials.size == 1) return@withContext partials.single()
            val merged = complete(
                config = config,
                apiKey = apiKey,
                prompt = "${config.prompt}\n请合并以下分段摘要，消除重复：\n" +
                    partials.joinToString("\n\n") { it.text }
            )
            merged.copy(
                promptTokens = partials.mapNotNull { it.promptTokens }.sum().takeIf { it > 0 },
                completionTokens = partials.mapNotNull { it.completionTokens }.sum().takeIf { it > 0 },
                totalTokens = (
                    partials.mapNotNull { it.totalTokens }.sum() +
                        (merged.totalTokens ?: 0)
                    ).takeIf { it > 0 }
            )
        }

    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val config = store.config()
        val result = complete(config, store.apiKey(), "只回复：连接成功")
        result.text.ifBlank { "连接成功" }
    }

    private fun complete(
        config: PhoneAiConfig,
        apiKey: String,
        prompt: String
    ): PhoneAiSummaryResult {
        val body = JSONObject().apply {
            put("model", config.model)
            put("stream", false)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                )
            )
        }
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            require(response.isSuccessful) {
                runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { "HTTP ${response.code}" }
            }
            val json = JSONObject(raw)
            val usage = json.optJSONObject("usage")
            return PhoneAiSummaryResult(
                text = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty(),
                promptTokens = usage?.optInt("prompt_tokens")?.takeIf { it > 0 },
                completionTokens = usage?.optInt("completion_tokens")?.takeIf { it > 0 },
                totalTokens = usage?.optInt("total_tokens")?.takeIf { it > 0 }
            )
        }
    }
}

private fun String.chunkForSummary(): List<String> {
    val clean = replace(Regex("\\s+"), " ").trim()
    if (clean.isBlank()) return listOf("（正文为空）")
    if (clean.length <= 12_000) return listOf(clean)
    return clean.chunked(10_000).take(20)
}
