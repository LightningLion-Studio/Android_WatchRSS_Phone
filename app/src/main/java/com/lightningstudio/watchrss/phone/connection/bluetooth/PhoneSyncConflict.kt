package com.lightningstudio.watchrss.phone.connection.bluetooth

data class PhoneSyncDeleteConflict(
    val articleId: String,
    val title: String,
    val url: String,
    val phoneDeleted: Boolean,
    val watchDeleted: Boolean
)

data class PhoneSyncConflictPlan(
    val outgoingArticleIds: Set<String> = emptySet(),
    val forcedRemoteRequests: List<ArticleBodyRequest> = emptyList(),
    val suppressedRemoteArticleIds: Set<String> = emptySet(),
    val mergeResolutions: Map<String, PhoneSyncConflictResolution> = emptyMap()
)

enum class PhoneSyncConflictResolution {
    KEEP_LATEST,
    MERGE_CONTENT,
    DELETE_CONTENT,
    KEEP_PHONE,
    KEEP_WATCH
}
