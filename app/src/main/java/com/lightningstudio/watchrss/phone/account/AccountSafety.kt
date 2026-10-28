package com.lightningstudio.watchrss.phone.account

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

data class PendingPhoneChange(val appealId:String,val status:String,val effectiveAt:Long,val phoneMasked:String,val cancellable:Boolean) {
    val message:String get() { val date=Instant.ofEpochMilli(effectiveAt).atZone(ZoneOffset.UTC).toLocalDate();return "手机号将于${date.year}年${date.monthValue}月${date.dayOfMonth}日变更，如非本人操作请联系支持" }
}
data class AccountSafetyProfile(val userId:String,val phoneMasked:String,val pendingPhoneChange:PendingPhoneChange?)
internal fun parseAccountSafetyProfile(value:JSONObject):AccountSafetyProfile {
    val pending=value.optJSONObject("pendingPhoneChange")?.let { p ->
        val id=p.getString("appealId");require(id.matches(Regex("[A-Za-z0-9-]{1,128}")))
        val status=p.getString("status");require(status in setOf("cooling","ready"))
        val time=p.getLong("effectiveAt");require(time>0)
        val mask=p.getString("phoneMasked");require(mask.contains('*'))
        PendingPhoneChange(id,status,time,mask,p.optBoolean("cancellable",false))
    }
    return AccountSafetyProfile(value.getString("userId"),value.getString("phoneMasked"),pending)
}
internal fun accountSafetyErrorMessage(error:Exception):String {
    val code=(error as? PhoneAccountHttpException)?.let { runCatching { JSONObject(it.responseBody).optString("error") }.getOrDefault("") }
    return when(code){
        "phone_appeal_completed" -> "手机号已完成变更，请刷新账号信息；如非本人操作请联系支持"
        "phone_appeal_not_found" -> "待处理换绑已不存在，请刷新账号信息"
        "wechat_login_linked_to_other_account" -> "当前微信已关联其他账号，请登录原账号解绑，或联系支持；不会转移或合并资料"
        "wechat_login_identity_mismatch" -> "请使用原账号和已关联微信完成验证"
        else -> "操作结果尚未确认，请刷新核对；如非本人操作请联系支持"
    }
}
