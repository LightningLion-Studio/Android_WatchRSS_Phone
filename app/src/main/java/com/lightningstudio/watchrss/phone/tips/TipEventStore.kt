package com.lightningstudio.watchrss.phone.tips

import android.content.Context
import java.time.LocalDate

/**
 * 用户行为事件计数的 SharedPreferences 实现。
 * 生命周期计数 + 近 30 天每日桶（为将来按日频控的事件规则预留），写时裁剪。
 */
class TipEventStore(context: Context) : TipEventBackend {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun snapshot(): Map<String, Long> = loadLifetime()

    override fun increment(event: String) {
        val lifetime = loadLifetime().toMutableMap()
        val daily = loadDaily().toMutableMap()
        val today = LocalDate.now().toString()

        lifetime[event] = (lifetime[event] ?: 0L) + 1
        val buckets = daily.getOrPut(event) { mutableMapOf() }.toMutableMap()
        buckets[today] = (buckets[today] ?: 0L) + 1
        daily[event] = buckets

        preferences.edit()
            .putString(KEY_COUNTS, TipCodecs.eventCountsToJson(lifetime, pruneDaily(daily)).toString())
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_COUNTS).apply()
    }

    private fun loadLifetime(): Map<String, Long> =
        preferences.getString(KEY_COUNTS, null)?.let { raw ->
            TipCodecs.eventCountsFromJson(raw)?.first
                .also { if (it == null) preferences.edit().remove(KEY_COUNTS).apply() }
        } ?: emptyMap()

    private fun loadDaily(): Map<String, MutableMap<String, Long>> =
        preferences.getString(KEY_COUNTS, null)?.let { raw ->
            TipCodecs.eventCountsFromJson(raw)?.second
                ?.mapValues { (_, buckets) -> buckets.toMutableMap() }
        } ?: emptyMap()

    /** 只保留最近 30 天的每日桶（ISO 日期字符串按字典序比较即时间序）。 */
    private fun pruneDaily(daily: Map<String, Map<String, Long>>): Map<String, Map<String, Long>> {
        val cutoff = LocalDate.now().minusDays(DAILY_BUCKET_RETENTION_DAYS.toLong()).toString()
        return daily
            .mapValues { (_, buckets) -> buckets.filterKeys { it >= cutoff } }
            .filterValues { it.isNotEmpty() }
    }

    companion object {
        const val PREFERENCES_NAME = "watchrss_tips_events"
        private const val KEY_COUNTS = "counts"
        private const val DAILY_BUCKET_RETENTION_DAYS = 30
    }
}
