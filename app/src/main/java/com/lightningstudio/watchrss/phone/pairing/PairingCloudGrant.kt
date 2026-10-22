package com.lightningstudio.watchrss.phone.pairing

import com.lightningstudio.watchrss.phone.cloud.*
import org.json.JSONArray
import org.json.JSONObject

/** Contains only encrypted key envelopes. Never place the account-key bytes in UI state. */
class PairingCloudGrant internal constructor(val keyVersion:Int,internal val payload:String,
    internal val envelopes:List<CloudKeyEnvelope>,internal val attestation:String,
    internal val registered:RegisteredCloudDevice) {
    fun message(userId:String,epoch:String):JSONObject = JSONObject().put("action","key").put("userId",userId).put("libraryEpoch",epoch).put("payload",payload)
    override fun toString():String="PairingCloudGrant(redacted)"
}

/** R28 checks precede wrapping; R7/R16 checks precede any server envelope write. */
internal fun buildPairingCloudGrant(userId:String,phoneDeviceId:String,bootstrap:CloudBootstrap,
    qr:WatchPairingQr,preparedSpki:String,keys:Map<Int,ByteArray>):PairingCloudGrant {
    val watch=bootstrap.devices.firstOrNull { it.deviceId==qr.deviceId && it.revokedAt==null && !it.deviceId.startsWith("reader-migration-") }
        ?:throw PairingException("watch_account_confirmation_required","手表尚未自行登记云设备，请在手表完成登录后重试")
    if(watch.watchSelfRegisteredAt.isNullOrBlank() || watch.platform !in setOf("watch","android_watch","podjs"))
        throw PairingException("watch_account_confirmation_required","手表尚未自行登记云设备，请更新手表后重试")
    val spki=preparedSpki.base64Bytes()
    require(watch.publicKeySpki.base64Bytes().contentEquals(spki)) { "手表登记公钥与配对码不一致，请先移除旧手表设备再重新绑定" }
    val publicKey=CloudKeyEnvelopeCodec.decodeP256PublicKey(preparedSpki)
    CloudKeyTrust.validate(userId,bootstrap.libraryEpoch,bootstrap.keyVersionFloor,bootstrap.keyCommitments,keys)
    require(keys.keys==bootstrap.keyCommitments.map {it.keyVersion}.toSet()) { "缺少历史云密钥，请先用24词恢复全部版本" }
    val latest=keys.keys.max();val attestation=CloudProtocolV2.attestation(keys.getValue(latest),userId,bootstrap.libraryEpoch,qr.deviceId,spki)
    val envelopes=keys.toSortedMap().map { (version,key) -> CloudKeyEnvelopeCodec.createDeviceEnvelope(key,userId,qr.deviceId,publicKey,version) }
    val rows=JSONArray()
    envelopes.forEach { env -> rows.put(JSONObject().put("keyVersion",env.keyVersion).put("envelope",env.wireJson()
        .put("recipientType","device").put("recipientDeviceId",qr.deviceId).put("createdByDeviceId",phoneDeviceId))) }
    val payload=JSONObject().put("envelopes",rows).put("attestation",JSONObject().put("keyVersion",latest).put("mac",attestation)).toString()
    if(payload.toByteArray(Charsets.UTF_8).size>PairingWireCodec.MAX_KEY_PAYLOAD_BYTES) PairingWireCodec.tooLarge()
    val grant=PairingCloudGrant(latest,payload,envelopes,attestation,watch)
    PairingWireCodec.checkMessage(qr,grant.message(userId,bootstrap.libraryEpoch))
    return grant
}
