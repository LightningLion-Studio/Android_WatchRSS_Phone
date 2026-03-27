package com.lightningstudio.watchrss.phone.data.model

data class WatchEndpoint(
    val host: String,
    val port: Int
) {
    val baseUrl: String = "http://$host:$port"
    val displayLabel: String = "$host:$port"
}
