package com.lightningstudio.watchrss.phone.review

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.lightningstudio.watchrss.phone.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow

enum class ReviewMoment { AFTER_SYNC, MINUTES_THRESHOLD }

/**
 * OPPO in-app review (评论调起) gate and launcher. Prompts at two moments:
 * after a successful Bluetooth library sync, and on app entry once cumulative
 * foreground usage crosses [MINUTES_THRESHOLD_MS]. Each moment fires at most once
 * per app version; a "下次再说" dismissal suppresses it for the rest of the version.
 * The store comment page is only launched after the in-app confirmation dialog.
 */
class OppoReviewCoordinator(
    private val context: Context,
    private val store: ReviewGateStore = ReviewGateStore(context)
) {
    /** Consumed by HomeActivity; persists until the UI shows or dismisses the dialog. */
    val pendingPrompt = MutableStateFlow<ReviewMoment?>(null)

    fun recordForeground(elapsedMillis: Long) {
        if (elapsedMillis <= 0) return
        store.cumulativeForegroundMillis += elapsedMillis
    }

    fun onSyncSucceeded() {
        maybePrompt(ReviewMoment.AFTER_SYNC)
    }

    fun onAppEntry() {
        maybePrompt(ReviewMoment.MINUTES_THRESHOLD)
    }

    private fun maybePrompt(moment: ReviewMoment) {
        if (!isMarketAvailable()) return
        val version = BuildConfig.VERSION_NAME
        val (shown, declined) = when (moment) {
            ReviewMoment.AFTER_SYNC ->
                store.syncPromptShownVersion to store.syncPromptDeclinedVersion
            ReviewMoment.MINUTES_THRESHOLD ->
                store.minutesPromptShownVersion to store.minutesPromptDeclinedVersion
        }
        if (shown == version || declined == version) return
        if (moment == ReviewMoment.MINUTES_THRESHOLD &&
            store.cumulativeForegroundMillis < MINUTES_THRESHOLD_MS
        ) return
        pendingPrompt.value = moment
    }

    fun onPromptShown(moment: ReviewMoment) {
        val version = BuildConfig.VERSION_NAME
        when (moment) {
            ReviewMoment.AFTER_SYNC -> store.syncPromptShownVersion = version
            ReviewMoment.MINUTES_THRESHOLD -> store.minutesPromptShownVersion = version
        }
        pendingPrompt.value = null
    }

    fun onPromptDeclined(moment: ReviewMoment) {
        val version = BuildConfig.VERSION_NAME
        when (moment) {
            ReviewMoment.AFTER_SYNC -> store.syncPromptDeclinedVersion = version
            ReviewMoment.MINUTES_THRESHOLD -> store.minutesPromptDeclinedVersion = version
        }
        pendingPrompt.value = null
    }

    /** OPPO store installed and new enough to support the comment page (versionCode >= 84000). */
    fun isMarketAvailable(): Boolean = marketPackage() != null

    /** Launches the store comment page for this app. Activity context, no NEW_TASK. */
    fun launchComment(activity: Activity): Boolean {
        val marketPackage = marketPackage() ?: return false
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("oaps://mk/developer/comment?pkg=${context.packageName}")
        ).apply { setPackage(marketPackage) }
        if (intent.resolveActivity(activity.packageManager) == null) return false
        return runCatching {
            activity.startActivity(intent)
            Log.i(TAG, "OPPO comment page launched via $marketPackage")
            true
        }.onFailure { Log.w(TAG, "failed to launch OPPO comment page", it) }
            .getOrDefault(false)
    }

    private fun marketPackage(): String? {
        for (candidate in listOf(MARKET_PACKAGE_HEYTAP, MARKET_PACKAGE_OPPO)) {
            val info = runCatching {
                context.packageManager.getPackageInfo(candidate, 0)
            }.getOrNull() ?: continue
            if (info.applicationInfo?.enabled == true &&
                PackageInfoCompat.getLongVersionCode(info) >= MARKET_MIN_VERSION_CODE
            ) {
                return candidate
            }
        }
        return null
    }

    companion object {
        const val TAG = "WatchRSS_ReviewGate"
        const val MINUTES_THRESHOLD_MS = 10 * 60 * 1000L
        private const val MARKET_PACKAGE_HEYTAP = "com.heytap.market" // Android Q+
        private const val MARKET_PACKAGE_OPPO = "com.oppo.market" // Android Q-
        private const val MARKET_MIN_VERSION_CODE = 84_000L
    }
}
