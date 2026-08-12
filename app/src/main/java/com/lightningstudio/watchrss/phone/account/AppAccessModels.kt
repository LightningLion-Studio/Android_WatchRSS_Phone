package com.lightningstudio.watchrss.phone.account

data class AppAccessSummary(
    val purchaseCount: Int = 0,
    val capacity: Int = 0,
    val occupied: Int = 0,
    val deviceStatus: String = "unknown",
    val revokeReason: String? = null
)

data class AppAuthorization(
    val deviceAccessToken: String,
    val deviceAccessTokenExpiresAt: Long,
    val lease: String,
    val leaseExpiresAt: Long,
    val releaseGrant: String,
    val access: AppAccessSummary,
    val serverTimeMillis: Long = 0L
)

data class AppPaymentOrder(
    val orderId: String,
    val merchantOrderId: String,
    val amountFen: Int,
    val status: String,
    val paymentUrl: String?
)

sealed interface AppAccessState {
    data object Loading : AppAccessState
    data object LoggedOut : AppAccessState
    data class PurchaseRequired(val summary: AppAccessSummary) : AppAccessState
    data class PaymentPending(val order: AppPaymentOrder) : AppAccessState
    data class Authorized(val summary: AppAccessSummary, val offline: Boolean) : AppAccessState
    data class Revoked(val summary: AppAccessSummary) : AppAccessState
    data class ReauthenticationRequired(val summary: AppAccessSummary) : AppAccessState
    data class ValidationError(val message: String) : AppAccessState
}
