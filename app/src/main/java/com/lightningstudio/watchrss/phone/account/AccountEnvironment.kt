package com.lightningstudio.watchrss.phone.account

import com.lightningstudio.watchrss.phone.BuildConfig

data class AccountEnvironment(
    val backendBaseUrl: String = BuildConfig.WATCHRSS_BACKEND_BASE_URL.trimEnd('/'),
    val supabaseAnonKey: String = BuildConfig.WATCHRSS_SUPABASE_ANON_KEY,
    val posthogHost: String = BuildConfig.WATCHRSS_POSTHOG_HOST.trimEnd('/'),
    val posthogApiKey: String = BuildConfig.WATCHRSS_POSTHOG_API_KEY
) {
    val isAuthConfigured: Boolean
        get() = backendBaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    val isTelemetryConfigured: Boolean
        get() = posthogHost.isNotBlank() && posthogApiKey.isNotBlank()
}

