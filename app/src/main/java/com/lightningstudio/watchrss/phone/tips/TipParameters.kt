package com.lightningstudio.watchrss.phone.tips

/**
 * 界面状态参数键的唯一事实源。目录中的参数规则都必须引用这里的常量，由 TipCatalogTest 校验。
 */
object TipParameters {
    const val TOKEN_STATS_EMPTY = "token_stats_empty"
    const val HAS_ANY_ARTICLE = "has_any_article"
    const val HAS_NO_IMPORTS = "has_no_imports"
    const val IS_LOGGED_OUT = "is_logged_out"
    const val CLOUD_PANE_VISIBLE = "cloud_pane_visible"

    /** 系统参数：页面转场动画进行中时为 true，宿主暂停展示新 Tip。 */
    const val SUPPRESS_TIPS = "suppress_tips"
}
