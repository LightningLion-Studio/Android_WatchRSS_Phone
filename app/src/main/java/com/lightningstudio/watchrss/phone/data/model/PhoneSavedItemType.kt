package com.lightningstudio.watchrss.phone.data.model

enum class PhoneSavedItemType(val wirePath: String, val displayName: String) {
    FAVORITE(
        wirePath = "/getFavorites",
        displayName = "收藏"
    ),
    WATCH_LATER(
        wirePath = "/getWatchlaterList",
        displayName = "稍后再看"
    )
}
