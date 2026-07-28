package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import org.json.JSONObject

enum class CloudNetworkPolicy {
    WIFI_BODIES_ANY_STATE,
    WIFI_AND_CHARGING,
    ANY_NETWORK
}

class PhoneCloudStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "watchrss_cloud_state",
        Context.MODE_PRIVATE
    )

    var retentionDays: Int?
        get() = preferences.getInt(KEY_RETENTION_DAYS, 30).takeIf { it > 0 }
        set(value) {
            require(value == null || value in setOf(7, 30, 90))
            preferences.edit().putInt(KEY_RETENTION_DAYS, value ?: 0).apply()
        }

    var networkPolicy: CloudNetworkPolicy
        get() = runCatching {
            CloudNetworkPolicy.valueOf(
                preferences.getString(KEY_NETWORK_POLICY, null)
                    ?: CloudNetworkPolicy.WIFI_BODIES_ANY_STATE.name
            )
        }.getOrDefault(CloudNetworkPolicy.WIFI_BODIES_ANY_STATE)
        set(value) {
            preferences.edit().putString(KEY_NETWORK_POLICY, value.name).apply()
        }

    @Synchronized
    fun nextSequence(serverSequence: Long): Long {
        val next = maxOf(preferences.getLong(KEY_DEVICE_SEQUENCE, 0L), serverSequence) + 1L
        check(preferences.edit().putLong(KEY_DEVICE_SEQUENCE, next).commit())
        return next
    }

    fun appliedSequence(deviceId: String, full: Boolean): Long =
        appliedHeads(full).optLong(deviceId)

    fun markApplied(deviceId: String, sequence: Long, full: Boolean) {
        val json = appliedHeads(full)
        json.put(deviceId, maxOf(json.optLong(deviceId), sequence))
        preferences.edit().putString(appliedKey(full), json.toString()).apply()
    }

    fun lastContentHash(full: Boolean): String? =
        preferences.getString(
            contentHashKey(full),
            preferences.getString(KEY_CONTENT_HASH, null)
        )

    fun lastParentHeads(): String? = preferences.getString(KEY_PARENT_HEADS, null)

    fun markUploaded(
        contentHash: String,
        parentHeads: Map<String, String>,
        full: Boolean
    ) {
        preferences.edit()
            .putString(contentHashKey(full), contentHash)
            .putString(KEY_PARENT_HEADS, JSONObject(parentHeads).toString())
            .apply()
    }

    private fun appliedHeads(full: Boolean): JSONObject =
        runCatching {
            JSONObject(preferences.getString(appliedKey(full), "{}").orEmpty())
        }.getOrDefault(JSONObject())

    private fun appliedKey(full: Boolean): String =
        if (full) KEY_APPLIED_FULL else KEY_APPLIED_STATE

    private fun contentHashKey(full: Boolean): String =
        if (full) KEY_CONTENT_HASH_FULL else KEY_CONTENT_HASH_STATE

    private companion object {
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_NETWORK_POLICY = "network_policy"
        private const val KEY_DEVICE_SEQUENCE = "device_sequence"
        private const val KEY_APPLIED_FULL = "applied_full_heads"
        private const val KEY_APPLIED_STATE = "applied_state_heads"
        private const val KEY_CONTENT_HASH = "last_content_hash"
        private const val KEY_CONTENT_HASH_FULL = "last_content_hash_full"
        private const val KEY_CONTENT_HASH_STATE = "last_content_hash_state"
        private const val KEY_PARENT_HEADS = "last_parent_heads"
    }
}
