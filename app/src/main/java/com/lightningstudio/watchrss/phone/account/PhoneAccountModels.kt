package com.lightningstudio.watchrss.phone.account

data class PhoneAccountSession(
    val userId: String,
    val phoneMasked: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val activationProof: String = "",
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

data class RegisteredPasskey(
    val credentialId: String,
    val displayName: String,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long?
)

data class TotpFactor(
    val id: String,
    val friendlyName: String,
    val verified: Boolean
)

data class TotpEnrollment(
    val factorId: String,
    val secret: String,
    val uri: String
)

data class LoginProgress(
    val transactionId: String,
    val requiredFactorCount: Int,
    val completedFactors: List<String>,
    val complete: Boolean,
    val session: PhoneAccountSession?,
    val verificationToken: String? = null
)

data class AccountSecurityStatus(
    val twoFactorEnabled: Boolean,
    val availableMethods: Set<String>
) {
    val availableMethodCount: Int get() = availableMethods.size
}
