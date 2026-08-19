package com.lightningstudio.watchrss.phone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.account.AppAccessState
import com.lightningstudio.watchrss.phone.account.RemoteEnvironment
import com.lightningstudio.watchrss.phone.account.RemoteEnvironmentStore
import com.lightningstudio.watchrss.phone.onboarding.OnboardingProfileBuilder
import com.lightningstudio.watchrss.phone.onboarding.OnboardingProfileStore
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The only exported router. No protected activity is entered before app access is valid. */
class MainActivity : ComponentActivity() {
    private val coordinator get() = (application as PhoneCompanionApplication).container.appAccessCoordinator
    private var pendingInbound: Intent? = null
    private var pollJob: Job? = null
    private lateinit var privacyConsentStore: PhonePrivacyConsentStore
    private val oobeComplete = mutableStateOf(false)
    private var oobeLaunchPending = false
    private val oobeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        oobeLaunchPending = false
        oobeComplete.value = privacyConsentStore.isOobeComplete()
        if (oobeComplete.value) {
            lifecycleScope.launch { coordinator.reconcile() }
        } else {
            launchOobeIfRequired()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        privacyConsentStore = PhonePrivacyConsentStore(this)
        oobeComplete.value = privacyConsentStore.isOobeComplete()
        pendingInbound = if (android.os.Build.VERSION.SDK_INT >= 33) {
            savedInstanceState?.getParcelable(KEY_PENDING_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") savedInstanceState?.getParcelable(KEY_PENDING_INTENT)
        } ?: Intent(intent)
        setContent {
            WatchRssPhoneTheme {
                val state by coordinator.state.collectAsState()
                val onboardingComplete = oobeComplete.value
                LaunchedEffect(state, onboardingComplete) {
                    if (onboardingComplete && state is AppAccessState.Authorized) {
                        enterApplication()
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    if (onboardingComplete) {
                        AccessGate(state)
                    } else {
                        OobeLoadingGate()
                    }
                }
            }
        }
        if (oobeComplete.value) {
            lifecycleScope.launch { coordinator.reconcile() }
        } else {
            launchOobeIfRequired()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!(intent.data?.scheme == "watchrss" && intent.data?.host == "payment-return")) {
            pendingInbound = Intent(intent)
        }
        if (oobeComplete.value) lifecycleScope.launch { coordinator.reconcile() }
    }

    override fun onResume() {
        super.onResume()
        oobeComplete.value = privacyConsentStore.isOobeComplete()
        if (!oobeComplete.value) {
            launchOobeIfRequired()
            return
        }
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

    private fun launchOobeIfRequired() {
        if (oobeLaunchPending || privacyConsentStore.isOobeComplete()) return
        oobeLaunchPending = true
        oobeLauncher.launch(PhoneOobeActivity.createIntent(this))
    }

    @androidx.compose.runtime.Composable
    private fun OobeLoadingGate() {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("腕上RSS", style = MaterialTheme.typography.headlineMedium)
            CircularProgressIndicator(Modifier.padding(24.dp))
            Text("正在准备首次使用引导")
        }
    }

    @androidx.compose.runtime.Composable
    private fun AccessGate(state: AppAccessState) {
        var showPaymentAgreement by remember { mutableStateOf(false) }
        var showTrialConfirmation by remember { mutableStateOf(false) }
        var trialStarting by remember { mutableStateOf(false) }
        if (showPaymentAgreement) {
            PaidServiceAgreementDialog(
                onOpenAgreement = {
                    startActivity(
                        LegalDocumentActivity.createIntent(
                            this@MainActivity,
                            LegalDocument.PAID_SERVICE_AGREEMENT
                        )
                    )
                },
                onConfirm = {
                    showPaymentAgreement = false
                    startPaymentAfterAgreement()
                },
                onDismiss = { showPaymentAgreement = false }
            )
        }
        if (showTrialConfirmation) {
            AlertDialog(
                onDismissRequest = { if (!trialStarting) showTrialConfirmation = false },
                title = { Text("开始 3 天免费试用？") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("试用从领取成功起连续 72 小时，仅绑定当前手机，每个账号只能领取一次。")
                        Text("预计结束时间：${trialEstimatedEndText(System.currentTimeMillis())}")
                        Text("试用到期后会返回购买页面，本地文章、订阅、收藏和稍后读不会被删除。")
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !trialStarting,
                        onClick = {
                            trialStarting = true
                            startTrialAfterConfirmation {
                                trialStarting = false
                                showTrialConfirmation = false
                            }
                        }
                    ) { Text(if (trialStarting) "正在领取…" else "确认开始") }
                },
                dismissButton = {
                    TextButton(
                        enabled = !trialStarting,
                        onClick = { showTrialConfirmation = false }
                    ) { Text("取消") }
                }
            )
        }
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("腕上RSS", style = MaterialTheme.typography.headlineMedium)
            when (state) {
                AppAccessState.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                AppAccessState.LoggedOut -> GateMessage("首次使用请登录", null) { openAccount() }
                is AppAccessState.PurchaseRequired -> {
                    PhoneValuePreview()
                    val copy = OnboardingProfileBuilder.paywallCopyFor(
                        state.summary,
                        OnboardingProfileStore(this@MainActivity).load()
                    )
                    GateMessage(
                        title = copy.title,
                        detail = copy.detail,
                        actionLabel = "立即购买 ¥6"
                    ) { showPaymentAgreement = true }
                    if (state.summary.trialEligible) {
                        OutlinedButton(
                            onClick = { showTrialConfirmation = true },
                            modifier = Modifier.padding(top = 12.dp)
                        ) { Text("免费试用 3 天") }
                    }
                }
                is AppAccessState.PaymentPending -> GateMessage("等待支付确认", "订单 ${state.order.merchantOrderId}") {
                    state.order.paymentUrl?.let { openExternally(it) }
                }
                is AppAccessState.Revoked -> GateMessage("本手机授权已撤销", state.summary.revokeReason ?: "设备名额已被较新的登录占用") { reauthenticate() }
                is AppAccessState.ReauthenticationRequired -> GateMessage("请重新验证账号", "验证后本手机将成为最新授权设备") { reauthenticate() }
                is AppAccessState.ValidationError -> GateMessage("授权校验失败", state.message) { lifecycleScope.launch { coordinator.reconcile() } }
                is AppAccessState.Authorized -> Text(if (state.offline) "正在使用离线授权…" else "授权有效")
            }
            val remoteEnvironment = RemoteEnvironmentStore(this@MainActivity).active()
            if (shouldShowProductionEnvironmentSwitch(BuildConfig.DEBUG, remoteEnvironment)) {
                OutlinedButton(
                    onClick = ::switchToProductionEnvironment,
                    modifier = Modifier.padding(top = 20.dp)
                ) { Text("切换到生产环境") }
                Text(
                    "当前为测试环境；切换后请重新打开应用。",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state !is AppAccessState.Authorized && state !is AppAccessState.Loading) {
                OutlinedButton(
                    onClick = { startActivity(Intent(this@MainActivity, ContactDeveloperActivity::class.java)) },
                    modifier = Modifier.padding(top = 20.dp)
                ) { Text("联系客服") }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PhoneValuePreview() {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = "你的手机阅读中枢",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "首页概览、RSS 与小说集中管理，阅读状态与手表保持同步。",
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ValuePreviewRow("多端资料库", "收藏、稍后读与文章双向同步")
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                ValuePreviewRow("沉浸阅读器", "字体、背景、自动滚动与 AI 总结")
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                ValuePreviewRow("手机端管理", "订阅整理、网页导入与账号安全")
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ValuePreviewRow(title: String, detail: String) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = detail,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun GateMessage(
        title: String,
        detail: String?,
        actionLabel: String = "继续",
        action: () -> Unit
    ) {
        Text(title, Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleLarge)
        detail?.let {
            Text(it, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = action) { Text(actionLabel) }
    }

    private fun openAccount() = startActivity(AccountActivity.createIntent(this, finishAfterLogin = true))
    private fun reauthenticate() {
        lifecycleScope.launch {
            coordinator.beginReauthentication()
            openAccount()
        }
    }
    private fun switchToProductionEnvironment() {
        if (!BuildConfig.DEBUG) return
        if (RemoteEnvironmentStore(this).select(RemoteEnvironment.PRODUCTION)) {
            (application as PhoneCompanionApplication).restartAfterRemoteEnvironmentChange()
        }
    }
    private fun startPaymentAfterAgreement() {
        lifecycleScope.launch {
            runCatching { coordinator.startPayment(agreementAccepted = true) }
                .onSuccess { order ->
                    order.paymentUrl?.let { openExternally(it) }
                    startPaymentPolling()
                }
                .onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        it.message ?: "订单创建失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun startTrialAfterConfirmation(onComplete: () -> Unit) {
        lifecycleScope.launch {
            runCatching { coordinator.startTrial() }
                .onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        it.message ?: "试用领取失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            onComplete()
        }
    }

    private companion object { const val KEY_PENDING_INTENT = "pending_inbound_intent" }
}

internal fun trialEstimatedEndText(nowMillis: Long): String = DateFormat
    .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    .format(Date(nowMillis + 72L * 60L * 60L * 1_000L))

internal fun shouldShowProductionEnvironmentSwitch(
    isDebugBuild: Boolean,
    remoteEnvironment: RemoteEnvironment
): Boolean = isDebugBuild && remoteEnvironment == RemoteEnvironment.TEST
