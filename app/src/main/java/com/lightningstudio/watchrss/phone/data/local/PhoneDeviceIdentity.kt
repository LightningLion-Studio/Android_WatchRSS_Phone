package com.lightningstudio.watchrss.phone.data.local

import android.content.Context
import java.util.UUID

class PhoneDeviceIdentity(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "phone_device_identity",
        Context.MODE_PRIVATE
    )

    val deviceId: String
        get() {
            val existing = preferences.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            preferences.edit().putString(KEY_DEVICE_ID, created).apply()
            return created
        }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}
