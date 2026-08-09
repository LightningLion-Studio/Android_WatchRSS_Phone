package com.lightningstudio.watchrss.phone.account

import org.json.JSONObject
import java.io.IOException

internal enum class AccountLoginAction {
    REQUEST_OTP,
    VERIFY_OTP,
    PASSKEY_LOGIN
}

internal class PhoneAccountHttpException(
    val statusCode: Int,
    val responseBody: String
) : IOException("账号请求失败（HTTP $statusCode）")

internal fun accountLoginErrorMessage(
    action: AccountLoginAction,
    throwable: Throwable
): String {
    val httpError = throwable.findAccountHttpException()
    if (httpError == null) {
        if (throwable.hasIoCause()) {
            return "网络连接失败，请检查网络后重试"
        }
        return when (action) {
            AccountLoginAction.REQUEST_OTP -> "验证码发送失败，请稍后重试"
            AccountLoginAction.VERIFY_OTP -> "登录失败，请稍后重试"
            AccountLoginAction.PASSKEY_LOGIN -> "通行密钥登录失败，请重试或改用短信验证码"
        }
    }

    val status = httpError.statusCode
    val response = parseErrorResponse(httpError.responseBody)
    val code = response.code.lowercase()

    if (status == 429 || code.contains("rate_limit")) {
        return "操作太频繁，请稍后再试"
    }

    if (status >= 500 || code == "unexpected_failure") {
        return if (action == AccountLoginAction.REQUEST_OTP) {
            "短信服务暂时繁忙，请稍后重试"
        } else {
            "账号服务暂时不可用，请稍后重试"
        }
    }

    return when (action) {
        AccountLoginAction.REQUEST_OTP -> {
            if (status in setOf(400, 422) || code in INVALID_PHONE_CODES) {
                "手机号格式不正确，请检查后重试"
            } else {
                "验证码发送失败，请稍后重试"
            }
        }
        AccountLoginAction.VERIFY_OTP -> {
            if (status in setOf(400, 401, 403, 422) || code in INVALID_OTP_CODES) {
                "验证码无效或已过期，请重新获取"
            } else {
                "登录失败，请稍后重试"
            }
        }
        AccountLoginAction.PASSKEY_LOGIN ->
            "通行密钥登录失败，请重试或改用短信验证码"
    }
}

private data class AccountErrorResponse(val code: String)

private fun parseErrorResponse(raw: String): AccountErrorResponse {
    val json = runCatching { JSONObject(raw) }.getOrNull()
    val code = json?.optString("error_code").orEmpty()
        .ifBlank { json?.optString("error").orEmpty() }
        .ifBlank {
            json?.opt("code")
                ?.takeIf { it is String }
                ?.toString()
                .orEmpty()
        }
        .trim()
    return AccountErrorResponse(code = code)
}

internal fun Throwable.findAccountHttpException(): PhoneAccountHttpException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is PhoneAccountHttpException) return current
        current = current.cause
    }
    return null
}

private fun Throwable.hasIoCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is IOException) return true
        current = current.cause
    }
    return false
}

private val INVALID_PHONE_CODES = setOf(
    "invalid_phone",
    "validation_failed"
)

private val INVALID_OTP_CODES = setOf(
    "invalid_credentials",
    "otp_expired",
    "validation_failed"
)
