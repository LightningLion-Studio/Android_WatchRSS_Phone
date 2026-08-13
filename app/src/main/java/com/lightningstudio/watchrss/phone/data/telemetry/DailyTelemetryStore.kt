package com.lightningstudio.watchrss.phone.data.telemetry

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

internal data class DailyTelemetrySnapshot(
    val day: String,
    val eventCounts: Map<String, Long> = emptyMap(),
    val screenOpenCounts: Map<String, Long> = emptyMap(),
    val screenDurationMs: Map<String, Long> = emptyMap(),
    val appForegroundMs: Long = 0L,
    val syncSuccessCount: Int = 0,
    val syncFailureCount: Int = 0
) {
    fun record(event: String, properties: Map<String, Any?>): DailyTelemetrySnapshot {
        val events = eventCounts.increment(event)
        val screen = properties["screen"]?.toString()?.takeIf { it.isNotBlank() }
        val durationMs = (properties["durationMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
        return copy(
            eventCounts = events,
            screenOpenCounts = if (event == "screen_opened" && screen != null) {
                screenOpenCounts.increment(screen)
            } else screenOpenCounts,
            screenDurationMs = if (event == "screen_duration" && screen != null && durationMs > 0L) {
                screenDurationMs.increment(screen, durationMs)
            } else screenDurationMs,
            appForegroundMs = if (event == "screen_duration") appForegroundMs + durationMs else appForegroundMs,
            syncSuccessCount = syncSuccessCount + if (event == "sync_completed" && properties["success"] == true) 1 else 0,
            syncFailureCount = syncFailureCount + if (event == "sync_completed" && properties["success"] == false) 1 else 0
        )
    }
}

internal class DailyTelemetryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "watchrss_daily_telemetry",
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun record(event: String, properties: Map<String, Any?> = emptyMap()) {
        val day = today()
        val updated = read(day).record(event, properties)
        val days = (preferences.getStringSet(KEY_DAYS, emptySet()).orEmpty() + day)
            .filterTo(mutableSetOf()) { value -> isRetained(value, day) }
        preferences.edit()
            .putString(key(day), encode(updated))
            .putStringSet(KEY_DAYS, days)
            .apply()
        prune(days)
    }

    @Synchronized
    fun snapshots(): List<DailyTelemetrySnapshot> =
        preferences.getStringSet(KEY_DAYS, emptySet()).orEmpty()
            .sorted()
            .map(::read)

    private fun read(day: String): DailyTelemetrySnapshot {
        val raw = preferences.getString(key(day), null) ?: return DailyTelemetrySnapshot(day)
        return runCatching {
            val json = JSONObject(raw)
            DailyTelemetrySnapshot(
                day = day,
                eventCounts = json.optJSONObject("eventCounts").toLongMap(),
                screenOpenCounts = json.optJSONObject("screenOpenCounts").toLongMap(),
                screenDurationMs = json.optJSONObject("screenDurationMs").toLongMap(),
                appForegroundMs = json.optLong("appForegroundMs"),
                syncSuccessCount = json.optInt("syncSuccessCount"),
                syncFailureCount = json.optInt("syncFailureCount")
            )
        }.getOrElse { DailyTelemetrySnapshot(day) }
    }

    private fun encode(snapshot: DailyTelemetrySnapshot): String = JSONObject().apply {
        put("eventCounts", JSONObject(snapshot.eventCounts))
        put("screenOpenCounts", JSONObject(snapshot.screenOpenCounts))
        put("screenDurationMs", JSONObject(snapshot.screenDurationMs))
        put("appForegroundMs", snapshot.appForegroundMs)
        put("syncSuccessCount", snapshot.syncSuccessCount)
        put("syncFailureCount", snapshot.syncFailureCount)
    }.toString()

    private fun prune(retainedDays: Set<String>) {
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(KEY_PREFIX) && it.removePrefix(KEY_PREFIX) !in retainedDays }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun today(): String = LocalDate.now(TELEMETRY_ZONE).toString()

    private fun isRetained(value: String, today: String): Boolean = runCatching {
        !LocalDate.parse(value).isBefore(LocalDate.parse(today).minusDays(RETENTION_DAYS - 1L))
    }.getOrDefault(false)

    private fun key(day: String): String = "$KEY_PREFIX$day"

    private companion object {
        val TELEMETRY_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        const val KEY_DAYS = "days"
        const val KEY_PREFIX = "day_"
        const val RETENTION_DAYS = 7L
    }
}

private fun Map<String, Long>.increment(key: String, amount: Long = 1L): Map<String, Long> =
    toMutableMap().apply { this[key] = (this[key] ?: 0L) + amount }

private fun JSONObject?.toLongMap(): Map<String, Long> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { key -> optLong(key).coerceAtLeast(0L) }
}
