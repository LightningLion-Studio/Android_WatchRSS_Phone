package com.lightningstudio.watchrss.phone.onboarding

import org.json.JSONArray
import org.json.JSONObject

/**
 * 草稿/档案的纯 JSON codec，不依赖 Android，可在 JVM 单测中直接验证。
 * SharedPreferences 包装（OnboardingDraftStore/OnboardingProfileStore）只做存取。
 */
object OnboardingCodecs {
    private const val SCHEMA_VERSION = 1

    // ── Draft ─────────────────────────────────────────────────────

    fun draftToJson(draft: OnboardingDraft): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("stepIndex", draft.stepIndex)
        put("answers", JSONObject().apply {
            draft.answers.forEach { (key, values) ->
                put(key, JSONArray().apply { values.forEach(::put) })
            }
        })
        put("skipped", JSONArray().apply { draft.skipped.forEach(::put) })
        draft.importedArticleId?.let { put("importedArticleId", it) }
        draft.importedArticleTitle?.let { put("importedArticleTitle", it) }
        put("startedAtMillis", draft.startedAtMillis)
        put("updatedAtMillis", draft.updatedAtMillis)
    }

    fun draftFromJson(raw: String): OnboardingDraft? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schemaVersion") != SCHEMA_VERSION) return@runCatching null
        val answers = json.optJSONObject("answers")?.let { answersJson ->
            buildMap {
                answersJson.keys().forEach { key ->
                    val values = answersJson.optJSONArray(key).toStringList()
                    if (values.isNotEmpty()) put(key, values)
                }
            }
        } ?: emptyMap()
        OnboardingDraft(
            stepIndex = json.optInt("stepIndex").coerceIn(0, ONBOARDING_CATALOG.size - 1),
            answers = answers,
            skipped = json.optJSONArray("skipped").toStringList().toSet(),
            importedArticleId = json.optString("importedArticleId").takeIf { it.isNotBlank() },
            importedArticleTitle = json.optString("importedArticleTitle").takeIf { it.isNotBlank() },
            startedAtMillis = json.optLong("startedAtMillis"),
            updatedAtMillis = json.optLong("updatedAtMillis")
        )
    }.getOrNull()

    // ── Profile ───────────────────────────────────────────────────

    fun profileToJson(profile: OnboardingProfile): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("planName", profile.planName)
        put("primaryScene", profile.primaryScene)
        put("watchOwnership", profile.watchOwnership)
        put("preferredCategories", profile.preferredCategories.toJsonArray())
        put("currentDailyCount", profile.currentDailyCount)
        put("platforms", profile.platforms.toJsonArray())
        put("favoriteWebsite", profile.favoriteWebsite)
        put("unfinishedArticle", profile.unfinishedArticle)
        put("dailyTarget", profile.dailyTarget)
        put("whyReadMore", profile.whyReadMore)
        put("commitmentDays", profile.commitmentDays)
        profile.importedArticleId?.let { put("importedArticleId", it) }
        profile.importedArticleTitle?.let { put("importedArticleTitle", it) }
        put("answeredCount", profile.answeredCount)
        put("completedAtMillis", profile.completedAtMillis)
    }

    fun profileFromJson(raw: String): OnboardingProfile? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schemaVersion") != SCHEMA_VERSION) return@runCatching null
        OnboardingProfile(
            planName = json.optString("planName"),
            primaryScene = json.optString("primaryScene"),
            watchOwnership = json.optString("watchOwnership"),
            preferredCategories = json.optJSONArray("preferredCategories").toStringList(),
            currentDailyCount = json.optString("currentDailyCount"),
            platforms = json.optJSONArray("platforms").toStringList(),
            favoriteWebsite = json.optString("favoriteWebsite"),
            unfinishedArticle = json.optString("unfinishedArticle"),
            dailyTarget = json.optString("dailyTarget"),
            whyReadMore = json.optString("whyReadMore"),
            commitmentDays = json.optString("commitmentDays"),
            importedArticleId = json.optString("importedArticleId").takeIf { it.isNotBlank() },
            importedArticleTitle = json.optString("importedArticleTitle").takeIf { it.isNotBlank() },
            answeredCount = json.optInt("answeredCount"),
            completedAtMillis = json.optLong("completedAtMillis")
        )
    }.getOrNull()

    private fun List<String>.toJsonArray(): JSONArray = JSONArray().apply { forEach(::put) }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
