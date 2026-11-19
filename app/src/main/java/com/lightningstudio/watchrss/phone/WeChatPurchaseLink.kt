package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.WeChatMiniProgram
import com.lightningstudio.watchrss.phone.account.WeChatPurchase
import com.lightningstudio.watchrss.phone.account.WeChatPurchaseFallback
import com.tencent.mm.opensdk.constants.Build
import com.tencent.mm.opensdk.modelbiz.WXLaunchMiniProgram
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.utils.ILog

internal suspend fun Context.openPurchaseInWeChat(
    repository: PhoneAccountRepository,
    expectedUserId: String
): WeChatPurchaseFallback? =
    WeChatPurchase(
        installed = {
            try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo("com.tencent.mm", 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        },
        appId = BuildConfig.WATCHRSS_WECHAT_OPEN_APP_ID.trim(),
        originalId = BuildConfig.WATCHRSS_WECHAT_MINI_PROGRAM_ORIGINAL_ID.trim(),
        connect = { id ->
            val api = WXAPIFactory.createWXAPI(this, id, true)
            var ready = false
            try {
                api.setLogImpl(DiscardWeChatLogs)
                ready = api.isWXAppInstalled &&
                    api.wxAppSupportAPI >= Build.LAUNCH_MINIPROGRAM_SUPPORTED_SDK_INT &&
                    api.registerApp(id)
                if (ready) object : WeChatMiniProgram {
                    override fun send(userName: String, path: String): Boolean {
                        return api.sendReq(weChatPurchaseRequest(userName, path))
                    }

                    override fun close() { api.detach() }
                } else null
            } finally {
                if (!ready) api.detach()
            }
        }
    ).launch { repository.createWeChatHandoff(expectedUserId) }

internal fun weChatPurchaseRequest(userName: String, miniPath: String) =
    WXLaunchMiniProgram.Req().apply {
        this.userName = userName
        path = miniPath
        miniprogramType = WXLaunchMiniProgram.Req.MINIPTOGRAM_TYPE_RELEASE
    }

/** The SDK receives an opaque credential in the path. Suppress its diagnostics. */
private object DiscardWeChatLogs : ILog {
    override fun v(tag: String?, message: String?) = Unit
    override fun d(tag: String?, message: String?) = Unit
    override fun i(tag: String?, message: String?) = Unit
    override fun w(tag: String?, message: String?) = Unit
    override fun e(tag: String?, message: String?) = Unit
}

@Composable
internal fun WeChatPurchaseFallbackDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text("请打开微信，搜索小程序「腕上RSS」，登录后购买云同步权限。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}
