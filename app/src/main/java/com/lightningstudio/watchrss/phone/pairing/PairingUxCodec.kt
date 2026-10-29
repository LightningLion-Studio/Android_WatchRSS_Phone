package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.account.accountHttpErrorCode
import com.lightningstudio.watchrss.phone.account.findAccountHttpException
import org.json.JSONObject

/** No secrets, remote messages, or old-account identifiers are exposed to UI/logging. */
data class PairingConfirmation(val phoneConfirmed:Boolean=false,val watchConfirmed:Boolean=false)
internal data class PairingConsentReceipt(val phone:String,val watch:String,val accountConflict:Boolean) {
    val rejected get()=phone=="rejected" || watch=="rejected"
    val bothConfirmed get()=phone=="confirmed" && watch=="confirmed"
}
internal object PairingUxCodec {
    const val SEQUENCE_MAX=255
    private val conflicts=setOf("account_switch_requires_logout","device_owned_by_another_account","installation_owned_by_another_account")
    fun ownershipConflict(error:Throwable):Boolean = error.findAccountHttpException()?.statusCode==409 && error.accountHttpErrorCode() in conflicts
    fun requireSupport(receipt:JSONObject) {
        if(receipt.opt("pairingUxVersion")!=1 || receipt.optJSONObject("capabilities")?.opt("pairingConfirmV1")!=true || receipt.opt("messageSequenceMax")!=SEQUENCE_MAX)
            throw PairingException("pairing_upgrade_required","请更新手机和手表后重新扫码；当前版本不支持安全的双端确认")
    }
    fun prepare(receipt:JSONObject,qr:WatchPairingQr,userId:String):PairingConsentReceipt {
        requireSupport(receipt)
        require(receipt.opt("success")==true && receipt.opt("deviceId")==qr.deviceId && receipt.opt("userId")==userId && receipt.opt("installId")==qr.installId) { "手表身份与配对码不一致，请重新扫码" }
        identity(receipt,qr,userId)
        com.lightningstudio.watchrss.phone.cloud.CloudKeyEnvelopeCodec.decodeP256PublicKey(receipt.getString("publicKeySpki"))
        return status(receipt,qr,userId).also { require(it.phone=="pending") { "手表核对状态无效，请重新扫码" } }
    }
    fun canReplace(receipt:JSONObject)=receipt.getJSONObject("capabilities").opt("pairingReplaceAccountV1")==true
    fun identity(receipt:JSONObject,qr:WatchPairingQr,userId:String) {
        for((key,expected) in mapOf("userId" to userId,"deviceId" to qr.deviceId,"installId" to qr.installId,"sas" to PairingWireCodec.sas(qr,userId))) {
            require(!receipt.has(key) || receipt.opt(key)==expected) { "手表身份或配对码不一致，请重新扫码" }
        }
    }
    fun status(receipt:JSONObject,qr:WatchPairingQr,userId:String):PairingConsentReceipt {
        require(receipt.opt("success")==true) { "手表未确认核对状态，请重新扫码" }
        identity(receipt,qr,userId)
        val status=receipt.getJSONObject("confirmation")
        fun decision(key:String):String = (status.opt(key) as? String)?.also {
            require(it in setOf("pending","confirmed","rejected")) { "手表核对状态无效，请重新扫码" }
        } ?: throw PairingException("pairing_status_invalid","手表核对状态无效，请重新扫码")
        require(receipt.opt("accountConflict") is Boolean) { "手表账号状态无效，请重新扫码" }
        return PairingConsentReceipt(decision("phone"),decision("watch"),receipt.getBoolean("accountConflict"))
    }
    fun replacement(receipt:JSONObject,qr:WatchPairingQr,userId:String,spki:String) {
        identity(receipt,qr,userId)
        require(receipt.opt("userId")==userId && receipt.opt("deviceId")==qr.deviceId && receipt.opt("installId")==qr.installId && receipt.opt("publicKeySpki")==spki) { "手表身份已变化，请重新扫码核对" }
        require(receipt.opt("success")==true && receipt.opt("action")=="replaceAccount" && receipt.opt("released")==true && receipt.opt("cleared")==true) {
            "手表退出或清理未完成，请重试；本次尚未绑定新账号"
        }
    }
}

/** An authenticated negative reply consumes its sequence, but never counts as an approval. */
internal class PairingPeerException(val peerCode:String):java.io.IOException(when(peerCode) {
    "pairing_rejected","watch_rejected" -> "手表已取消配对，请重新扫描"
    "device_revoked" -> "手表设备授权已撤销，请联系支持恢复"
    "pairing_replacement_unavailable","pairing_release_unavailable","pairing_release_disabled","pairing_release_proof_unavailable" -> "当前手表暂不支持退出并绑定，请更新手表后重试"
    "pairing_release_failed" -> "手表退出未完成，请检查网络后重试"
    "pairing_upgrade_required" -> "请更新手机和手表后重新扫码"
    "pairing_confirmation_expired" -> "两端核对超时，请重新扫码"
    "pairing_identity_changed","pairing_release_binding_changed" -> "手表身份已变化，请重新扫码核对"
    "pairing_clear_failed" -> "手表本地资料清理未完成，请重试"
    else -> "手表未完成操作，请检查手表后重试"
})
