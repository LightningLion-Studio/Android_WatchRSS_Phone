package com.lightningstudio.watchrss.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.account.AppAccessState
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The only exported router. No protected activity is entered before app access is valid. */
class MainActivity : ComponentActivity() {
    private val coordinator get() = (application as PhoneCompanionApplication).container.appAccessCoordinator
    private var pendingInbound: Intent? = null
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInbound = if (android.os.Build.VERSION.SDK_INT >= 33) {
            savedInstanceState?.getParcelable(KEY_PENDING_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") savedInstanceState?.getParcelable(KEY_PENDING_INTENT)
        } ?: Intent(intent)
        setContent {
            WatchRssPhoneTheme {
                val state by coordinator.state.collectAsState()
                LaunchedEffect(state) {
                    if (state is AppAccessState.Authorized) enterApplication()
                }
                Surface(Modifier.fillMaxSize()) { AccessGate(state) }
            }
        }
        lifecycleScope.launch { coordinator.reconcile() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!(intent.data?.scheme == "watchrss" && intent.data?.host == "payment-return")) {
            pendingInbound = Intent(intent)
        }
        lifecycleScope.launch { coordinator.reconcile() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { coordinator.reconcile() }
        startPaymentPolling()
    }

    override fun onPause() { pollJob?.cancel(); pollJob = null; super.onPause() }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingInbound?.let { outState.putParcelable(KEY_PENDING_INTENT, it) }
        super.onSaveInstanceState(outState)
    }

    private fun startPaymentPolling() {
        if (pollJob != null) return
        val pending = coordinator.state.value as? AppAccessState.PaymentPending ?: return
        pollJob = lifecycleScope.launch {
            repeat(120) {
                coordinator.refreshPayment(pending.order.orderId)
                if (coordinator.state.value !is AppAccessState.PaymentPending) return@launch
                delay(5_000)
            }
        }
    }

    private fun enterApplication() {
        val inbound = pendingInbound
        val target = Intent(this, HomeActivity::class.java).apply {
            if (inbound != null) {
                action = inbound.action
                setDataAndType(inbound.data, inbound.type)
                clipData = inbound.clipData; selector = inbound.selector
                addFlags(inbound.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                inbound.extras?.let(::putExtras)
            }
        }
        pendingInbound = null
        startActivity(target)
        finish()
        overridePendingTransition(0, 0)
    }

    @androidx.compose.runtime.Composable
    private fun AccessGate(state: AppAccessState) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("腕上RSS", style = MaterialTheme.typography.headlineMedium)
            when (state) {
                AppAccessState.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                AppAccessState.LoggedOut -> GateMessage("首次使用请登录", "短信验证码和 Passkey 均可使用") { openAccount() }
                is AppAccessState.PurchaseRequired -> GateMessage(
                    "¥6 永久开通，可授权 3 台手机",
                    "已购买 ${state.summary.purchaseCount} 次 · 容量 ${state.summary.capacity} 台"
                ) { startPayment() }
                is AppAccessState.PaymentPending -> GateMessage("等待支付确认", "订单 ${state.order.merchantOrderId}") {
                    state.order.paymentUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                }
                is AppAccessState.Revoked -> GateMessage("本手机授权已撤销", state.summary.revokeReason ?: "设备名额已被较新的登录占用") { reauthenticate() }
                is AppAccessState.ReauthenticationRequired -> GateMessage("请重新验证账号", "验证后本手机将成为最新授权设备") { reauthenticate() }
                is AppAccessState.ValidationError -> GateMessage("授权校验失败", state.message) { lifecycleScope.launch { coordinator.reconcile() } }
                is AppAccessState.Authorized -> Text(if (state.offline) "正在使用离线授权…" else "授权有效")
            }
            if (state !is AppAccessState.Authorized && state !is AppAccessState.Loading) {
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, DataManagementActivity::class.java)) }) { Text("导出/删除资料") }
                    OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, ContactDeveloperActivity::class.java)) }) { Text("联系客服") }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun GateMessage(title: String, detail: String, action: () -> Unit) {
        Text(title, Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleLarge)
        Text(detail, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = action) { Text(if (title.contains("¥6") || title.contains("支付")) "去支付" else "继续") }
    }

    private fun openAccount() = startActivity(Intent(this, AccountActivity::class.java))
    private fun reauthenticate() {
        lifecycleScope.launch {
            coordinator.beginReauthentication()
            openAccount()
        }
    }
    private fun startPayment() { lifecycleScope.launch { coordinator.startPayment().paymentUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }; startPaymentPolling() } }

    private companion object { const val KEY_PENDING_INTENT = "pending_inbound_intent" }
}
