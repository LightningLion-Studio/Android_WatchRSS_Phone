package com.lightningstudio.watchrss.phone.cloud

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import com.lightningstudio.watchrss.phone.syncv2.SyncV2Codec

internal const val CLOUD_RESET_CAPABILITY = "cloud_storage_reset_v1"
internal const val CLOUD_RESET_HEADER = "x-watchrss-cloud-reset-version"
internal const val CLOUD_RESET_BLUETOOTH_PENDING = "旧云存储有无法核对的待确认蓝牙资料。原件已保留，云同步已暂停。"
internal const val CLOUD_RESET_OTHER_DEVICE = "云存储已在其他设备上重置，请输入新的恢复词"
internal const val CLOUD_RESET_UNCONFIRMED = "重置结果暂未确认，请稍后在云同步页查看"
internal const val CLOUD_RESET_QUOTA = "云存储空间不足，待同步内容已保留在本机"

internal fun cloudResetEntry(bootstrap: CloudBootstrap?, hasKey: Boolean, enabled: Boolean, blocked: Boolean): Boolean =
    enabled && !hasKey && !blocked && bootstrap?.cloudResetEnabled == true && bootstrap.member.writable

internal fun canSubmitCloudReset(saved: Boolean, remainingSeconds: Int): Boolean = saved && remainingSeconds == 0

internal class CloudResetIntent(json: JSONObject, expectedEpoch: String) {
    val token = json.getString("resetToken").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    val oldEpoch = json.getString("libraryEpoch").also { require(it == expectedEpoch) }
    val nextEpoch = json.getString("nextLibraryEpoch").also { UUID.fromString(it); require(it != oldEpoch) }
    val version = json.getInt("nextKeyVersion").also { require(it > 0) }
    val expires = Instant.parse(json.getString("expiresAt"))
}

internal class CloudResetPending(private val json: JSONObject) {
    val oldEpoch get() = json.getString("oldEpoch")
    val nextEpoch get() = json.getString("nextEpoch")
    val version get() = json.getInt("version")
    val commitment get() = json.getString("commitment")
    val key get() = json.getString("key").base64Bytes()
    val accepted get() = json.optJSONObject("accepted")
    fun withAccepted(result: JSONObject) = CloudResetPending(JSONObject(json.toString()).put("accepted", result))
    fun encode() = json.toString()

    fun accepts(response: JSONObject): Boolean = runCatching {
        response.getString("libraryEpoch") == nextEpoch &&
            response.getInt("keyVersion") == version &&
            UUID.fromString(response.getString("contentGeneration")).toString().isNotBlank() &&
            response.getString("cloudResetAt").isNotBlank() &&
            SyncV2Codec.scope(response.getJSONObject("syncV2Scope")).isNotBlank()
    }.getOrDefault(false)

    fun matches(user: String, bootstrap: CloudBootstrap): Boolean {
        if (bootstrap.libraryEpoch != nextEpoch || bootstrap.cloudResetAt.isNullOrBlank() ||
            bootstrap.contentGeneration.isNullOrBlank() || bootstrap.syncV2ResetScope == null ||
            runCatching { SyncV2Codec.scope(bootstrap.syncV2ResetScope) }.isFailure) return false
        val secret = key
        try {
            val computed = CloudProtocolV2.commitment(secret, user, nextEpoch, version)
            return CloudProtocolV2.equal(computed, commitment) &&
                bootstrap.keyCommitments.any { it.keyVersion == version && CloudProtocolV2.equal(it.commitment, computed) } &&
                (accepted == null || SyncV2Codec.scope(accepted!!.getJSONObject("syncV2Scope")) ==
                    SyncV2Codec.scope(bootstrap.syncV2ResetScope))
        } finally { secret.fill(0) }
    }

    companion object {
        fun stage(intent: CloudResetIntent, setup: RecoveryKeySetup, commitment: String) = CloudResetPending(
            JSONObject().put("oldEpoch", intent.oldEpoch).put("nextEpoch", intent.nextEpoch)
                .put("version", intent.version).put("commitment", commitment)
                .put("key", setup.pendingKey.base64()).put("words", setup.words.joinToString(" "))
                .put("startedAt", System.currentTimeMillis())
        )
    }
}

internal fun cloudResetSignal(status: Int, code: String, detail: String?, features: org.json.JSONArray? = null): Boolean =
    status == 409 && code == "cloud_reset" || status == 426 && (
        code == "client_upgrade_required" && detail == CLOUD_RESET_CAPABILITY ||
        code == "upgrade_required" && features != null &&
            (0 until features.length()).any { features.optString(it) == CLOUD_RESET_CAPABILITY }
    )

internal fun definitiveCloudResetRejection(error: CloudHttpException): Boolean = error.serverResponse && when (error.status) {
    400 -> cloudErrorCode(error) in setOf("cloud_reset_confirmation_required", "invalid_cloud_reset_key_material",
        "invalid_key_envelope", "invalid_crypto_encoding", "invalid_public_key")
    401 -> cloudErrorCode(error) in setOf("unauthorized", "invalid_token", "session_expired", "invalid_session")
    402 -> cloudErrorCode(error) == "cloud_membership_required"
    403 -> cloudErrorCode(error) in setOf("cloud_reset_phone_required", "cloud_reset_device_not_registered", "device_token_mismatch")
    409 -> cloudErrorCode(error) in setOf("cloud_reset_key_version_changed", "cloud_reset_key_version_exhausted", "cloud_write_busy")
    429 -> cloudErrorCode(error) == "cloud_reset_rate_limited"
    503 -> cloudErrorCode(error) in setOf("cloud_reset_disabled", "cloud_reset_repo_init_unavailable")
    else -> false
}

internal class CloudResetUiFailure(val text: String) : Exception(text)

internal fun cloudStorageResetError(error: Exception): String {
    if (error is CloudResetUiFailure) return error.text
    if (error.message == "reset_bluetooth_pending") return CLOUD_RESET_BLUETOOTH_PENDING
    if (error.message == "reset_record_conflict") return "新云存储中有同 ID 的本机资料。两份资料均已保留，迁移已暂停。"
    if (error is com.lightningstudio.watchrss.phone.syncv2.SyncV2CloudError) {
        return when (error.code) {
            "quota_exceeded" -> CLOUD_RESET_QUOTA
            "closure_incomplete", "reset_migration_required", "storage_pressure" ->
                "本机待同步内容尚未完成迁移，云同步已暂停"
            "feature_disabled" -> "当前无法初始化新云存储，本机待同步内容已保留"
            else -> "新云存储暂时无法连接，本机待同步内容已保留"
        }
    }
    if (error is IllegalStateException &&
        error.message in setOf("closure_incomplete", "reset_migration_required", "storage_pressure", "budget_paused")) {
        return "本机待同步内容尚未完成迁移，云同步已暂停"
    }
    val http = error as? CloudHttpException
    return when (http?.let(::cloudErrorCode)) {
        "cloud_reset_disabled", "cloud_reset_repo_init_unavailable" -> "当前无法重置云存储，请稍后再试"
        "cloud_membership_required" -> "当前账号无云存储写入资格，或有效配额不足"
        "quota_exceeded" -> CLOUD_RESET_QUOTA
        "cloud_reset_phone_required" -> "请在手机或小程序重置云存储"
        "cloud_reset_device_not_registered", "device_token_mismatch" -> "本机云端授权无效，请重新登记本机"
        "cloud_reset_intent_invalid" -> "重置确认已过期或失效，请重新确认"
        "cloud_reset_key_version_changed" -> "云端密钥已变化，请重新确认"
        "cloud_reset_key_version_exhausted" -> "云端密钥版本已用尽，无法重置"
        "cloud_reset_confirmation_required" -> "请重新确认重置云存储"
        "invalid_cloud_reset_key_material", "invalid_key_envelope", "invalid_crypto_encoding", "invalid_public_key" ->
            "新密钥验证失败，请重新确认"
        "cloud_write_busy" -> "云存储正在写入，请稍后再试"
        "cloud_reset_rate_limited" -> "重置操作过于频繁，请稍后再试"
        else -> when {
            http?.status == 401 -> "登录已过期，请重新登录"
            error is java.io.IOException -> "网络连接失败，请检查网络后重试"
            else -> "当前无法重置云存储，请稍后再试"
        }
    }
}

/** A reset POST is sent once. Only its own definitive rejection can retire the attempt. */
internal suspend fun executeCloudResetOnce(
    pending: CloudResetPending,
    stage: () -> Unit,
    execute: suspend () -> JSONObject,
    confirmed: (CloudResetPending) -> Unit,
    rejected: (CloudHttpException) -> Unit
) {
    stage()
    val result = try { execute() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        if (error is CloudHttpException && definitiveCloudResetRejection(error)) {
            rejected(error)
            throw error
        }
        return
    }
    if (pending.accepts(result)) confirmed(pending.withAccepted(result))
}

/** Returns the first observable decision. An old epoch never proves the POST was rejected. */
internal suspend fun pollCloudReset(
    pending: CloudResetPending,
    userId: String,
    active: () -> Boolean,
    elapsed: () -> Long,
    read: suspend () -> CloudBootstrap
): CloudBootstrap? {
    while (active()) {
        val started = elapsed()
        val observed = try { read() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        if (!active()) return null
        if (observed != null &&
            (pending.matches(userId, observed) || observed.libraryEpoch != pending.oldEpoch)) return observed
        kotlinx.coroutines.delay((5_000 - (elapsed() - started)).coerceAtLeast(1))
    }
    return null
}
