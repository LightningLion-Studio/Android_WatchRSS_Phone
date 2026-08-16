package com.lightningstudio.watchrss.phone.onboarding

import android.content.Context

/**
 * 引导完成后沉淀的用户档案（净化后的答案 + 已导入文章）。
 * 只在完成引导时写入一次、永不自动清除——MainActivity 付费墙回显与首页问候依赖它。
 * 内容仅存本机，绝不上传；遥测只上报计数器级事件。
 */
class OnboardingProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): OnboardingProfile? =
        preferences.getString(KEY_PROFILE, null)?.let { raw ->
            runCatching { OnboardingCodecs.profileFromJson(raw) }
                .getOrNull()
                .also { if (it == null) clear() }
        }

    fun save(profile: OnboardingProfile) {
        preferences.edit().putString(KEY_PROFILE, OnboardingCodecs.profileToJson(profile).toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_PROFILE).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "watchrss_onboarding_profile"
        private const val KEY_PROFILE = "profile"
    }
}
