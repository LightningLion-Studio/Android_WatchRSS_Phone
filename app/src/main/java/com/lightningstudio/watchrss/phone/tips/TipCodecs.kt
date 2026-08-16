package com.lightningstudio.watchrss.phone.tips

import org.json.JSONObject

/**
 * Tip 状态与事件计数的纯 JSON codec，不依赖 Android，可在 JVM 单测中直接验证。
 * SharedPreferences 包装（TipStateStore/TipEventStore）只做存取。
 */
object TipCodecs {
    private const val SCHEMA_VERSION = 1

    // ── Tip 状态 ──────────────────────────────────────────────────

    fun tipStatesToJson(states: Map<TipId, TipState>): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("tips", JSONObject().apply {
            states.forEach { (id, state) ->
                put(id, JSONObject().apply {
                    put("dismissed", state.dismissed)
                    put("invalidated", state.invalidated)
                    put("lastShownAtMillis", state.lastShownAtMillis)
                    put("showCount", state.showCount)
                })
            }
        })
    }

    fun tipStatesFromJson(raw: String): Map<TipId, TipState>? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schemaVersion") != SCHEMA_VERSION) return@runCatching null
        val tips = json.optJSONObject("tips") ?: return@runCatching emptyMap<TipId, TipState>()
        buildMap {
            tips.keys().forEach { id ->
                val state = tips.getJSONObject(id)
                put(
                    id,
                    TipState(
                        dismissed = state.optBoolean("dismissed"),
                        invalidated = state.optBoolean("invalidated"),
                        lastShownAtMillis = state.optLong("lastShownAtMillis"),
                        showCount = state.optInt("showCount")
                    )
                )
            }
        }
    }.getOrNull()

    // ── 事件计数 ──────────────────────────────────────────────────

    fun eventCountsToJson(
        lifetime: Map<String, Long>,
        daily: Map<String, Map<String, Long>>
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("lifetime", JSONObject(lifetime))
        put("daily", JSONObject().apply {
            daily.forEach { (event, buckets) -> put(event, JSONObject(buckets)) }
        })
    }

    fun eventCountsFromJson(raw: String): Pair<Map<String, Long>, Map<String, Map<String, Long>>>? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("schemaVersion") != SCHEMA_VERSION) return@runCatching null
        val lifetimeJson = json.optJSONObject("lifetime")
        val dailyJson = json.optJSONObject("daily")
        val lifetime = buildMap {
            lifetimeJson?.keys()?.forEach { key -> put(key, lifetimeJson.getLong(key)) }
        }
        val daily = buildMap {
            dailyJson?.keys()?.forEach { event ->
                val bucketsJson = dailyJson.getJSONObject(event)
                val buckets = buildMap {
                    bucketsJson.keys().forEach { day -> put(day, bucketsJson.getLong(day)) }
                }
                put(event, buckets)
            }
        }
        lifetime to daily
    }.getOrNull()
}
