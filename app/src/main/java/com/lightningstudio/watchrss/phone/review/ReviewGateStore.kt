package com.lightningstudio.watchrss.phone.review

import android.content.Context

/** SharedPreferences-backed gate state for the in-app review prompts. */
class ReviewGateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var cumulativeForegroundMillis: Long
        get() = preferences.getLong(KEY_CUMULATIVE_FOREGROUND, 0L)
        set(value) {
            preferences.edit().putLong(KEY_CUMULATIVE_FOREGROUND, value).apply()
        }

    // Moment 1: after a successful Bluetooth library sync.
    var syncPromptShownVersion: String?
        get() = preferences.getString(KEY_SYNC_SHOWN, null)
        set(value) {
            preferences.edit().putString(KEY_SYNC_SHOWN, value).apply()
        }

    var syncPromptDeclinedVersion: String?
        get() = preferences.getString(KEY_SYNC_DECLINED, null)
        set(value) {
            preferences.edit().putString(KEY_SYNC_DECLINED, value).apply()
        }

    // Moment 2: cumulative foreground minutes threshold on app entry.
    var minutesPromptShownVersion: String?
        get() = preferences.getString(KEY_MINUTES_SHOWN, null)
        set(value) {
            preferences.edit().putString(KEY_MINUTES_SHOWN, value).apply()
        }

    var minutesPromptDeclinedVersion: String?
        get() = preferences.getString(KEY_MINUTES_DECLINED, null)
        set(value) {
            preferences.edit().putString(KEY_MINUTES_DECLINED, value).apply()
        }

    companion object {
        const val PREFERENCES_NAME = "phone_review_gate"
        private const val KEY_CUMULATIVE_FOREGROUND = "cumulative_foreground_millis"
        private const val KEY_SYNC_SHOWN = "sync_prompt_shown_version"
        private const val KEY_SYNC_DECLINED = "sync_prompt_declined_version"
        private const val KEY_MINUTES_SHOWN = "minutes_prompt_shown_version"
        private const val KEY_MINUTES_DECLINED = "minutes_prompt_declined_version"
    }
}
