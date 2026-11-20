package com.lightningstudio.watchrss.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lightningstudio.watchrss.phone.account.WECHAT_HANDOFF_TIMEOUT_MILLIS
import com.lightningstudio.watchrss.phone.account.WeChatHandoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val WECHAT_CLOUD_MEMBERSHIP_URL = "https://wxmpurl.cn/kck25HzrRpc"

private const val PURCHASE_COOLDOWN_MILLIS = 1_000L

internal enum class WeChatPurchaseResult { OPENED, NO_HANDLER, CANCELLED }

internal suspend fun Context.openPurchaseInWeChat(
    canDispatch: () -> Boolean = { true },
    withCurrentSession: (() -> WeChatPurchaseResult) -> WeChatPurchaseResult? = { it() },
    requestHandoff: (suspend () -> WeChatHandoff)? = null
): WeChatPurchaseResult {
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
    val coroutineContext = currentCoroutineContext()
    return withCurrentSession {
        coroutineContext.ensureActive()
        if ((this is Activity && (isFinishing || isDestroyed)) || !canDispatch()) {
            return@withCurrentSession WeChatPurchaseResult.CANCELLED
        }

        val baseUri = WECHAT_CLOUD_MEMBERSHIP_URL.toUri()
        val uri = if (handoff != null && handoff.expiresAtMillis > System.currentTimeMillis()) {
            baseUri.buildUpon().appendQueryParameter("cq", "t=${handoff.token}").build()
        } else {
            baseUri
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            WeChatPurchaseResult.OPENED
        } catch (_: ActivityNotFoundException) {
            WeChatPurchaseResult.NO_HANDLER
        }
    } ?: WeChatPurchaseResult.CANCELLED
}

internal class WeChatPurchaseLauncher(
    private val activity: Activity,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope
) : DefaultLifecycleObserver {
    var busy by mutableStateOf(false)
        private set
    var returnRevision by mutableIntStateOf(0)
        private set

    private var purchase: Job? = null
    private var coolingDown = false
    private var awaitingReturn = false

    fun launch(
        isPageActive: () -> Boolean,
        withCurrentSession: (() -> WeChatPurchaseResult) -> WeChatPurchaseResult?,
        requestHandoff: (suspend () -> WeChatHandoff)?,
        onNoHandler: () -> Unit
    ) {
        fun canDispatch() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && isPageActive()
        if (busy || activity.isFinishing || activity.isDestroyed || !canDispatch()) return
        busy = true
        purchase = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                when (activity.openPurchaseInWeChat(::canDispatch, withCurrentSession, requestHandoff)) {
                    WeChatPurchaseResult.OPENED -> {
                        awaitingReturn = true
                        coolingDown = true
                        delay(PURCHASE_COOLDOWN_MILLIS)
                    }
                    WeChatPurchaseResult.NO_HANDLER -> onNoHandler()
                    WeChatPurchaseResult.CANCELLED -> Unit
                }
            } finally {
                coolingDown = false
                busy = false
            }
        }
    }

    fun cancelPending() {
        if (!coolingDown) purchase?.cancel()
    }

    override fun onPause(owner: LifecycleOwner) = cancelPending()

    override fun onResume(owner: LifecycleOwner) {
        if (!awaitingReturn) return
        awaitingReturn = false
        returnRevision++
        if (coolingDown) purchase?.cancel()
    }

    fun dispose() {
        purchase?.cancel()
    }
}

@Composable
internal fun rememberWeChatPurchaseLauncher(): WeChatPurchaseLauncher? {
    val activity = LocalActivity.current ?: return null
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val launcher = remember(activity, owner) { WeChatPurchaseLauncher(activity, owner.lifecycle, scope) }
    DisposableEffect(launcher, owner) {
        owner.lifecycle.addObserver(launcher)
        onDispose {
            owner.lifecycle.removeObserver(launcher)
            launcher.dispose()
        }
    }
    return launcher
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
