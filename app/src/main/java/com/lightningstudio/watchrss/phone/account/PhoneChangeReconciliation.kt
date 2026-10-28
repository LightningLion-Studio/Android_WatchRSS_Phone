package com.lightningstudio.watchrss.phone.account

import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Uses the backend account bearer, including wrp_ sessions that GoTrue cannot read. */
internal fun reconcilePhoneChange(environment: AccountEnvironment, session: PhoneAccountSession,
    transactionId: String, execute: (Request) -> Response): String? {
    require(transactionId.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "换绑事务无效" }
    val request = Request.Builder().url(environment.backendBaseUrl.trimEnd('/') +
        "/functions/v1/account/phone/change/$transactionId")
        .header("apikey", environment.supabaseAnonKey).header("authorization", "Bearer ${session.accessToken}").get().build()
    val data = execute(request).use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw PhoneAccountHttpException(response.code, body)
        JSONObject(body)
    }
    check(data.getString("transactionId") == transactionId) { "换绑响应不匹配" }
    return when(data.getString("status")) {
        "completed" -> data.getString("phoneMasked").also { require(it.contains('*')) { "无法读取当前账号手机号" } }
        "pending" -> null
        "expired", "superseded" -> throw PhoneAccountHttpException(409, "{\"error\":\"phone_change_expired\"}")
        "blocked" -> throw PhoneAccountHttpException(429, "{\"error\":\"phone_change_rate_limited\"}")
        else -> error("换绑响应无效")
    }
}
