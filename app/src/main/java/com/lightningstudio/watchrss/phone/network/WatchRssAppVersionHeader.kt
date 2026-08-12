package com.lightningstudio.watchrss.phone.network

import com.lightningstudio.watchrss.phone.BuildConfig
import okhttp3.Request

internal const val WATCHRSS_APP_VERSION_HEADER = "X-WatchRSS-App-Version"

internal fun watchRssAppVersionHeaderValue(
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE
): String = "phone-${versionName.trim()}+$versionCode"

internal fun Request.Builder.withWatchRssAppVersionHeader(): Request.Builder =
    header(WATCHRSS_APP_VERSION_HEADER, watchRssAppVersionHeaderValue())
