package com.lightningstudio.watchrss.phone

/**
 * Shared data class for file import operations across MainActivity, RssActivity, and ListPageActivity.
 */
data class SelectedLocalContent(
    val fileName: String,
    val mimeType: String?,
    val bytes: ByteArray
)
