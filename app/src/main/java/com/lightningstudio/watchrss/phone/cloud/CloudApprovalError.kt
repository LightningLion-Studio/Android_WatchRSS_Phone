package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject

internal fun cloudApprovalRequestError(error: Throwable): String {
    val detail = error.message.orEmpty()
    val code = runCatching { JSONObject(detail.substringAfter(": ")).optString("error") }
        .getOrDefault("")
    return when (code) {
        "approval_device_mismatch", "device_session_invalid", "device_session_expired",
        "device_session_revoked", "device_revoked" -> "本机设备授权已失效，请重新登录后再请求批准"
        "app_access_expired" -> "手机版使用授权已到期，请先在账号页检查授权状态"
        "cloud_membership_required", "membership_required", "cloud_read_only" -> "当前云会员状态暂不支持设备授权，请检查云会员状态"
        "approval_rate_limited" -> "请求次数较多，请稍后再试"
        else -> "暂时无法发起授权请求，请检查网络后重试"
    }
}
