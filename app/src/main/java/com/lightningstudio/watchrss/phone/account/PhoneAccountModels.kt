package com.lightningstudio.watchrss.phone.account

data class PhoneAccountSession(
    val userId: String,
    val phoneMasked: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val isExpired: Boolean
        get() = expiresAtMillis > 0L && expiresAtMillis <= System.currentTimeMillis()
}

data class WatchEntitlementSnapshot(
    val plan: String = "free",
    val active: Boolean = true,
    val expiresAtMillis: Long = 0L,
    val features: List<String> = emptyList()
)

data class WatchDeviceToken(
    val accessToken: String,
    val accessTokenExpiresAtMillis: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtMillis: Long,
    val entitlement: WatchEntitlementSnapshot
) {
    val token: String get() = accessToken
    val expiresAtMillis: Long get() = accessTokenExpiresAtMillis
}

data class PasskeyOptions(
    val challengeId: String,
    val requestJson: String
)
