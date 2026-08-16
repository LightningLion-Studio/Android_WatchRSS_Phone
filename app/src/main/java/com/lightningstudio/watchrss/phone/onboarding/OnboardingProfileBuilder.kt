package com.lightningstudio.watchrss.phone.onboarding

import com.lightningstudio.watchrss.phone.account.AppAccessSummary

/**
 * 草稿 → 档案的净化与付费墙文案生成。全部为纯函数，JVM 可测。
 *
 * 三层文案策略：
 * - FULL：有档案、计划名非空、answeredCount >= 6 —— 回显用户亲手写下的目标与文章（一致性压力）
 * - PARTIAL：answeredCount 1-5 —— "还差一点定制"（未完成感）
 * - NONE：无档案或全跳过 —— "未定制的阅读计划将无法在手机上继续"（损失框架）
 */
object OnboardingProfileBuilder {

    private const val MULTI_SELECT_CAP = 8

    fun buildProfile(
        draft: OnboardingDraft,
        completedAtMillis: Long = System.currentTimeMillis()
    ): OnboardingProfile {
        val answers = draft.answers
        return OnboardingProfile(
            planName = sanitizedFirst(answers, "plan_name"),
            primaryScene = sanitizedFirst(answers, "scene"),
            watchOwnership = sanitizedFirst(answers, "watch_ownership"),
            preferredCategories = sanitizedList(answers, "categories"),
            currentDailyCount = sanitizedNumber(answers, "current_daily"),
            platforms = sanitizedList(answers, "platforms"),
            favoriteWebsite = sanitizedFirst(answers, "favorite_website"),
            unfinishedArticle = sanitizedFirst(answers, "unfinished_article"),
            dailyTarget = sanitizedNumber(answers, "daily_target"),
            whyReadMore = sanitizedFirst(answers, "why_read_more"),
            commitmentDays = sanitizedNumber(answers, "commitment_days"),
            importedArticleId = draft.importedArticleId,
            importedArticleTitle = draft.importedArticleTitle?.trim()?.takeIf { it.isNotBlank() },
            answeredCount = answeredCount(draft),
            completedAtMillis = completedAtMillis
        )
    }

    /** 非空答案的 echoKey 数 + 已导入文章（1）。 */
    fun answeredCount(draft: OnboardingDraft): Int {
        val answeredKeys = ONBOARDING_CATALOG
            .mapNotNull { it.echoKey }
            .count { key -> draft.answers[key].orEmpty().any { it.isNotBlank() } }
        return answeredKeys + if (draft.importedArticleId != null) 1 else 0
    }

    fun onboardingTier(profile: OnboardingProfile?): ProfileTier = when {
        profile == null || profile.answeredCount <= 0 -> ProfileTier.NONE
        profile.planName.isNotBlank() && profile.answeredCount >= 6 -> ProfileTier.FULL
        else -> ProfileTier.PARTIAL
    }

    fun paywallCopyFor(summary: AppAccessSummary, profile: OnboardingProfile?): PaywallCopy {
        val price = priceSentence(summary)
        return when (onboardingTier(profile)) {
            ProfileTier.FULL -> {
                val p = profile!!
                val scene = p.primaryScene.ifBlank { "你的碎片时间" }
                val target = p.dailyTarget.ifBlank { "若干" }
                val days = p.commitmentDays.ifBlank { "每一天" }
                val article = p.importedArticleTitle?.let { "第一篇文章《$it》已保存在资料库。" } ?: ""
                PaywallCopy(
                    title = "《${p.planName}》就差最后一步",
                    detail = "你为「$scene」场景定制的阅读计划已经就绪：每天 $target 篇，坚持 $days 天。$article${price}在手机与手表上继续。"
                )
            }
            ProfileTier.PARTIAL -> PaywallCopy(
                title = "你的阅读计划还差一点定制",
                detail = "${profile!!.answeredCount} 个定制项已保存，补齐后可在手机与手表上继续阅读。${price}继续。"
            )
            ProfileTier.NONE -> PaywallCopy(
                title = "当前账号没有可授权额度",
                detail = "未定制的阅读计划将无法在手机上继续。${price}定制并继续。"
            )
        }
    }

    private fun priceSentence(summary: AppAccessSummary): String {
        val remaining = (summary.capacity - summary.occupied).coerceAtLeast(0)
        return "购买手机版永久授权（¥6 可授权 3 台手机；当前已购买 ${summary.purchaseCount} 次，" +
            "剩余 $remaining 台）后即可"
    }

    private fun sanitizedFirst(answers: Map<String, List<String>>, key: String): String =
        sanitizedList(answers, key).firstOrNull().orEmpty()

    private fun sanitizedList(answers: Map<String, List<String>>, key: String): List<String> {
        val step = ONBOARDING_CATALOG.firstOrNull { it.echoKey == key } ?: return emptyList()
        val cap = step.maxChars
        return answers[key].orEmpty()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (cap != null) it.take(cap) else it }
            .take(MULTI_SELECT_CAP)
            .toList()
    }

    private fun sanitizedNumber(answers: Map<String, List<String>>, key: String): String {
        val step = ONBOARDING_CATALOG.firstOrNull { it.echoKey == key } ?: return ""
        val value = sanitizedFirst(answers, key)
        if (value.isBlank()) return ""
        val parsed = value.toIntOrNull() ?: return ""
        return parsed.coerceIn(step.range).toString()
    }
}
