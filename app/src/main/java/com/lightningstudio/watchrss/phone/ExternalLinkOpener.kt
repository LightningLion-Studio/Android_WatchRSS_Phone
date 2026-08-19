package com.lightningstudio.watchrss.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 统一的外部链接拉起入口。
 *
 * 所有需要跳转到系统浏览器（或能处理对应 scheme 的应用）的位置都应经由这里，
 * 避免设备上没有可用浏览器时 startActivity 抛出 ActivityNotFoundException 直接闪退，
 * 或只弹 Toast 导致提示不明确。没有任何应用可打开链接时，弹出 App 内阻断式弹窗
 * [NoBrowserDialogActivity]，用户必须明确确认后才能继续。
 *
 * 拉起外部链接，没有可打开的应用时自动弹出阻断式提示弹窗。
 * @return true 表示已成功交给系统处理；false 表示没有可打开的应用，已弹出弹窗。
 */
fun Context.openExternally(url: String): Boolean {
    if (tryOpenExternal(url)) return true
    showUnopenableDialog(url)
    return false
}

/** 尝试拉起外部链接，不弹任何提示。@return 是否已成功交给系统处理。 */
fun Context.tryOpenExternal(url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (intent.resolveActivity(packageManager) == null) return false
    return runCatching { startActivity(intent) }.isSuccess
}

/**
 * 弹出「无法打开链接」的 App 内阻断式弹窗（非 Toast）。
 * 仅当 [url] 是 http/https 时展示链接本身，避免 intent: 等协议串污染文案。
 */
fun Context.showUnopenableDialog(url: String) {
    val displayUrl = Uri.parse(url)
        .takeIf { it.scheme?.lowercase() in setOf("http", "https") }
        ?.toString()
        .orEmpty()
    val intent = NoBrowserDialogActivity.createIntent(this, displayUrl)
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // 兜底：弹窗自身无法启动时（理论上不会发生），退化为 Toast，绝不闪退。
    runCatching { startActivity(intent) }
        .onFailure { Toast.makeText(this, "无法打开链接，请安装浏览器后重试", Toast.LENGTH_SHORT).show() }
}
