package com.lightningstudio.watchrss.phone.privacy

import android.content.Context

class PhonePrivacyConsentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun hasRequiredConsent(): Boolean =
        preferences.getInt(KEY_POLICY_VERSION, 0) >= CURRENT_POLICY_VERSION

    fun isOobeComplete(): Boolean =
        hasRequiredConsent() &&
            preferences.getInt(KEY_OOBE_VERSION, 0) >= CURRENT_OOBE_VERSION

    fun acceptRequiredPolicies(acceptedAtMillis: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putInt(KEY_POLICY_VERSION, CURRENT_POLICY_VERSION)
            .putLong(KEY_ACCEPTED_AT_MILLIS, acceptedAtMillis)
            .apply()
    }

    fun completeOobe() {
        check(hasRequiredConsent()) { "完成首次使用引导前必须同意用户协议与隐私政策" }
        preferences.edit()
            .putInt(KEY_OOBE_VERSION, CURRENT_OOBE_VERSION)
            .apply()
    }

    companion object {
        const val PREFERENCES_NAME = "watchrss_phone_privacy_consent"
        const val BACKUP_FILE_NAME = "$PREFERENCES_NAME.xml"
        const val CURRENT_POLICY_VERSION = 1
        const val CURRENT_OOBE_VERSION = 1

        private const val KEY_POLICY_VERSION = "policy_version"
        private const val KEY_OOBE_VERSION = "oobe_version"
        private const val KEY_ACCEPTED_AT_MILLIS = "accepted_at_millis"
    }
}

internal enum class PhoneOobeStage {
    WELCOME,
    AGREEMENT,
    ACCOUNT,
    COMPLETE
}

internal fun initialPhoneOobeConsentState(
    hasRequiredConsent: Boolean,
    replayFromStart: Boolean
): Boolean = hasRequiredConsent && !replayFromStart

internal fun phoneOobeStage(
    page: Int,
    hasConsent: Boolean,
    hasUsableSession: Boolean
): PhoneOobeStage = when {
    hasConsent && hasUsableSession -> PhoneOobeStage.COMPLETE
    hasConsent -> PhoneOobeStage.ACCOUNT
    page <= 0 -> PhoneOobeStage.WELCOME
    else -> PhoneOobeStage.AGREEMENT
}

internal fun shouldEnforceAppAccess(
    hasRequiredConsent: Boolean,
    isOobeComplete: Boolean
): Boolean = hasRequiredConsent && isOobeComplete
