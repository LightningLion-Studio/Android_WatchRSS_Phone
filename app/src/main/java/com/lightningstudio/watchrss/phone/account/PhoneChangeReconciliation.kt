package com.lightningstudio.watchrss.phone.account

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** Read authenticated identity and its RLS-scoped profile after a single-use transaction response was lost. */
internal fun reconcilePhoneChange(environment: AccountEnvironment, session: PhoneAccountSession,
    requestedPhone: String, execute: (Request) -> Response): String? {
    fun request(path: String) = Request.Builder().url(environment.backendBaseUrl.trimEnd('/') + path)
        .header("apikey", environment.supabaseAnonKey).header("authorization", "Bearer ${session.accessToken}").get()
    fun read(request: Request): String = execute(request).use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw PhoneAccountHttpException(response.code, body)
        body
    }
    val user = JSONObject(read(request("/auth/v1/user").build()))
    check(user.getString("id") == session.userId) { "phone_change_account_changed" }
    if (normalizeAccountPhone(user.getString("phone")) != normalizeAccountPhone(requestedPhone)) return null
    val url = (environment.backendBaseUrl.trimEnd('/') + "/rest/v1/profiles").toHttpUrl().newBuilder()
        .addQueryParameter("select", "user_id,phone_masked").addQueryParameter("user_id", "eq.${session.userId}").build()
    val rows = JSONArray(read(request("/rest/v1/profiles").url(url).build()))
    require(rows.length() == 1) { "无法读取当前账号手机号" }
    val row = rows.getJSONObject(0)
    check(row.getString("user_id") == session.userId) { "phone_change_account_changed" }
    return row.getString("phone_masked").also { require(it.isNotBlank()) { "无法读取当前账号手机号" } }
}
