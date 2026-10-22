package com.lightningstudio.watchrss.phone.cloud

import org.json.JSONObject

/** Durable state can arrive before the disposable public entry. Equal clocks resolve deterministically. */
internal object CloudRssInteraction {
    fun merge(old: JSONObject?, incoming: JSONObject): JSONObject {
        if (old == null) return JSONObject(incoming.toString())
        val out = JSONObject(old.toString())
        fun group(clock: String, fields: List<String>) {
            val a = old.optLong(clock); val b = incoming.optLong(clock)
            val wins = b > a || (b == a && (incoming.optString("sourceDeviceId", "") > old.optString("sourceDeviceId", "") || (incoming.optString("sourceDeviceId", "") == old.optString("sourceDeviceId", "") && PhoneCloudRecordCodec.canonical(incoming) < PhoneCloudRecordCodec.canonical(old))))
            if (wins) (fields + clock).forEach { out.put(it, incoming.opt(it) ?: JSONObject.NULL) }
        }
        group("readingPositionChangedAt", listOf("isRead", "readingProgress", "readingPositionBytes", "readingPositionContentHash", "sourceDeviceId"))
        if (old.optLong("readingPositionChangedAt") == incoming.optLong("readingPositionChangedAt") && old.optString("sourceDeviceId", "") == incoming.optString("sourceDeviceId", "")) out.put("isRead", old.optBoolean("isRead") && incoming.optBoolean("isRead"))
        group("favoriteChangedAt", listOf("favoriteSaved", "favoriteSortOrder"))
        group("watchLaterChangedAt", listOf("watchLaterSaved", "watchLaterSortOrder"))
        group("deletedAt", listOf("deleted"))
        return out
    }
}
