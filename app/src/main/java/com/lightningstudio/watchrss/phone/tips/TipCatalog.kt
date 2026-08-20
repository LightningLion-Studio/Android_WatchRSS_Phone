package com.lightningstudio.watchrss.phone.tips

/**
 * Tip 唯一 id 常量：目录与各屏锚点（Modifier.tipAnchor）共同引用，避免魔法字符串。
 */
object TipIds {
    // 蓝牙同步
    const val SYNC_MANUAL = "sync_manual"
    const val SYNC_STATUS_CARD = "sync_status_card"

    // AI 总结与词元
    const val TOKEN_USAGE = "token_usage"
    const val AI_SUMMARY = "ai_summary"

    // 内容管理
    const val FAVORITES_VS_WATCH_LATER = "favorites_vs_watch_later"
    const val IMPORTS_THREE_WAYS = "imports_three_ways"
    const val RSS_FAB = "rss_fab"

    // 账号与数据
    const val PASSKEY_LOGIN = "passkey_login"
    const val BACKUP_FORMATS = "backup_formats"
    const val BACKUP_MERGE = "backup_merge"
    const val CLOUD_E2EE = "cloud_e2ee"
    const val PROFILE_DATA = "profile_data"
}

/**
 * 提示目录：新增一条提示只需在这里追加 [TipDefinition]，
 * 并在对应控件上挂 Modifier.tipAnchor(TipIds.XXX)，无需改动任何框架逻辑。
 *
 * 文案原则：不是补丁，是把技术味（RFCOMM、词元……）讲成用户能用得明白、
 * 有帮助、有温度的话。
 */
object TipCatalog {

    val all: List<TipDefinition> = listOf(
        // ── 蓝牙同步 ─────────────────────────────────────────────
        TipDefinition(
            id = TipIds.SYNC_MANUAL,
            title = "同步手表",
            message = "点击即可把资料库与已配对手表双向同步；优先走 Wi-Fi，失败时自动改用蓝牙 RFCOMM 通道。",
            priority = 10,
            rule = TipRules.allOf(
                TipRules.eventAtLeast(TipEvents.APP_LAUNCH, 1),
                TipRules.eventNever(TipEvents.SYNC_COMPLETED)
            ),
            displayFrequency = TipDisplayFrequency.DAILY,
            maxShows = 2,
            invalidateOnEvents = setOf(TipEvents.SYNC_COMPLETED)
        ),
        TipDefinition(
            id = TipIds.SYNC_STATUS_CARD,
            title = "同步状态",
            message = "RFCOMM 是蓝牙传输通道，不是手表型号——这里显示同步进度与结果，异常时可导出蓝牙日志排查。",
            priority = 8,
            rule = TipRules.eventAtLeast(TipEvents.SYNC_COMPLETED, 1),
            displayFrequency = TipDisplayFrequency.WEEKLY,
            maxShows = 2
        ),
        // ── AI 总结与词元 ────────────────────────────────────────
        TipDefinition(
            id = TipIds.TOKEN_USAGE,
            title = "词元用量",
            message = "AI 总结消耗的词元会记录在这里，与手表同步后自动更新。",
            priority = 5,
            rule = TipRules.param(TipParameters.TOKEN_STATS_EMPTY),
            displayFrequency = TipDisplayFrequency.WEEKLY,
            maxShows = 2
        ),
        TipDefinition(
            id = TipIds.AI_SUMMARY,
            title = "AI 总结",
            message = "一键总结全文要点；词元消耗显示在下方，可同步到手表查看。",
            priority = 5,
            rule = TipRules.allOf(
                TipRules.eventAtLeast(TipEvents.ARTICLE_OPENED, 2),
                TipRules.eventNever(TipEvents.AI_SUMMARY_COMPLETED)
            ),
            displayFrequency = TipDisplayFrequency.DAILY,
            maxShows = 1,
            invalidateOnEvents = setOf(TipEvents.AI_SUMMARY_COMPLETED)
        ),
        // ── 内容管理 ─────────────────────────────────────────────
        TipDefinition(
            id = TipIds.FAVORITES_VS_WATCH_LATER,
            title = "收藏与稍后",
            message = "收藏用于永久保存，稍后再看是临时清单，两者都会同步到手表。",
            priority = 5,
            rule = TipRules.allOf(
                TipRules.param(TipParameters.HAS_ANY_ARTICLE),
                TipRules.eventNever(TipEvents.FAVORITE_TOGGLED),
                TipRules.eventNever(TipEvents.WATCH_LATER_TOGGLED)
            ),
            displayFrequency = TipDisplayFrequency.ALWAYS,
            maxShows = 1,
            invalidateOnEvents = setOf(TipEvents.FAVORITE_TOGGLED, TipEvents.WATCH_LATER_TOGGLED)
        ),
        TipDefinition(
            id = TipIds.IMPORTS_THREE_WAYS,
            title = "三种导入",
            message = "RSS 订阅频道、OPML 批量订阅、网页独立文章、TXT/EPUB 本地内容，按需选择。",
            priority = 8,
            rule = TipRules.allOf(
                TipRules.param(TipParameters.HAS_NO_IMPORTS),
                TipRules.eventAtLeast(TipEvents.APP_LAUNCH, 1)
            ),
            displayFrequency = TipDisplayFrequency.ALWAYS,
            maxShows = 1,
            invalidateOnEvents = setOf(
                TipEvents.RSS_SOURCE_ADDED,
                TipEvents.ARTICLE_IMPORTED,
                TipEvents.LOCAL_CONTENT_IMPORTED
            )
        ),
        TipDefinition(
            id = TipIds.RSS_FAB,
            title = "添加 RSS 源",
            message = "粘贴频道地址即可订阅，文章会按频道自动分组。",
            priority = 8,
            rule = TipRules.allOf(
                TipRules.eventAtLeast(TipEvents.APP_LAUNCH, 2),
                TipRules.eventNever(TipEvents.RSS_SOURCE_ADDED)
            ),
            displayFrequency = TipDisplayFrequency.WEEKLY,
            maxShows = 2,
            invalidateOnEvents = setOf(TipEvents.RSS_SOURCE_ADDED)
        ),
        // ── 账号与数据 ─────────────────────────────────────────────
        TipDefinition(
            id = TipIds.PASSKEY_LOGIN,
            title = "通行密钥",
            message = "用指纹、人脸或设备屏幕锁登录，无需短信验证码。",
            priority = 8,
            rule = TipRules.eventNever(TipEvents.ACCOUNT_SIGNED_IN),
            displayFrequency = TipDisplayFrequency.ALWAYS,
            maxShows = 1,
            invalidateOnEvents = setOf(TipEvents.ACCOUNT_SIGNED_IN)
        ),
        TipDefinition(
            id = TipIds.BACKUP_FORMATS,
            title = "备份格式",
            message = ".wrss 专有格式可完整恢复；JSON 便于在其他平台查看。",
            priority = 10,
            rule = TipRules.eventAtLeast(TipEvents.DATA_MANAGEMENT_OPENED, 1),
            displayFrequency = TipDisplayFrequency.ALWAYS,
            maxShows = 1,
            invalidateOnEvents = setOf(TipEvents.BACKUP_EXPORTED)
        ),
        TipDefinition(
            id = TipIds.BACKUP_MERGE,
            title = "覆盖还是合并",
            message = "覆盖会清空现有数据；合并保留本地更新的内容。",
            priority = 9,
            rule = TipRules.allOf(
                TipRules.eventAtLeast(TipEvents.DATA_MANAGEMENT_OPENED, 2),
                TipRules.eventNever(TipEvents.BACKUP_IMPORTED)
            ),
            displayFrequency = TipDisplayFrequency.ALWAYS,
            maxShows = 1,
            invalidateOnEvents = setOf(TipEvents.BACKUP_IMPORTED)
        ),
        TipDefinition(
            id = TipIds.CLOUD_E2EE,
            title = "端到端加密",
            message = "云备份采用端到端加密；24 个恢复词请离线抄写妥善保管。",
            priority = 8,
            rule = TipRules.eventNever(TipEvents.CLOUD_ENCRYPTION_ENABLED),
            displayFrequency = TipDisplayFrequency.ALWAYS,
            maxShows = 1,
            invalidateOnEvents = setOf(TipEvents.CLOUD_ENCRYPTION_ENABLED)
        ),
        TipDefinition(
            id = TipIds.PROFILE_DATA,
            title = "数据管理",
            message = "备份、恢复或删除本机资料库都在这里。",
            priority = 5,
            rule = TipRules.allOf(
                TipRules.eventAtLeast(TipEvents.PROFILE_OPENED, 1),
                TipRules.eventNever(TipEvents.DATA_MANAGEMENT_OPENED)
            ),
            displayFrequency = TipDisplayFrequency.WEEKLY,
            maxShows = 1,
            invalidateOnEvents = setOf(TipEvents.DATA_MANAGEMENT_OPENED)
        )
    )

    fun byId(id: TipId): TipDefinition? = all.firstOrNull { it.id == id }
}
