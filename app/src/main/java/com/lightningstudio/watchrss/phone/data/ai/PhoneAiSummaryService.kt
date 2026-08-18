package com.lightningstudio.watchrss.phone.data.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class PhoneAiConfig(
    val enabled: Boolean,
    val autoSummarize: Boolean,
    val showTokenUsage: Boolean
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

    init {
        plain.edit()
            .remove("ai_provider")
            .remove("ai_model")
            .remove("ai_base_url")
            .remove("ai_prompt")
            .apply()
    }

    fun config(): PhoneAiConfig = PhoneAiConfig(
        enabled = plain.getBoolean("ai_enabled", false),
        autoSummarize = plain.getBoolean("ai_auto", false),
        showTokenUsage = plain.getBoolean("ai_token_usage", false)
    )

    fun saveConfig(value: PhoneAiConfig) {
        plain.edit()
            .putBoolean("ai_enabled", value.enabled)
            .putBoolean("ai_auto", value.autoSummarize)
            .putBoolean("ai_token_usage", value.showTokenUsage)
            .apply()
    }

    fun clearLegacyApiKey() = encrypted.edit().remove("api_key").apply()
    fun saveDouyinCookie(value: String) = encrypted.edit().putString("douyin_cookie", value).apply()
    fun douyinCookie(): String = encrypted.getString("douyin_cookie", "").orEmpty()

    companion object {
        private const val PREFS = "phone_feature_settings"
        private const val SECRET_PREFS = "phone_local_secrets"
    }
}

class PhoneAiSummaryService(
    private val environment: AccountEnvironment,
    private val accountRepository: PhoneAccountRepository,
    private val deviceAccessToken: () -> String?
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun summarize(article: PhoneArticleEntity, promptPreset: Int = 1): PhoneAiSummaryResult =
        withContext(Dispatchers.IO) {
            val session = accountRepository.session.value ?: error("请先登录腕上RSS账号")
            val deviceToken = deviceAccessToken().orEmpty()
            require(deviceToken.isNotBlank()) { "请先完成 6 元授权" }
            val source = if (!article.contentHtml.isNullOrBlank()) {
                Jsoup.parse(article.contentHtml.orEmpty()).text()
            } else {
                article.contentText.ifBlank { article.excerpt }
            }
            val body = JSONObject().apply {
                put("title", article.title)
                put("content", source)
                article.contentHash.takeIf { it.matches(CONTENT_HASH) }?.let {
                    put("contentHash", it)
                }
                put("promptPreset", promptPreset.coerceIn(1, 4))
                put("stream", false)
            }
            val request = Request.Builder()
                .url("${environment.backendBaseUrl}/api/v1/llm/default-model/article-summary")
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("x-watchrss-device-authorization", "Bearer $deviceToken")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                require(response.isSuccessful) {
                    when (response.code) {
                        402 -> "AI 总结仅限已购买 6 元授权的用户"
                        401, 403 -> "账号或设备授权已失效，请重新登录"
                        else -> json.optString("detail").ifBlank {
                            json.optString("error").ifBlank { "HTTP ${response.code}" }
                        }
                    }
                }
                val usage = json.optJSONObject("usage")
                PhoneAiSummaryResult(
                    text = json.optString("text").ifBlank { error("服务未返回摘要") },
                    promptTokens = usage?.optInt("inputTokens")?.takeIf { it > 0 },
                    completionTokens = usage?.optInt("outputTokens")?.takeIf { it > 0 },
                    totalTokens = usage?.optInt("totalTokens")?.takeIf { it > 0 }
                )
            }
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val CONTENT_HASH = Regex("^[0-9a-f]{64}$")
    }
}
