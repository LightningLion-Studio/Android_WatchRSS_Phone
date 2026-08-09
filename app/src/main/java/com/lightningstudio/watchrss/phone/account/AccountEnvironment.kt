package com.lightningstudio.watchrss.phone.account

import android.content.Context
import com.lightningstudio.watchrss.phone.BuildConfig

data class AccountEnvironment(
    val remoteEnvironment: RemoteEnvironment = RemoteEnvironment.PRODUCTION,
    val backendBaseUrl: String = BuildConfig.WATCHRSS_PRODUCTION_BACKEND_BASE_URL.trimEnd('/'),
    val supabaseAnonKey: String = BuildConfig.WATCHRSS_PRODUCTION_SUPABASE_ANON_KEY,
    val appAccessPublicKey: String = BuildConfig.WATCHRSS_APP_ACCESS_PUBLIC_KEY,
    val posthogHost: String = BuildConfig.WATCHRSS_POSTHOG_HOST.trimEnd('/'),
    val posthogApiKey: String = BuildConfig.WATCHRSS_POSTHOG_API_KEY
) {
    val storageSuffix: String
        get() = remoteEnvironment.storageSuffix

    val isAuthConfigured: Boolean
        get() = backendBaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    val isTelemetryConfigured: Boolean
        get() = posthogHost.isNotBlank() && posthogApiKey.isNotBlank()

    companion object {
        fun active(context: Context): AccountEnvironment =
            forRemoteEnvironment(RemoteEnvironmentStore(context).active())

        fun forRemoteEnvironment(remoteEnvironment: RemoteEnvironment): AccountEnvironment =
            when (remoteEnvironment) {
                RemoteEnvironment.PRODUCTION -> AccountEnvironment()
                RemoteEnvironment.TEST -> AccountEnvironment(
                    remoteEnvironment = RemoteEnvironment.TEST,
                    backendBaseUrl = testBackendBaseUrl(
                        BuildConfig.WATCHRSS_PRODUCTION_BACKEND_BASE_URL
                    ),
                    supabaseAnonKey = BuildConfig.WATCHRSS_TEST_SUPABASE_ANON_KEY,
                    appAccessPublicKey = BuildConfig.WATCHRSS_TEST_APP_ACCESS_PUBLIC_KEY
                )
            }
    }
}

internal fun testBackendBaseUrl(productionBackendBaseUrl: String): String =
    productionBackendBaseUrl
        .trim()
        .trimEnd('/')
        .takeIf(String::isNotBlank)
        ?.plus("/test")
        .orEmpty()

enum class RemoteEnvironment(
    val persistedValue: String,
    val displayName: String,
    internal val storageSuffix: String
) {
    PRODUCTION("production", "生产环境", ""),
    TEST("test", "测试环境", "_test");

    companion object {
        fun fromPersistedValue(value: String?): RemoteEnvironment? =
            entries.firstOrNull { it.persistedValue == value }
    }
}

class RemoteEnvironmentStore(
    context: Context,
    private val isDebugBuild: Boolean = BuildConfig.DEBUG
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun active(): RemoteEnvironment = resolveRemoteEnvironment(
        isDebugBuild = isDebugBuild,
        persistedValue = preferences.getString(KEY_ACTIVE_ENVIRONMENT, null)
    )

    fun select(remoteEnvironment: RemoteEnvironment): Boolean {
        val resolved = if (isDebugBuild) remoteEnvironment else RemoteEnvironment.PRODUCTION
        if (active() == resolved) return false
        check(
            preferences.edit()
                .putString(KEY_ACTIVE_ENVIRONMENT, resolved.persistedValue)
                .commit()
        ) { "远端环境设置保存失败" }
        return true
    }

    private companion object {
        private const val PREFS_NAME = "watchrss_remote_environment"
        private const val KEY_ACTIVE_ENVIRONMENT = "active_environment"
    }
}

internal fun resolveRemoteEnvironment(
    isDebugBuild: Boolean,
    persistedValue: String?
): RemoteEnvironment {
    if (!isDebugBuild) return RemoteEnvironment.PRODUCTION
    return RemoteEnvironment.fromPersistedValue(persistedValue)
        ?: RemoteEnvironment.PRODUCTION
}
