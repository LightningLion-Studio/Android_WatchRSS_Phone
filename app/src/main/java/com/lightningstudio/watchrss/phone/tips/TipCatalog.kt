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
        )
    )

    fun byId(id: TipId): TipDefinition? = all.firstOrNull { it.id == id }
}
