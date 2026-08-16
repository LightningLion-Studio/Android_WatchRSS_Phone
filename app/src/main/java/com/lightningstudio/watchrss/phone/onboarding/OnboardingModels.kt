package com.lightningstudio.watchrss.phone.onboarding

/**
 * 投入型引导漏斗的步骤与状态模型。
 *
 * answers 统一为 Map<String, List<String>>：多选存完整列表，单选/滑块/数字/文本存单元素列表，
 * 数字步骤的"一句话理由"存 `${id}_reason` 键。
 */
enum class StepType {
    WELCOME,
    CONSENT,
    CHIP_MULTI,
    CHIP_SINGLE,
    SLIDER,
    TEXT,
    NUMBER,
    PLAN_NAME,
    ANIMATION,
    IMPORT_URL,
    IMPORT_RESULT,
    FEATURE_PREVIEW,
    LOGIN_GUIDE,
    LOGIN_VIRTUAL,
    AUTH_INFO,
    VALUE_RECAP,
    PAYMENT_INTRO,
    COMPLETE
}

data class OnboardingStep(
    val id: String,
    val type: StepType,
    val title: String,
    val body: String,
    val detail: String? = null,
    /** 仅输入类步骤（TEXT/NUMBER/PLAN_NAME/IMPORT_URL）可为 true；选择类必答。 */
    val skippable: Boolean = false,
    val options: List<String> = emptyList(),
    val maxChars: Int? = null,
    val range: IntRange = 0..20,
    /** 答案在 OnboardingProfile 中的对应键。 */
    val echoKey: String? = null
) {
    val isInputStep: Boolean
        get() = type == StepType.TEXT || type == StepType.NUMBER ||
            type == StepType.PLAN_NAME || type == StepType.IMPORT_URL
}

data class OnboardingDraft(
    val stepIndex: Int = 0,
    val answers: Map<String, List<String>> = emptyMap(),
    val skipped: Set<String> = emptySet(),
    val importedArticleId: String? = null,
    val importedArticleTitle: String? = null,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class OnboardingProfile(
    val planName: String = "",
    val primaryScene: String = "",
    val watchOwnership: String = "",
    val preferredCategories: List<String> = emptyList(),
    val currentDailyCount: String = "",
    val platforms: List<String> = emptyList(),
    val favoriteWebsite: String = "",
    val unfinishedArticle: String = "",
    val dailyTarget: String = "",
    val whyReadMore: String = "",
    val commitmentDays: String = "",
    val importedArticleId: String? = null,
    val importedArticleTitle: String? = null,
    val answeredCount: Int = 0,
    val completedAtMillis: Long = 0L
)

enum class ProfileTier { FULL, PARTIAL, NONE }

data class PaywallCopy(
    val title: String,
    val detail: String,
    val actionLabel: String = "前往网页支付"
)

sealed interface ImportState {
    data object Idle : ImportState
    data object Loading : ImportState
    data class Success(val articleId: String, val title: String) : ImportState
    data class Failure(val message: String) : ImportState
}
