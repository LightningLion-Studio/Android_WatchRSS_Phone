package com.lightningstudio.watchrss.phone.model

data class AbilityResponse(
    val code: String,
    val name: String,
    val version: String
)

data class HealthResponse(
    val status: String
)

data class RSSUrlRequest(
    val url: String
)

data class RSSUrlResponse(
    val success: Boolean,
    val message: String
)

data class FavoriteItem(
    val id: String,
    val title: String,
    val link: String,
    val summary: String,
    val channelTitle: String,
    val pubDate: String
)

data class FavoritesResponse(
    val success: Boolean,
    val data: List<FavoriteItem>?
)

data class WatchLaterItem(
    val id: String,
    val title: String,
    val link: String,
    val summary: String,
    val channelTitle: String,
    val pubDate: String
)

data class WatchLaterResponse(
    val success: Boolean,
    val data: List<WatchLaterItem>?
)
