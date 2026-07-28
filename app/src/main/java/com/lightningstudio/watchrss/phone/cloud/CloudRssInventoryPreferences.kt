package com.lightningstudio.watchrss.phone.cloud

import android.content.Context
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter

enum class CloudRssInventoryMode {
    RECENT_128,
    ALL
}

class CloudRssInventoryPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "watchrss_cloud_rss_inventory",
        Context.MODE_PRIVATE
    )

    fun mode(sourceUrl: String): CloudRssInventoryMode =
        if (preferences.getBoolean(key(sourceUrl), false)) {
            CloudRssInventoryMode.ALL
        } else {
            CloudRssInventoryMode.RECENT_128
        }

    fun setMode(sourceUrl: String, mode: CloudRssInventoryMode) {
        preferences.edit()
            .putBoolean(key(sourceUrl), mode == CloudRssInventoryMode.ALL)
            .apply()
    }

    private fun key(sourceUrl: String): String =
        "all_${WebArticleImporter.sha256(sourceUrl)}"
}
