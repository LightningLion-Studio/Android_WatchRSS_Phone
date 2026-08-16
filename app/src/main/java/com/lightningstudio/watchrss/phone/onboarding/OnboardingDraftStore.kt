package com.lightningstudio.watchrss.phone.onboarding

import android.content.Context

/**
 * 引导进行中的草稿：当前步、答案、跳过记录、已导入文章。进程死亡/配置变更后据此续跑。
 * 非机密数据，普通 SharedPreferences 即可；序列化逻辑在 [OnboardingCodecs]。
 */
class OnboardingDraftStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): OnboardingDraft? =
        preferences.getString(KEY_DRAFT, null)?.let { raw ->
            runCatching { OnboardingCodecs.draftFromJson(raw) }
                .getOrNull()
                .also { if (it == null) clear() }
        }

    fun save(draft: OnboardingDraft) {
        preferences.edit().putString(KEY_DRAFT, OnboardingCodecs.draftToJson(draft).toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_DRAFT).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "watchrss_onboarding_draft"
        private const val KEY_DRAFT = "draft"
    }
}
