package com.lightningstudio.watchrss.phone.account

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.os.SystemClock
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
                access = json.getJSONObject("access").toAccessSummary(),
                serverTimeMillis = json.optLong("serverTimeMillis")
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
            put("serverTimeMillis", value.serverTimeMillis)
        }.toString()).apply()
        if (value.serverTimeMillis > 0L) recordTrustedTime(value.serverTimeMillis)
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

    /**
     * Returns a wall-clock estimate anchored to a server response and elapsed realtime. A clock
     * rollback larger than the tolerance fails closed instead of extending an offline lease.
     */
    fun trustedNowMillis(): Long? {
        val anchor = prefs.getLong(KEY_TRUSTED_TIME, 0L)
        val anchorElapsed = prefs.getLong(KEY_TRUSTED_ELAPSED, -1L)
        val wall = System.currentTimeMillis()
        if (anchor <= 0L || anchorElapsed < 0L) return wall
        val elapsed = SystemClock.elapsedRealtime()
        return trustedTimeDecision(anchor, anchorElapsed, wall, elapsed)
    }

    fun recordTrustedTime(value: Long) {
        if (value <= 0L) return
        val hasAnchor = prefs.getLong(KEY_TRUSTED_TIME, 0L) > 0L
        val previous = if (hasAnchor) trustedNowMillis() ?: return else value
        prefs.edit()
            .putLong(KEY_TRUSTED_TIME, maxOf(value, previous))
            .putLong(KEY_TRUSTED_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    private companion object {
        const val KEY_AUTHORIZATION = "authorization"
        const val KEY_ORDER = "pending_order"
        const val KEY_RELEASE = "pending_release"
        const val KEY_CLAIM = "claim_idempotency"
        const val KEY_ORDER_CREATE = "order_idempotency"
        const val KEY_TRUSTED_TIME = "trusted_time"
        const val KEY_TRUSTED_ELAPSED = "trusted_elapsed"
        const val CLOCK_ROLLBACK_TOLERANCE_MILLIS = 5 * 60 * 1000L
    }
}

internal fun trustedTimeDecision(
    anchorMillis: Long,
    anchorElapsedMillis: Long,
    wallMillis: Long,
    elapsedMillis: Long,
    rollbackToleranceMillis: Long = 5 * 60 * 1000L
): Long? {
    val estimated = if (elapsedMillis >= anchorElapsedMillis) {
        anchorMillis + (elapsedMillis - anchorElapsedMillis)
    } else {
        anchorMillis
    }
    if (wallMillis + rollbackToleranceMillis < estimated) return null
    return maxOf(wallMillis, estimated)
}

internal fun JSONObject.toAccessSummary() = AppAccessSummary(
    product = optJSONObject("product")?.toAccessProduct() ?: AppAccessProductInfo(),
    purchaseCount = optInt("purchaseCount"), capacity = optInt("capacity"), occupied = optInt("occupied"),
    deviceStatus = optString("deviceStatus", "unknown"), revokeReason = optString("revokeReason").takeIf { it.isNotBlank() },
    accessMode = optString("accessMode", "none"),
    trialEligible = optBoolean("trialEligible", false),
    trialStartedAtMillis = optLong("trialStartedAt").takeIf { it > 0L },
    trialExpiresAtMillis = optLong("trialExpiresAt").takeIf { it > 0L }
)

internal fun AppAccessProductInfo.toJson() = JSONObject().apply {
    put("productId", productId)
    put("productName", productName)
    put("priceFen", priceFen)
    put("oneTime", oneTime)
    put("autoRenew", autoRenew)
    put("deviceCapacity", deviceCapacity)
    put("includedFeatures", org.json.JSONArray(includedFeatures))
    put("excludedFeatures", org.json.JSONArray(excludedFeatures))
}

internal fun AppAccessSummary.toJson() = JSONObject().apply {
    put("product", product.toJson())
    put("purchaseCount", purchaseCount); put("capacity", capacity); put("occupied", occupied)
    put("deviceStatus", deviceStatus); put("revokeReason", revokeReason)
    put("accessMode", accessMode); put("trialEligible", trialEligible)
    put("trialStartedAt", trialStartedAtMillis); put("trialExpiresAt", trialExpiresAtMillis)
}

internal fun JSONObject.toAccessProduct() = AppAccessProductInfo(
    productId = optString("productId", "watchrss_phone_device_authorization"),
    productName = optString("productName", "手机版设备授权包"),
    priceFen = optInt("priceFen", 600),
    oneTime = optBoolean("oneTime", true),
    autoRenew = optBoolean("autoRenew", false),
    deviceCapacity = optInt("deviceCapacity", 3),
    includedFeatures = optStringList("includedFeatures").ifEmpty { AppAccessProductInfo().includedFeatures },
    excludedFeatures = optStringList("excludedFeatures").ifEmpty { AppAccessProductInfo().excludedFeatures }
)

private fun JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

internal fun JSONObject.toPaymentOrder() = AppPaymentOrder(
    product = optJSONObject("product")?.toAccessProduct() ?: AppAccessProductInfo(),
    orderId = getString("orderId"), merchantOrderId = optString("merchantOrderId"), amountFen = optInt("amountFen"),
    status = optString("status"), paymentUrl = optString("paymentUrl").takeIf { it.isNotBlank() },
    paidAtMillis = optLong("paidAt").takeIf { it > 0L },
    refundedAtMillis = optLong("refundedAt").takeIf { it > 0L },
    refundEligibleUntilMillis = optLong("refundEligibleUntil").takeIf { it > 0L },
    refundable = optBoolean("refundable", false)
)

internal fun JSONObject.toPaymentOrderForUser(userId: String): AppPaymentOrder? =
    takeIf { optString("userId").isNotBlank() && optString("userId") == userId }
        ?.toPaymentOrder()
