package com.lightningstudio.watchrss.phone.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Launches the OPPO software store detail page for this app per the self-update
 * client protocol: `market://details?id=<pkg>&caller=<pkg>&v_code=<code>&m=<module>`.
 * The docs require `startActivityForResult` with a requestCode > 0; atd is omitted
 * so the store uses its default (auto-download where granted, manual otherwise).
 */
object OppoMarketLauncher {
    private const val TAG = "WatchRSS_MarketLaunch"
    private const val REQUEST_CODE = 1001 // must be > 0 per the protocol doc
    private const val MARKET_PACKAGE_HEYTAP = "com.heytap.market"
    private const val MARKET_PACKAGE_OPPO = "com.oppo.market"

    fun launchUpdate(activity: Activity, versionCode: Int): Boolean {
        val marketPackage = marketPackage(activity) ?: return false
        val url = "market://details?id=${activity.packageName}" +
            "&caller=${activity.packageName}&v_code=$versionCode&m=app_update"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(marketPackage)
        }
        if (intent.resolveActivity(activity.packageManager) == null) return false
        return runCatching {
            activity.startActivityForResult(intent, REQUEST_CODE)
            Log.i(TAG, "store detail page launched via $marketPackage for v_code=$versionCode")
            true
        }.onFailure { Log.w(TAG, "failed to launch store detail page", it) }
            .getOrDefault(false)
    }

    private fun marketPackage(context: Context): String? {
        for (candidate in listOf(MARKET_PACKAGE_HEYTAP, MARKET_PACKAGE_OPPO)) {
            val info = runCatching {
                context.packageManager.getPackageInfo(candidate, 0)
            }.getOrNull() ?: continue
            if (info.applicationInfo?.enabled == true) return candidate
        }
        return null
    }
}
