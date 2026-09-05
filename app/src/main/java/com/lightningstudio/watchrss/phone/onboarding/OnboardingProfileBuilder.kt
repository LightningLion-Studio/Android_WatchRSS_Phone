package com.lightningstudio.watchrss.phone.onboarding

import com.lightningstudio.watchrss.phone.account.AppAccessSummary

/**
 * 草稿 → 档案的净化与付费墙文案生成。全部为纯函数，JVM 可测。
 *
 * 三层文案策略只用于帮助用户回顾已填写内容，不制造必须购买、倒计时或损失压力。
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

    fun paywallCopyFor(
        @Suppress("UNUSED_PARAMETER") summary: AppAccessSummary,
        profile: OnboardingProfile?
    ): PaywallCopy {
        val price = priceSentence()
        return when (onboardingTier(profile)) {
            ProfileTier.FULL -> {
                val p = profile!!
                val scene = p.primaryScene.ifBlank { "你的碎片时间" }
                val target = p.dailyTarget.ifBlank { "若干" }
                val days = p.commitmentDays.ifBlank { "每一天" }
                val article = p.importedArticleTitle?.let { "第一篇文章《$it》已保存在资料库。" } ?: ""
                PaywallCopy(
                    title = "你的阅读计划已准备好",
                    detail = "你为「$scene」场景定制的阅读计划：每天 $target 篇，坚持 $days 天。$article$price"
                )
            }
            ProfileTier.PARTIAL -> PaywallCopy(
                title = "你的阅读计划已保存",
                detail = "已保存 ${profile!!.answeredCount} 个定制项。你可以先查看本地内容；如需手机与手表协同，再了解$price"
            )
            ProfileTier.NONE -> PaywallCopy(
                title = "了解手机版设备授权",
                detail = "${price}主要用于小说阅读、备忘录和手机与手表协同；与哔哩哔哩、抖音的会员或平台功能无关。"
            )
        }
    }

    private fun priceSentence(): String =
        "一次支付 ¥6 获取手机版设备授权包：账号增加 3 台手机容量，不自动续费。"

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
