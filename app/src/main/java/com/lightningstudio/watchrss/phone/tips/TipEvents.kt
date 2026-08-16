package com.lightningstudio.watchrss.phone.tips

/**
 * 用户行为事件键的唯一事实源。目录中的事件规则与失效事件都必须引用这里的常量，
 * 由 TipCatalogTest 校验。
 */
object TipEvents {
    const val APP_LAUNCH = "app_launch"
    const val SYNC_COMPLETED = "sync_completed"
    const val FAVORITE_TOGGLED = "favorite_toggled"
    const val WATCH_LATER_TOGGLED = "watch_later_toggled"
    const val RSS_SOURCE_ADDED = "rss_source_added"
    const val ARTICLE_IMPORTED = "article_imported"
    const val LOCAL_CONTENT_IMPORTED = "local_content_imported"
    const val ARTICLE_OPENED = "article_opened"
    const val AI_SUMMARY_COMPLETED = "ai_summary_completed"
    const val IMPORTS_PAGE_OPENED = "imports_page_opened"
    const val ACCOUNT_SIGNED_IN = "account_signed_in"
    const val DATA_MANAGEMENT_OPENED = "data_management_opened"
    const val PROFILE_OPENED = "profile_opened"
    const val BACKUP_EXPORTED = "backup_exported"
    const val BACKUP_IMPORTED = "backup_imported"
    const val CLOUD_ENCRYPTION_ENABLED = "cloud_encryption_enabled"
}
