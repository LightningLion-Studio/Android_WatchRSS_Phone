package com.lightningstudio.watchrss.phone.account

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

class AppAccessStore(context: Context, suffix: String = "") {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "watchrss_app_access$suffix",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): AppAuthorization? = prefs.getString(KEY_AUTHORIZATION, null)?.let { raw ->
        runCatching {
            val json = JSONObject(raw)
            AppAuthorization(
                deviceAccessToken = json.getString("deviceAccessToken"),
                deviceAccessTokenExpiresAt = json.getLong("deviceAccessTokenExpiresAt"),
                lease = json.getString("lease"),
                leaseExpiresAt = json.getLong("leaseExpiresAt"),
                releaseGrant = json.optString("releaseGrant"),
                access = json.getJSONObject("access").toAccessSummary()
            )
        }.getOrNull()
    }

    fun save(value: AppAuthorization) {
        prefs.edit().putString(KEY_AUTHORIZATION, JSONObject().apply {
            put("deviceAccessToken", value.deviceAccessToken)
            put("deviceAccessTokenExpiresAt", value.deviceAccessTokenExpiresAt)
            put("lease", value.lease)
            put("leaseExpiresAt", value.leaseExpiresAt)
            put("releaseGrant", value.releaseGrant)
            put("access", value.access.toJson())
        }.toString()).apply()
    }

    fun clear() { prefs.edit().remove(KEY_AUTHORIZATION).apply() }

    fun queueRelease(deviceId: String, releaseGrant: String) {
        prefs.edit().putString(KEY_RELEASE, JSONObject().apply {
            put("licenseDeviceId", deviceId); put("releaseGrant", releaseGrant)
        }.toString()).apply()
    }

    fun pendingRelease(): Pair<String, String>? = prefs.getString(KEY_RELEASE, null)?.let { raw ->
        runCatching { JSONObject(raw).let { it.getString("licenseDeviceId") to it.getString("releaseGrant") } }.getOrNull()
    }

    fun clearPendingRelease() { prefs.edit().remove(KEY_RELEASE).apply() }

    fun claimIdempotencyKey(): String {
        prefs.getString(KEY_CLAIM, null)?.let { return it }
        return java.util.UUID.randomUUID().toString().also { prefs.edit().putString(KEY_CLAIM, it).apply() }
    }

    fun clearClaimIdempotencyKey() { prefs.edit().remove(KEY_CLAIM).apply() }

    fun orderIdempotencyKey(): String {
        prefs.getString(KEY_ORDER_CREATE, null)?.let { return it }
        return java.util.UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ORDER_CREATE, it).apply() }
    }

    fun clearOrderIdempotencyKey() { prefs.edit().remove(KEY_ORDER_CREATE).apply() }

    fun savePendingOrder(order: AppPaymentOrder, userId: String) {
        require(userId.isNotBlank()) { "待支付订单必须绑定账号" }
        prefs.edit().putString(KEY_ORDER, JSONObject().apply {
            put("userId", userId)
            put("orderId", order.orderId); put("merchantOrderId", order.merchantOrderId)
            put("amountFen", order.amountFen); put("status", order.status); put("paymentUrl", order.paymentUrl)
        }.toString()).apply()
    }

    fun loadPendingOrder(userId: String): AppPaymentOrder? = prefs.getString(KEY_ORDER, null)?.let { raw ->
        runCatching { JSONObject(raw).toPaymentOrderForUser(userId) }.getOrNull().also { order ->
            if (order == null) clearPendingOrder()
        }
    }

    fun clearPendingOrder() { prefs.edit().remove(KEY_ORDER).apply() }

    private companion object { const val KEY_AUTHORIZATION = "authorization"; const val KEY_ORDER = "pending_order"; const val KEY_RELEASE = "pending_release"; const val KEY_CLAIM = "claim_idempotency"; const val KEY_ORDER_CREATE = "order_idempotency" }
}

internal fun JSONObject.toAccessSummary() = AppAccessSummary(
    purchaseCount = optInt("purchaseCount"), capacity = optInt("capacity"), occupied = optInt("occupied"),
    deviceStatus = optString("deviceStatus", "unknown"), revokeReason = optString("revokeReason").takeIf { it.isNotBlank() }
)

internal fun AppAccessSummary.toJson() = JSONObject().apply {
    put("purchaseCount", purchaseCount); put("capacity", capacity); put("occupied", occupied)
    put("deviceStatus", deviceStatus); put("revokeReason", revokeReason)
}

internal fun JSONObject.toPaymentOrder() = AppPaymentOrder(
    orderId = getString("orderId"), merchantOrderId = optString("merchantOrderId"), amountFen = optInt("amountFen"),
    status = optString("status"), paymentUrl = optString("paymentUrl").takeIf { it.isNotBlank() }
)

internal fun JSONObject.toPaymentOrderForUser(userId: String): AppPaymentOrder? =
    takeIf { optString("userId").isNotBlank() && optString("userId") == userId }
        ?.toPaymentOrder()
