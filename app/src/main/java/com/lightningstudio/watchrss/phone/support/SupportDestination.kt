package com.lightningstudio.watchrss.phone.support

import android.content.Context
import android.content.Intent
import com.lightningstudio.watchrss.phone.*
import org.json.JSONArray

/** Stable, local allowlist. Server/model text never controls an Intent class, URI or extras. */
enum class SupportDestination(val id: String, val label: String) {
    HOME("home", "打开首页"),
    SYNC("sync", "打开手表同步"),
    IMPORTS("imports", "打开导入"),
    RSS("rss", "打开内容列表"),
    ADD_RSS("add_rss", "添加 RSS 源"),
    FAVORITES("favorites", "打开收藏"),
    WATCH_LATER("watch_later", "打开稍后再看"),
    INDEPENDENT("independent", "打开独立文章"),
    IMPORTED("imported", "打开导入内容"),
    NOTES("notes", "打开备忘录"),
    ACCOUNT("account", "打开账号"),
    ORDERS("orders", "查看订单与退款"),
    SECURITY("security", "打开账号安全"),
    CLOUD_SYNC("cloud_sync", "打开云同步"),
    SETTINGS("settings", "打开设置"),
    PRESETS("presets", "打开阅读器与预设"),
    FONTS("fonts", "打开字体库"),
    BACKGROUNDS("backgrounds", "打开背景资源"),
    APP_SETTINGS("app_settings", "打开应用功能"),
    DATA("data", "打开数据管理"),
    ABOUT("about", "打开关于"),
    CONTACT("contact", "打开人工客服"),
    USER_AGREEMENT("user_agreement", "阅读用户协议"),
    PRIVACY("privacy", "阅读隐私政策"),
    PAID_AGREEMENT("paid_agreement", "阅读付费服务协议");

    fun createIntent(context: Context): Intent = when (this) {
        HOME, SYNC, IMPORTS, RSS, ADD_RSS, FAVORITES, WATCH_LATER, INDEPENDENT, IMPORTED ->
            HomeActivity.createIntent(context).putExtra(HomeActivity.EXTRA_SUPPORT_DESTINATION, id)
        NOTES -> NotesActivity.createIntent(context)
        ACCOUNT -> AccountActivity.createIntent(context)
        ORDERS -> AccountActivity.createIntent(context, section = AccountSection.ORDERS)
        SECURITY -> AccountActivity.createIntent(context, section = AccountSection.SECURITY)
        CLOUD_SYNC -> AccountActivity.createIntent(context, section = AccountSection.CLOUD_SYNC)
        SETTINGS -> SettingsActivity.createIntent(context)
        PRESETS, FONTS, BACKGROUNDS, APP_SETTINGS -> SettingsActivity.createIntent(context, section = id)
        DATA -> DataManagementActivity.createIntent(context)
        ABOUT -> Intent(context, AboutActivity::class.java)
        CONTACT -> Intent(context, ContactDeveloperActivity::class.java)
        USER_AGREEMENT -> LegalDocumentActivity.createIntent(context, LegalDocument.USER_AGREEMENT)
        PRIVACY -> LegalDocumentActivity.createIntent(context, LegalDocument.PRIVACY_POLICY)
        PAID_AGREEMENT -> LegalDocumentActivity.createIntent(context, LegalDocument.PAID_SERVICE_AGREEMENT)
    }

    companion object {
        fun fromId(id: String?): SupportDestination? = entries.firstOrNull { it.id == id }

        fun fromActions(raw: String): List<SupportDestination> {
            val items = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                if (item.optString("kind") == "navigation") fromId(item.optString("target")) else null
            }.distinct().take(3)
        }
    }
}

/** Also cleans previously saved answers from older servers, without touching real links. */
internal fun supportAnswerText(answer: String, streaming: Boolean): String {
    val cleaned = answer.replace(Regex("\\[(?:[Ss]?\\d+)(?:[,，、 ]+[Ss]?\\d+)*](?!\\()"), "")
    return if (streaming) cleaned.replace(Regex("\\[(?:[Ss]?\\d*)$"), "") else cleaned
}
