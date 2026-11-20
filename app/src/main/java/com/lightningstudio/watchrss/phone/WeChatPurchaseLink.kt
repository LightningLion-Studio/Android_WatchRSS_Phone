package com.lightningstudio.watchrss.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import com.lightningstudio.watchrss.phone.account.WECHAT_HANDOFF_TIMEOUT_MILLIS
import com.lightningstudio.watchrss.phone.account.WeChatHandoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

private const val WECHAT_CLOUD_MEMBERSHIP_URL = "https://wxmpurl.cn/kck25HzrRpc"

internal suspend fun Context.openPurchaseInWeChat(
    requestHandoff: (suspend () -> WeChatHandoff)? = null
): Boolean {
    val handoff = requestHandoff?.let { request ->
        withTimeoutOrNull(WECHAT_HANDOFF_TIMEOUT_MILLIS) {
            try {
                request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    currentCoroutineContext().ensureActive()

    val baseUri = WECHAT_CLOUD_MEMBERSHIP_URL.toUri()
    val uri = if (handoff != null && handoff.expiresAtMillis > System.currentTimeMillis()) {
        baseUri.buildUpon().appendQueryParameter("cq", "t=${handoff.token}").build()
    } else {
        baseUri
    }
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
internal fun CloudMembershipPurchaseButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("购买云同步会员")
    }
}

@Composable
internal fun WeChatPurchaseFallbackDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text("请打开微信，搜索小程序「腕上RSS」，登录后购买云同步会员。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}
