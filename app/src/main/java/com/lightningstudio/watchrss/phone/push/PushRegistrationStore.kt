package com.lightningstudio.watchrss.phone.push

import android.content.Context

/** Stores push-notification preferences and OPPO registration state. */
class PushRegistrationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    var regId: String?
        get() = preferences.getString(KEY_REG_ID, null)
        set(value) {
            preferences.edit().putString(KEY_REG_ID, value).apply()
        }

    /** 0 = registered; -1 = never attempted; OPPO error codes otherwise (16 = signature mismatch). */
    var lastRegisterCode: Int
        get() = preferences.getInt(KEY_LAST_CODE, -1)
        set(value) {
            preferences.edit().putInt(KEY_LAST_CODE, value).apply()
        }

    companion object {
        const val PREFERENCES_NAME = "phone_push_registration"
        const val KEY_ENABLED = "push_notifications"
        private const val KEY_REG_ID = "register_id"
        private const val KEY_LAST_CODE = "last_register_code"
    }
}
