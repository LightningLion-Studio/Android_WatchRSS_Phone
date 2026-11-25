package com.lightningstudio.watchrss.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lightningstudio.watchrss.phone.account.AppPaymentOrder
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.ui.generateQRCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal enum class MembershipScanPlan(val productId: String, val label: String) {
    Month("watchrss_cloud_month", "月卡 · ¥8"),
    Year("watchrss_cloud_year", "年卡 · ¥88")
}

internal class MembershipPurchaseState<Page : Any> {
    internal var request by mutableStateOf<Request<Page>?>(null)
        private set

    fun open(page: Page) {
        show(page, Step.Choice)
    }

    fun resume(page: Page, order: AppPaymentOrder) {
        show(page, Step.Resume(order))
    }

    internal fun show(page: Page, step: Step) {
        request = Request(page, step)
    }

    internal fun dismiss() {
        request = null
    }

    internal data class Request<Page>(val page: Page, val step: Step)

    internal sealed interface Step {
        data object Choice : Step
        data object SignIn : Step
        data object Plans : Step
        data class Buy(val plan: MembershipScanPlan) : Step
        data class Resume(val order: AppPaymentOrder) : Step
    }
}

@Composable
internal fun <Page : Any> MembershipPurchaseDialogs(
    state: MembershipPurchaseState<Page>,
    visiblePage: Page?,
    ownerId: String?,
    repository: PhoneAccountRepository,
    onWeChat: (Page) -> Unit,
    onPaid: (String) -> Unit,
    onCheckoutClosed: () -> Unit
) {
    val request = state.request ?: return
    if (visiblePage != request.page) return
    val dismiss = state::dismiss
    when (val step = request.step) {
        MembershipPurchaseState.Step.Choice -> MembershipPaymentChoiceDialog(
            onScan = {
                val next = if (ownerId == null) MembershipPurchaseState.Step.SignIn else MembershipPurchaseState.Step.Plans
                state.show(request.page, next)
            },
            onWeChat = {
                dismiss()
                onWeChat(request.page)
            },
            onDismiss = dismiss
        )
        MembershipPurchaseState.Step.SignIn -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("请先登录") },
            text = { Text("登录账号后可以扫码购买云同步会员。") },
            confirmButton = { TextButton(onClick = dismiss) { Text("知道了") } }
        )
        MembershipPurchaseState.Step.Plans -> {
            if (ownerId == null) return
            MembershipPlanDialog(
                onSelect = { plan ->
                    if (state.request === request) {
                        state.show(request.page, MembershipPurchaseState.Step.Buy(plan))
                    }
                },
                onDismiss = dismiss
            )
        }
        is MembershipPurchaseState.Step.Buy, is MembershipPurchaseState.Step.Resume -> {
            if (ownerId == null) return
            val pending = (step as? MembershipPurchaseState.Step.Resume)?.order
            val productId = when (step) {
                is MembershipPurchaseState.Step.Buy -> step.plan.productId
                is MembershipPurchaseState.Step.Resume -> step.order.product.productId
                else -> error("Payment product missing")
            }
            key(ownerId, request) {
                MembershipQrCheckout(
                    resumeOrderId = pending?.orderId,
                    createOrder = { key -> repository.createMembershipPaymentOrder(ownerId, productId, key) },
                    readOrder = { repository.membershipPaymentOrder(ownerId, it) },
                    recoverOrderQr = { repository.resumeMembershipPaymentOrder(ownerId, it) },
                    onPaid = { onPaid(ownerId) },
                    onDismiss = {
                        dismiss()
                        onCheckoutClosed()
                    }
                )
            }
        }
    }
}

@Composable
internal fun MembershipPaymentChoiceDialog(onScan: () -> Unit, onWeChat: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择支付方式") },
        confirmButton = { Button(onClick = onScan) { Text("扫码支付") } },
        dismissButton = { TextButton(onClick = onWeChat) { Text("跳转微信") } }
    )
}

@Composable
internal fun MembershipPlanDialog(onSelect: (MembershipScanPlan) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择云同步会员") },
        text = {
            Column {
                MembershipScanPlan.entries.forEach { plan ->
                    TextButton(onClick = { onSelect(plan) }, modifier = Modifier.fillMaxWidth()) {
                        Text(plan.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

internal suspend fun loadMembershipCheckout(
    resumeOrderId: String?,
    createOrder: suspend () -> AppPaymentOrder,
    readOrder: suspend (String) -> AppPaymentOrder,
    recoverOrderQr: suspend (String) -> AppPaymentOrder
): AppPaymentOrder {
    if (resumeOrderId == null) return createOrder()

    // Details reconcile the payment and omit QR payloads past the server's deadline.
    var current = readOrder(resumeOrderId)
    if (current.status == "pending" && current.qrPayload == null) {
        current = recoverOrderQr(resumeOrderId)
    }
    return if (current.status == "closed") createOrder() else current
}

@Composable
internal fun MembershipQrCheckout(
    resumeOrderId: String?,
    createOrder: suspend (String) -> AppPaymentOrder,
    readOrder: suspend (String) -> AppPaymentOrder,
    recoverOrderQr: suspend (String) -> AppPaymentOrder,
    onPaid: () -> Unit,
    onDismiss: () -> Unit
) {
    val idempotencyKey = remember { "xunhu-cloud-${UUID.randomUUID()}" }
    var createAttempt by remember { mutableIntStateOf(0) }
    var order by remember { mutableStateOf<AppPaymentOrder?>(null) }
    var creationFailed by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    val create by rememberUpdatedState(createOrder)
    val read by rememberUpdatedState(readOrder)
    val recover by rememberUpdatedState(recoverOrderQr)
    val paid by rememberUpdatedState(onPaid)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(createAttempt) {
        creationFailed = false
        try {
            order = loadMembershipCheckout(
                resumeOrderId,
                createOrder = { create(idempotencyKey) },
                readOrder = { read(it) },
                recoverOrderQr = { recover(it) }
            ).also { created ->
                check(created.status != "pending" || created.qrPayload != null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            creationFailed = true
        }
    }

    val current = order
    val payload = current?.takeIf { it.status == "pending" }?.qrPayload
    LaunchedEffect(current?.orderId, current?.status, payload, lifecycle) {
        when {
            current?.status == "paid" -> paid()
            current?.status == "pending" && payload != null -> lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (timedOut) return@repeatOnLifecycle
                val confirmed = withTimeoutOrNull(120_000L) {
                    var status = current.status
                    while (status == "pending") {
                        delay(3_000)
                        val refreshed = try {
                            read(current.orderId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            continue
                        }
                        status = refreshed.status
                        order = refreshed
                    }
                    true
                }
                timedOut = confirmed == null
            }
        }
    }

    val qr = remember(payload) {
        payload?.let {
            runCatching { generateQRCode(it).asImageBitmap() }.getOrNull()
        }
    }
    val title = when (current?.status) {
        "paid" -> "已开通"
        "closed" -> "订单已关闭"
        null, "pending" -> "扫码支付"
        else -> "订单状态已更新"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (current != null) {
                    Text("${current.product.productName} · ¥${current.amountFen / 100}")
                }
                when {
                    current?.status == "paid" -> Text("云同步会员已开通")
                    current?.status == "closed" -> Text("请返回订单页查看状态，或重新下单。")
                    current != null && current.status != "pending" -> Text("请返回订单页查看状态。")
                    creationFailed -> Text("暂时无法获取付款二维码，请重试。")
                    timedOut -> Text("暂未确认付款。可以从订单页继续。")
                    qr != null -> {
                        Image(
                            painter = BitmapPainter(qr, filterQuality = FilterQuality.None),
                            contentDescription = "微信付款二维码",
                            modifier = Modifier.size(220.dp)
                        )
                        Text("用微信扫码付款，付款后自动开通", style = MaterialTheme.typography.bodySmall)
                    }
                    payload != null -> Text("无法显示付款二维码，请返回订单页重试。")
                    current != null -> Text("付款二维码已失效，可以从订单页继续。")
                    resumeOrderId != null -> Text("正在核对订单…")
                    else -> Text("正在获取付款二维码…")
                }
            }
        },
        confirmButton = {
            if (creationFailed) {
                Button(onClick = { createAttempt++ }) { Text("重试") }
            } else {
                TextButton(onClick = onDismiss) { Text(if (current?.status == "paid") "完成" else "关闭") }
            }
        },
        dismissButton = if (creationFailed) {
            { TextButton(onClick = onDismiss) { Text("关闭") } }
        } else null
    )
}
