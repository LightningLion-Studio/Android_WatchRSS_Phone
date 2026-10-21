package com.lightningstudio.watchrss.phone.cloud

/** Only phone/mini may publish commitments. A reset's empty epoch cannot be claimed by orphan keys. */
internal fun legacyMigrationAuthorized(
    bootstrap: CloudBootstrap, deviceId: String, localSpki: String, keys: Map<Int, ByteArray>,
    unwrap: (CloudKeyEnvelope) -> ByteArray
): Boolean {
    if (!legacyMigrationHasCompleteKeySet(bootstrap, keys.keys)) return false
    val device = bootstrap.devices.firstOrNull { it.deviceId == deviceId && it.revokedAt == null } ?: return false
    if (!runCatching { device.publicKeySpki.base64Bytes().contentEquals(localSpki.base64Bytes()) }.getOrDefault(false)) return false
    return keys.all { (version, key) ->
        val envelope = deviceApprovalEnvelopes(bootstrap, deviceId).firstOrNull { it.envelope.keyVersion == version }?.envelope ?: return@all false
        runCatching { val recovered = unwrap(envelope); try { recovered.contentEquals(key) } finally { recovered.fill(0) } }.getOrDefault(false)
    }
}

/** R19 includes device and recovery envelopes, including versions not addressed to this device. */
internal fun legacyMigrationHasCompleteKeySet(bootstrap: CloudBootstrap, versions: Set<Int>): Boolean {
    val visible = bootstrap.keyEnvelopes.map { it.envelope.keyVersion }.toSet() + bootstrap.legacyKeyVersions
    val ordered = versions.sorted()
    return visible.isNotEmpty() && ordered.isNotEmpty() && versions.containsAll(visible) &&
        ordered.first() == visible.min() && ordered.last() == visible.max() && ordered.first() > bootstrap.keyVersionFloor &&
        ordered.zipWithNext().all { (a, b) -> b == a + 1 }
}
internal class CloudLegacyMigrationPendingException : IllegalStateException("缺少完整的历史密钥版本，请等待其他设备迁移或使用24词恢复")

internal fun requireCloudFirstActivationAllowed(bootstrap: CloudBootstrap) {
    require(bootstrap.keyCommitments.isEmpty() && bootstrap.keyEnvelopes.isEmpty() && bootstrap.legacyKeyVersions.isEmpty()) {
        "账号已有云密钥或历史资料，请使用恢复或批准"
    }
}

/** Only an immutable key-version race may be resolved by re-reading bootstrap. */
internal suspend fun storeLegacyCommitments(client: PhoneCloudClient,
    session: com.lightningstudio.watchrss.phone.account.PhoneAccountSession, deviceId: String,
    rows: List<CloudKeyCommitment>, expectedEpoch: String) {
    try { client.storeCommitments(session, deviceId, rows, expectedEpoch) }
    catch (error: CloudHttpException) {
        if (error.status != 409 || runCatching { org.json.JSONObject(error.detail).optString("error") }.getOrDefault("") != "key_version_exists") throw error
    }
}
