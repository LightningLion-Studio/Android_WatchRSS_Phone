package com.lightningstudio.watchrss.phone.model

data class AbilityResponse(
    val code: String,
    val name: String,
    val version: String
)

data class HealthResponse(
    val status: String
)

data class RSSUrlRequest(
    val url: String
)

data class RSSUrlResponse(
    val success: Boolean,
    val message: String
)

data class FavoriteItem(
    val id: String,
    val title: String,
    val link: String,
    val summary: String,
    val channelTitle: String,
    val pubDate: String
)

data class FavoritesResponse(
    val success: Boolean,
    val data: List<FavoriteItem>?
)

data class WatchLaterItem(
    val id: String,
    val title: String,
    val link: String,
    val summary: String,
    val channelTitle: String,
    val pubDate: String
)

data class WatchLaterResponse(
    val success: Boolean,
    val data: List<WatchLaterItem>?
)

// ── LLM 总结配置 ──────────────────────────────────────────────

/**
 * 支持的 LLM 提供商枚举（字符串值传给手表）
 */
enum class LLMProvider(val displayName: String, val value: String) {
    OPENAI("OpenAI (ChatGPT)", "openai"),
    DEEPSEEK("DeepSeek", "deepseek"),
    QWEN("通义千问", "qwen"),
    ZHIPU("智谱 GLM", "zhipu"),
    CUSTOM("自定义 (OpenAI 兼容)", "custom")
}

/**
 * 发送给手表的大模型配置请求体
 * POST /setLLMSummaryConfig
 */
data class LLMConfigRequest(
    val provider: String,       // LLMProvider.value
    val apiKey: String,
    val model: String,
    val baseUrl: String,        // 自定义端点时有效，其余可为空串
    val enabled: Boolean        // 是否开启自动总结
)

/**
 * 手表返回的配置写入结果
 */
data class LLMConfigResponse(
    val success: Boolean,
    val message: String
)

/**
 * 手表当前已保存的大模型配置（GET /getLLMSummaryConfig 返回）
 */
data class LLMConfigGetResponse(
    val success: Boolean,
    val data: LLMConfigData?
)

data class LLMConfigData(
    val provider: String,
    val apiKey: String,         // 手表回传时末位已脱敏，如 "sk-****abcd"
    val model: String,
    val baseUrl: String,
    val enabled: Boolean
)
