package com.lightningstudio.watchrss.phone.support

import com.lightningstudio.watchrss.phone.account.AccountEnvironment
import com.lightningstudio.watchrss.phone.account.PhoneAccountSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SupportFailure(val code: String) : Exception(when(code) {
    "support_disabled" -> "AI 客服暂未开放，请使用人工客服"
    "support_consent_required", "support_agreement_changed" -> "请阅读并同意最新 AI 客服协议"
    "support_rate_limited" -> "客服请求较多，请稍后重试（每分钟 5 条，每天 100 条）"
    "support_request_in_progress" -> "上一条问题仍在处理中，请稍后点击重试"
    "invalid_session", "support_login_required" -> "登录已失效，请重新登录"
    else -> "客服暂时无法回答，请重试或联系人工客服"
})

class SupportClient(private val environment: AccountEnvironment, private val sessions: StateFlow<PhoneAccountSession?>) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(135, TimeUnit.SECONDS).callTimeout(140, TimeUnit.SECONDS).build()
    fun cancel() = http.dispatcher.cancelAll()
    fun identity(): String? = sessions.value?.takeUnless { it.isExpired }?.userId

    private fun request(user: String, path: String, body: JSONObject?): Request {
        val session = sessions.value?.takeIf { it.userId == user && !it.isExpired }
            ?: throw SupportFailure("support_login_required")
        return Request.Builder().url(environment.backendBaseUrl + "/functions/v1/support" + path)
            .header("apikey", environment.supabaseAnonKey)
            .header("authorization", "Bearer ${session.accessToken}")
            .apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType())) }
            .build()
    }
    suspend fun json(user: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(request(user, path, body)).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (identity() != user) throw SupportFailure("support_login_required")
            val data = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if (!response.isSuccessful) throw SupportFailure(if(response.code == 401) "invalid_session" else data.optString("error").ifBlank { data.optString("code") })
            data
        }
    }
    suspend fun send(user: String, conversation: String, id: String, content: String, event: suspend (String, JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("request_id", id).put("content", content)
        http.newCall(request(user, "/conversations/$conversation/messages", body)).execute().use { response ->
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                throw SupportFailure(if(response.code == 401) "invalid_session" else error.optString("error").ifBlank { error.optString("code") })
            }
            check(response.header("Content-Type").orEmpty().startsWith("text/event-stream")) { "客服响应格式错误" }
            var name = "message"
            val data = StringBuilder()
            var completed = false
            val source = response.body?.source() ?: error("客服响应为空")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (identity() != user) throw SupportFailure("support_login_required")
                if (line.startsWith("event:")) name = line.substringAfter(':').trim()
                else if (line.startsWith("data:")) { if(data.isNotEmpty()) data.append('\n'); data.append(line.substringAfter(':').trimStart()) }
                else if (line.isEmpty() && data.isNotEmpty()) {
                    val value = JSONObject(data.toString()); data.clear()
                    if (name == "error") throw SupportFailure(value.optString("code"))
                    event(name, value)
                    if (name == "done") { completed = true; break }
                    name = "message"
                }
            }
            check(completed) { "连接中断，请重试获取回答" }
        }
    }
}
