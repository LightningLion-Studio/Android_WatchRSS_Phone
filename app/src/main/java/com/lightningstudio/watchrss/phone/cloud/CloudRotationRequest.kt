package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject

/** Field set follows backend cloud_v2::Rotate (deny_unknown_fields). */
internal fun cloudRotationBody(userId: String, epoch: String, deviceId: String, publicKeySpki: String,
    setup: RecoveryKeySetup, previous: ByteArray?): JSONObject {
    val version = setup.envelope.keyVersion
    val commitment = CloudProtocolV2.commitment(setup.pendingKey, userId, epoch, version)
    return JSONObject().put("deviceId", deviceId).put("keyVersion", version).put("commitment", commitment)
        .put("rotationProof", previous?.let { CloudProtocolV2.rotationProof(it, userId, epoch, version, commitment) } ?: JSONObject.NULL)
        .put("recoveryEnvelope", setup.envelope.wireJson().put("recipientType", "recovery")
            .put("recipientDeviceId", JSONObject.NULL).put("createdByDeviceId", deviceId))
        .put("deviceEnvelope", CloudKeyEnvelopeCodec.createDeviceEnvelope(setup.pendingKey, userId, deviceId,
            CloudKeyEnvelopeCodec.decodeP256PublicKey(publicKeySpki), version).wireJson()
            .put("recipientType", "device").put("recipientDeviceId", deviceId).put("createdByDeviceId", deviceId))
}
