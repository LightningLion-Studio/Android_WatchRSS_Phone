package com.lightningstudio.watchrss.phone.account

data class AppAccessProductInfo(
    val productId: String = "watchrss_phone_device_authorization",
    val productName: String = "手机版设备授权包",
    val priceFen: Int = 600,
    val oneTime: Boolean = true,
    val autoRenew: Boolean = false,
    val deviceCapacity: Int = 3,
    val includedFeatures: List<String> = listOf(
        "手机端小说与本地资料阅读",
        "手机端备忘录",
        "手机与手表协同同步阅读资料和状态",
        "账号增加3台手机授权容量"
    ),
    val excludedFeatures: List<String> = listOf(
        "哔哩哔哩或抖音会员及平台权益",
        "腕上RSS当前未提供的其他平台社区功能",
        "网络速度、清晰度、码率、CDN或加载优先级提升",
        "WatchRSS云会员、云空间及其他独立云服务"
    )
)

data class AppAccessSummary(
    val product: AppAccessProductInfo = AppAccessProductInfo(),
    val purchaseCount: Int = 0,
    val capacity: Int = 0,
    val occupied: Int = 0,
    val deviceStatus: String = "unknown",
    val revokeReason: String? = null,
    val accessMode: String = "none",
    val trialEligible: Boolean = false,
    val trialStartedAtMillis: Long? = null,
    val trialExpiresAtMillis: Long? = null
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
    val product: AppAccessProductInfo = AppAccessProductInfo(),
    val orderId: String,
    val merchantOrderId: String,
    val amountFen: Int,
    val status: String,
    val paymentUrl: String?,
    val paidAtMillis: Long? = null,
    val refundedAtMillis: Long? = null,
    val refundEligibleUntilMillis: Long? = null,
    val refundable: Boolean = false
)

data class AccountDeletionResult(
    val storageCleanupPending: Boolean,
    val retainedMerchantOrderIds: List<String>
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
