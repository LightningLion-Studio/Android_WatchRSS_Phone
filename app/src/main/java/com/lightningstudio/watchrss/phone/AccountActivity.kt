package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.account.AccountLoginAction
import com.lightningstudio.watchrss.phone.account.AccountDeletionResult
import com.lightningstudio.watchrss.phone.account.AccountSecurityStatus
import com.lightningstudio.watchrss.phone.account.AppAccessState
import com.lightningstudio.watchrss.phone.account.AppPaymentOrder
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhonePasskeyCoordinator
import com.lightningstudio.watchrss.phone.account.LoginProgress
import com.lightningstudio.watchrss.phone.account.RegisteredPasskey
import com.lightningstudio.watchrss.phone.account.TotpEnrollment
import com.lightningstudio.watchrss.phone.account.TotpFactor
import com.lightningstudio.watchrss.phone.account.accountLoginErrorMessage
import com.lightningstudio.watchrss.phone.account.accountSecurityErrorMessage
import com.lightningstudio.watchrss.phone.cloud.CloudAccountPanel
import com.lightningstudio.watchrss.phone.tips.TipEvents
import com.lightningstudio.watchrss.phone.tips.TipIds
import com.lightningstudio.watchrss.phone.tips.ui.LocalTipManager
import com.lightningstudio.watchrss.phone.tips.ui.TipOverlayHost
import com.lightningstudio.watchrss.phone.tips.ui.tipAnchor
import com.lightningstudio.watchrss.phone.cloud.CloudMemberState
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncService
import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveReaderOpenThreePane
import com.lightningstudio.watchrss.phone.ui.AdaptiveReaderReturnThreePane
import com.lightningstudio.watchrss.phone.ui.AdaptiveTwoPane
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.SupportContactInlineFooter
import com.lightningstudio.watchrss.phone.ui.generateQRCode
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AccountActivity : ComponentActivity() {
    private var screenStartedAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PhoneCompanionApplication).container
        val accountRepository = container.accountRepository
        val passkeyCoordinator = PhonePasskeyCoordinator(this, accountRepository)
        val finishAfterLogin = intent.getBooleanExtra(EXTRA_FINISH_AFTER_LOGIN, false)

        setContent {
            WatchRssPhoneTheme {
                val rssSources by container.repository.observeRssSources()
                    .collectAsState(initial = emptyList())
                TipOverlayHost(
                    tipManager = container.tipManager
                ) {
                AccountScreen(
                    accountRepository = accountRepository,
                    cloudSyncService = container.cloudSyncService,
                    rssSources = rssSources,
                    usageTelemetry = container.usageTelemetry,
                    onBack = ::finish,
                    onLoginComplete = {
                        if (finishAfterLogin) {
                            lifecycleScope.launch {
                                container.appAccessCoordinator.reconcile()
                                finish()
                            }
                        }
                    },
                    loginWithPasskey = passkeyCoordinator::login,
                    createPasskey = passkeyCoordinator::createPasskey,
                    runAction = { action ->
                        lifecycleScope.launch { action() }
                    }
                )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        screenStartedAt = SystemClock.elapsedRealtime()
        (application as PhoneCompanionApplication).container.usageTelemetry.recordScreenOpen("phone_account")
    }

    override fun onPause() {
        super.onPause()
        val startedAt = screenStartedAt
        if (startedAt > 0L) {
            (application as PhoneCompanionApplication).container.usageTelemetry.recordScreenDuration(
                "phone_account",
                SystemClock.elapsedRealtime() - startedAt
            )
            screenStartedAt = 0L
        }
    }

    companion object {
        private const val EXTRA_FINISH_AFTER_LOGIN =
            "com.lightningstudio.watchrss.phone.extra.FINISH_AFTER_LOGIN"

        fun createIntent(context: Context, finishAfterLogin: Boolean = false): Intent =
            Intent(context, AccountActivity::class.java).apply {
                putExtra(EXTRA_FINISH_AFTER_LOGIN, finishAfterLogin)
            }
    }
}

private enum class AccountPage {
    ROOT,
    CLOUD_SYNC
}

private enum class AccountPaneTransition {
    OPENING_CLOUD,
    CLOSING_CLOUD
}

private enum class LoginMethod(val label: String, val supportingText: String, val factorName: String) {
    PASSWORD("密码", "使用账号密码验证。", "password"),
    OTP("验证码", "验证码将发送至此手机号。", "sms"),
    TOTP("TOTP 验证器", "输入验证器中的 6 位动态验证码。", "totp"),
    PASSKEY("通行密钥", "使用指纹、人脸或设备屏幕锁。", "passkey")
}

private enum class SecurityVerificationPurpose {
    DISABLE_TWO_FACTOR,
    UPDATE_PASSWORD,
    REFUND_ORDER,
    DELETE_ACCOUNT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    accountRepository: PhoneAccountRepository,
    cloudSyncService: PhoneCloudSyncService,
    rssSources: List<com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity>,
    usageTelemetry: PhoneUsageTelemetry,
    onBack: () -> Unit,
    onLoginComplete: () -> Unit,
    loginWithPasskey: suspend (String, String?) -> LoginProgress,
    createPasskey: suspend () -> Unit,
    runAction: (suspend () -> Unit) -> Unit,
    leadingPane: (@Composable () -> Unit)? = null
) {
    val session by accountRepository.session.collectAsState()
    val context = LocalContext.current
    val tipManager = LocalTipManager.current
    val accessCoordinator = (context.applicationContext as PhoneCompanionApplication).container.appAccessCoordinator
    val appAccessState by accessCoordinator.state.collectAsState()
    val cloudSyncState by cloudSyncService.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var page by rememberSaveable(session?.userId) { mutableStateOf(AccountPage.ROOT) }
    var paneTransition by remember { mutableStateOf<AccountPaneTransition?>(null) }
    var paneTransitionProgress by remember { mutableStateOf(1f) }
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var passwordTotp by remember { mutableStateOf("") }
    var loginProgress by remember { mutableStateOf<LoginProgress?>(null) }
    var loginMethod by remember { mutableStateOf(LoginMethod.OTP) }
    var loginMethodMenuExpanded by remember { mutableStateOf(false) }
    var otpResendAvailableAt by rememberSaveable { mutableLongStateOf(0L) }
    var elapsedRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var registeredPasskeys by remember(session?.userId) {
        mutableStateOf<List<RegisteredPasskey>>(emptyList())
    }
    var passkeysLoading by remember(session?.userId) { mutableStateOf(session != null) }
    var renameTarget by remember(session?.userId) {
        mutableStateOf<RegisteredPasskey?>(null)
    }
    var renameValue by remember(session?.userId) { mutableStateOf("") }
    var deleteTarget by remember(session?.userId) {
        mutableStateOf<RegisteredPasskey?>(null)
    }
    var totpFactors by remember(session?.userId) { mutableStateOf<List<TotpFactor>>(emptyList()) }
    var totpLoading by remember(session?.userId) { mutableStateOf(session != null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    var passwordDialogError by remember { mutableStateOf<String?>(null) }
    var totpEnrollment by remember { mutableStateOf<TotpEnrollment?>(null) }
    var enrollmentCode by remember { mutableStateOf("") }
    var enrollmentError by remember { mutableStateOf<String?>(null) }
    var disableTotpTarget by remember { mutableStateOf<TotpFactor?>(null) }
    var disableTotpCode by remember { mutableStateOf("") }
    var disableTotpError by remember { mutableStateOf<String?>(null) }
    var securityStatus by remember(session?.userId) { mutableStateOf<AccountSecurityStatus?>(null) }
    var securityLoading by remember(session?.userId) { mutableStateOf(session != null) }
    var pendingAutoEnableTwoFactor by remember { mutableStateOf(false) }
    var showAddMethodDialog by remember { mutableStateOf(false) }
    var showDisableMethodDialog by remember { mutableStateOf(false) }
    var securityVerificationMethod by remember { mutableStateOf<LoginMethod?>(null) }
    var securityVerificationProgress by remember { mutableStateOf<LoginProgress?>(null) }
    var securityVerificationInput by remember { mutableStateOf("") }
    var securityVerificationError by remember { mutableStateOf<String?>(null) }
    var securityVerificationPurpose by remember { mutableStateOf<SecurityVerificationPurpose?>(null) }
    var passwordVerificationToken by remember { mutableStateOf<String?>(null) }
    var paymentOrders by remember(session?.userId) { mutableStateOf<List<AppPaymentOrder>>(emptyList()) }
    var paymentOrdersLoading by remember(session?.userId) { mutableStateOf(session != null) }
    var showPaymentAgreement by remember { mutableStateOf(false) }
    var pendingRefundOrder by remember { mutableStateOf<AppPaymentOrder?>(null) }
    var refundVerificationToken by remember { mutableStateOf<String?>(null) }
    var deleteVerificationToken by remember { mutableStateOf<String?>(null) }
    var accountDeletionResult by remember { mutableStateOf<AccountDeletionResult?>(null) }
    val canManageTotp = session != null

    LaunchedEffect(session?.userId) {
        if (session == null) {
            registeredPasskeys = emptyList()
            passkeysLoading = false
            return@LaunchedEffect
        }
        passkeysLoading = true
        val result = runCatching { accountRepository.listRegisteredPasskeys() }
        result.onSuccess { registeredPasskeys = it }
            .onFailure { error = it.message ?: "已有 Passkey 加载失败" }
        passkeysLoading = false
    }

    LaunchedEffect(session?.userId) {
        if (session == null) {
            paymentOrders = emptyList()
            paymentOrdersLoading = false
            return@LaunchedEffect
        }
        paymentOrdersLoading = true
        runCatching { accountRepository.paymentOrders() }
            .onSuccess { paymentOrders = it }
            .onFailure { error = it.message ?: "订单加载失败" }
        paymentOrdersLoading = false
    }

    LaunchedEffect(session?.userId, paymentOrders.map { it.orderId to it.status }) {
        if (session == null || paymentOrders.none { it.status == "refund_pending" }) {
            return@LaunchedEffect
        }
        repeat(120) {
            delay(5_000)
            val refreshed = paymentOrders.map { order ->
                if (order.status == "refund_pending") {
                    runCatching { accountRepository.paymentOrder(order.orderId) }.getOrDefault(order)
                } else {
                    order
                }
            }
            paymentOrders = refreshed
            if (refreshed.none { it.status == "refund_pending" }) {
                accessCoordinator.reconcile()
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(session?.userId, canManageTotp) {
        if (session == null) {
            totpFactors = emptyList()
            totpLoading = false
            return@LaunchedEffect
        }
        if (!canManageTotp) {
            totpFactors = emptyList()
            totpLoading = false
            return@LaunchedEffect
        }
        totpLoading = true
        runCatching { accountRepository.listTotpFactors() }
            .onSuccess { totpFactors = it }
            .onFailure { error = accountSecurityErrorMessage(it, "两步验证状态加载失败") }
        totpLoading = false
    }

    LaunchedEffect(session?.userId) {
        if (session == null) {
            securityStatus = null
            securityLoading = false
            return@LaunchedEffect
        }
        securityLoading = true
        runCatching { accountRepository.securityStatus() }
            .onSuccess { securityStatus = it }
            .onFailure { error = it.message ?: "账号安全状态加载失败" }
        securityLoading = false
    }

    LaunchedEffect(otpResendAvailableAt) {
        while (SystemClock.elapsedRealtime() < otpResendAvailableAt) {
            elapsedRealtime = SystemClock.elapsedRealtime()
            delay(1_000)
        }
        elapsedRealtime = SystemClock.elapsedRealtime()
    }

    val otpResendSecondsRemaining = ((otpResendAvailableAt - elapsedRealtime + 999L) / 1_000L)
        .coerceAtLeast(0L)

    suspend fun refreshSecurityAndMaybeEnable(): Boolean {
        val refreshed = accountRepository.securityStatus()
        securityStatus = refreshed
        if (!shouldAutoEnableTwoFactor(pendingAutoEnableTwoFactor, refreshed)) return false
        accountRepository.setTwoFactorEnabled(true)
        pendingAutoEnableTwoFactor = false
        message = "两步验证已开启，请重新登录"
        return true
    }

    suspend fun enableTwoFactor() {
        val current = accountRepository.securityStatus().also { securityStatus = it }
        if (current.availableMethodCount < 2) {
            pendingAutoEnableTwoFactor = true
            showAddMethodDialog = true
            return
        }
        accountRepository.setTwoFactorEnabled(true)
        message = "两步验证已开启，请重新登录"
    }

    suspend fun finishSecurityVerification(progress: LoginProgress) {
        val token = progress.verificationToken
        if (token == null) {
            securityVerificationProgress = progress
            securityVerificationMethod = null
            securityVerificationInput = ""
            showDisableMethodDialog = true
            message = "还需要一种不同的验证方式"
            return
        }
        when (securityVerificationPurpose) {
            SecurityVerificationPurpose.DISABLE_TWO_FACTOR -> {
                securityStatus = accountRepository.setTwoFactorEnabled(false, token)
                message = "两步验证已关闭"
            }
            SecurityVerificationPurpose.UPDATE_PASSWORD -> {
                passwordVerificationToken = token
                newPassword = ""
                confirmPassword = ""
                newPasswordVisible = false
                confirmPasswordVisible = false
                passwordDialogError = null
                showPasswordDialog = true
            }
            SecurityVerificationPurpose.REFUND_ORDER -> {
                refundVerificationToken = token
            }
            SecurityVerificationPurpose.DELETE_ACCOUNT -> {
                deleteVerificationToken = token
            }
            null -> error("安全验证用途缺失")
        }
        showDisableMethodDialog = false
        securityVerificationMethod = null
        securityVerificationProgress = null
        securityVerificationInput = ""
    }

    val accountControls: @Composable ColumnScope.(() -> Unit) -> Unit = { onCloudSyncClick ->
        if (session == null) {
            Text(
                "登录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "输入手机号以继续。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (loginProgress == null) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = {
                        phone = it
                        loginProgress = null
                        otpResendAvailableAt = 0L
                    },
                    label = { Text("手机号") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentType = ContentType.PhoneNumber }
                )
            }
            fun applyLoginProgress(progress: LoginProgress) {
                loginProgress = progress
                progress.session?.let { signedIn ->
                    message = "登录成功"
                    usageTelemetry.recordAccountSignedIn(signedIn.userId)
                    tipManager?.recordEvent(TipEvents.ACCOUNT_SIGNED_IN)
                    onLoginComplete()
                } ?: run {
                    message = "已完成一种验证方式，请再选择另一种不同方式。"
                }
            }
            val loginMethodPicker: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier = modifier) {
                    OutlinedButton(
                        onClick = { loginMethodMenuExpanded = true },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tipAnchor(TipIds.PASSKEY_LOGIN)
                    ) {
                        Text("${loginMethod.label}登录", modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换登录方式")
                    }
                    DropdownMenu(
                        expanded = loginMethodMenuExpanded,
                        onDismissRequest = { loginMethodMenuExpanded = false }
                    ) {
                        LoginMethod.entries.forEach { method ->
                            DropdownMenuItem(
                                text = { Text(method.label) },
                                enabled = method.factorName !in (loginProgress?.completedFactors ?: emptyList()),
                                onClick = {
                                    loginMethod = method
                                    loginMethodMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
            val loginHint: @Composable () -> Unit = {
                Text(
                    loginMethod.supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                loginProgress?.takeIf { !it.complete }?.let { progress ->
                    Text(
                        if (progress.requiredFactorCount > progress.completedFactors.size) {
                            "已完成 ${progress.completedFactors.size} 项验证；请选择另一种方式继续。"
                        } else {
                            "正在完成登录验证。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            when (loginMethod) {
                LoginMethod.PASSWORD -> {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password }
                    )
                    loginHint()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        loginMethodPicker(Modifier.weight(1f))
                        Button(
                            onClick = {
                                runAction {
                                    busy = true
                                    error = null
                                    runCatching {
                                        val progress = loginProgress ?: accountRepository.startLogin(phone)
                                        accountRepository.loginWithPasswordFactor(progress.transactionId, password)
                                    }.onSuccess(::applyLoginProgress)
                                        .onFailure { error = it.message ?: "密码验证失败" }
                                    busy = false
                                }
                            },
                            enabled = !busy && password.length >= 10 && (phone.isNotBlank() || loginProgress != null),
                            modifier = Modifier.weight(1f)
                        ) { Text("继续") }
                    }
                }
                LoginMethod.OTP -> {
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                        label = { Text("验证码") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    runAction {
                                        busy = true
                                        error = null
                                        runCatching {
                                            val progress = loginProgress ?: accountRepository.startLogin(phone)
                                            loginProgress = progress
                                            accountRepository.requestPhoneOtpFactor(progress.transactionId)
                                        }.onSuccess {
                                            otpResendAvailableAt = SystemClock.elapsedRealtime() + 60_000L
                                            message = "验证码已发送"
                                        }.onFailure {
                                            Log.w(ACCOUNT_LOG_TAG, "Phone OTP request failed", it)
                                            error = accountLoginErrorMessage(AccountLoginAction.REQUEST_OTP, it)
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy && (phone.isNotBlank() || loginProgress != null) && otpResendSecondsRemaining == 0L
                            ) { Text(if (otpResendSecondsRemaining > 0) "${otpResendSecondsRemaining}s 后重发" else "发送验证码") }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentType = ContentType.SmsOtpCode }
                    )
                    Text(
                        "发送验证码，请输入来自杭州绳匠科技有限公司的短信验证码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    loginHint()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        loginMethodPicker(Modifier.weight(1f))
                        Button(
                            onClick = {
                                runAction {
                                    busy = true
                                    error = null
                                    runCatching {
                                        val progress = loginProgress ?: accountRepository.startLogin(phone)
                                        accountRepository.verifyPhoneOtpFactor(progress.transactionId, otp)
                                    }.onSuccess(::applyLoginProgress)
                                        .onFailure { error = accountLoginErrorMessage(AccountLoginAction.VERIFY_OTP, it) }
                                    busy = false
                                }
                            },
                            enabled = !busy && otp.length == 6 && (phone.isNotBlank() || loginProgress != null),
                            modifier = Modifier.weight(1f)
                        ) { Text("继续") }
                    }
                }
                LoginMethod.TOTP -> {
                    OutlinedTextField(
                        value = passwordTotp,
                        onValueChange = { passwordTotp = it.filter(Char::isDigit).take(6) },
                        label = { Text("验证器动态码") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    loginHint()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        loginMethodPicker(Modifier.weight(1f))
                        Button(
                            onClick = {
                                runAction {
                                    busy = true
                                    error = null
                                    runCatching {
                                        val progress = loginProgress ?: accountRepository.startLogin(phone)
                                        accountRepository.verifyTotpFactor(
                                            progress.transactionId,
                                            passwordTotp
                                        )
                                    }
                                        .onSuccess(::applyLoginProgress)
                                        .onFailure { error = it.message ?: "动态验证码无效" }
                                    busy = false
                                }
                            },
                            enabled = !busy && passwordTotp.length == 6 &&
                                (phone.isNotBlank() || loginProgress != null),
                            modifier = Modifier.weight(1f)
                        ) { Text("继续") }
                    }
                }
                LoginMethod.PASSKEY -> {
                    loginHint()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        loginMethodPicker(Modifier.weight(1f))
                        Button(
                            onClick = {
                                runAction {
                                    busy = true
                                    error = null
                                    runCatching {
                                        val progress = loginProgress ?: accountRepository.startLogin(phone)
                                        loginWithPasskey(phone, progress.transactionId)
                                    }.onSuccess(::applyLoginProgress)
                                        .onFailure {
                                            Log.w(ACCOUNT_LOG_TAG, "Passkey login failed", it)
                                            error = accountLoginErrorMessage(AccountLoginAction.PASSKEY_LOGIN, it)
                                        }
                                    busy = false
                                }
                            },
                            enabled = !busy && (phone.isNotBlank() || loginProgress != null),
                            modifier = Modifier.weight(1f)
                        ) { Text("继续") }
                    }
                }
            }
        } else {
            Text(
                "Passkey",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "创建后可使用指纹、人脸或设备屏幕锁登录，无需短信验证码。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when {
                passkeysLoading -> Text(
                    "正在读取已有 Passkey…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                registeredPasskeys.isEmpty() -> Text(
                    "尚未创建 Passkey",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Text(
                        "已有 Passkey（${registeredPasskeys.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    registeredPasskeys.forEach { passkey ->
                        RegisteredPasskeyCard(
                            passkey = passkey,
                            enabled = !busy,
                            onRename = {
                                renameTarget = passkey
                                renameValue = passkey.displayName.ifBlank { "Passkey" }
                            },
                            onDelete = { deleteTarget = passkey }
                        )
                    }
                }
            }
            Button(
                onClick = {
                    runAction {
                        busy = true
                        error = null
                        message = null
                        val creation = runCatching { createPasskey() }
                        if (creation.isSuccess) {
                            message = "Passkey 创建成功"
                            runCatching { accountRepository.listRegisteredPasskeys() }
                                .onSuccess { registeredPasskeys = it }
                            runCatching { refreshSecurityAndMaybeEnable() }
                                .onFailure { error = accountSecurityErrorMessage(it, "两步验证开启失败") }
                        } else {
                            error = creation.exceptionOrNull()?.message ?: "Passkey 创建失败"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (registeredPasskeys.isEmpty()) "创建 Passkey" else "添加其他 Passkey")
            }
            if (registeredPasskeys.isNotEmpty()) {
                Text(
                    "当前设备已有时，请在系统窗口中选择其他密码管理器或其他设备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("密码与账号安全", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedButton(
                onClick = {
                    securityVerificationPurpose = SecurityVerificationPurpose.UPDATE_PASSWORD
                    securityVerificationProgress = null
                    securityVerificationMethod = null
                    securityVerificationError = null
                    passwordVerificationToken = null
                    showDisableMethodDialog = true
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("设置或修改密码") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("两步验证", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            securityLoading -> "正在读取状态…"
                            securityStatus?.twoFactorEnabled == true ->
                                "已开启 · 登录需要两种不同验证方式"
                            else -> "未开启 · 当前有 ${securityStatus?.availableMethodCount ?: 0} 种验证方式"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = securityStatus?.twoFactorEnabled == true,
                    enabled = !busy && !securityLoading && securityStatus != null,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            runAction {
                                busy = true
                                error = null
                                runCatching { enableTwoFactor() }
                                    .onFailure { error = accountSecurityErrorMessage(it, "无法开启两步验证") }
                                busy = false
                            }
                        } else {
                            securityVerificationPurpose = SecurityVerificationPurpose.DISABLE_TWO_FACTOR
                            securityVerificationProgress = null
                            securityVerificationMethod = null
                            showDisableMethodDialog = true
                            securityVerificationError = null
                        }
                    }
                )
            }
            Text(
                "可用方式：${securityStatus?.availableMethods?.map(::securityMethodLabel)?.joinToString("、") ?: "正在读取"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("TOTP 验证器", fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    totpLoading -> "正在读取状态…"
                    totpFactors.any { it.verified } -> "已绑定 ${totpFactors.count { it.verified }} 个验证器"
                    else -> "尚未绑定验证器"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            totpFactors.filter { it.verified }.forEach { factor ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(factor.friendlyName.ifBlank { "验证器" }, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            disableTotpTarget = factor
                            disableTotpCode = ""
                            disableTotpError = null
                        },
                        enabled = !busy
                    ) { Text("移除") }
                }
            }
            OutlinedButton(
                onClick = {
                    runAction {
                        busy = true
                        enrollmentError = null
                        runCatching { accountRepository.beginTotpEnrollment() }
                            .onSuccess { totpEnrollment = it; enrollmentCode = "" }
                            .onFailure { error = it.message ?: "无法添加验证器" }
                        busy = false
                    }
                },
                enabled = !busy && !totpLoading,
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加 TOTP 验证器") }
            if (shouldShowCloudSyncEntry(BuildConfig.DEBUG)) {
                CloudSyncEntryCard(
                    summary = cloudSyncMenuSummary(
                        member = cloudSyncState.member,
                        hasLocalKey = cloudSyncService.hasLocalAccountKey()
                    ),
                    enabled = !busy && appAccessState is AppAccessState.Authorized,
                    onClick = onCloudSyncClick
                )
            }
            OutlinedButton(
                onClick = {
                    runAction {
                        busy = true
                        runCatching { accessCoordinator.logout() }
                            .onSuccess { released ->
                                message = if (released) "已退出登录并释放名额" else "已在本机退出；名额将在联网后释放"
                            }
                            .onFailure { error = it.message ?: "退出失败" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("退出登录")
            }
            OutlinedButton(
                onClick = {
                    securityVerificationPurpose = SecurityVerificationPurpose.DELETE_ACCOUNT
                    securityVerificationProgress = null
                    securityVerificationMethod = null
                    securityVerificationError = null
                    deleteVerificationToken = null
                    showDisableMethodDialog = true
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("注销账号")
            }
        }
    }

    fun completePaneTransition(activeTransition: AccountPaneTransition) {
        paneTransition = activeTransition
        paneTransitionProgress = 0f
        coroutineScope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 480,
                    easing = FastOutSlowInEasing
                )
            ) { value, _ ->
                paneTransitionProgress = value
            }
            if (paneTransition == activeTransition) {
                paneTransition = null
                paneTransitionProgress = 1f
            }
        }
    }

    fun openCloudSync() {
        if (page == AccountPage.CLOUD_SYNC || paneTransition != null) return
        page = AccountPage.CLOUD_SYNC
        if (leadingPane != null) {
            completePaneTransition(AccountPaneTransition.OPENING_CLOUD)
        }
    }

    fun leaveCloudSync() {
        if (page != AccountPage.CLOUD_SYNC || paneTransition != null) return
        page = AccountPage.ROOT
        if (leadingPane != null) {
            completePaneTransition(AccountPaneTransition.CLOSING_CLOUD)
        }
    }

    BackHandler(enabled = true) {
        if (paneTransition != null) return@BackHandler
        if (page == AccountPage.CLOUD_SYNC) leaveCloudSync() else onBack()
    }

    if (showAddMethodDialog) {
        val missingMethodNames = missingTwoFactorMethods(
            securityStatus?.availableMethods ?: emptySet()
        )
        val missingMethods = LoginMethod.entries.filter { it.factorName in missingMethodNames }
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    showAddMethodDialog = false
                    pendingAutoEnableTwoFactor = false
                }
            },
            title = { Text("还需要一种验证方式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("开启两步验证前，账号必须至少有两种不同的验证方式。添加成功后将自动开启。")
                    missingMethods.forEach { method ->
                        OutlinedButton(
                            onClick = {
                                showAddMethodDialog = false
                                when (method) {
                                    LoginMethod.PASSWORD -> {
                                        securityVerificationPurpose = SecurityVerificationPurpose.UPDATE_PASSWORD
                                        securityVerificationProgress = null
                                        securityVerificationMethod = null
                                        securityVerificationError = null
                                        passwordVerificationToken = null
                                        showDisableMethodDialog = true
                                    }
                                    LoginMethod.TOTP -> runAction {
                                        busy = true
                                        enrollmentError = null
                                        runCatching { accountRepository.beginTotpEnrollment() }
                                            .onSuccess { totpEnrollment = it; enrollmentCode = "" }
                                            .onFailure {
                                                pendingAutoEnableTwoFactor = false
                                                error = it.message ?: "无法添加验证器"
                                            }
                                        busy = false
                                    }
                                    LoginMethod.PASSKEY -> runAction {
                                        busy = true
                                        runCatching {
                                            createPasskey()
                                            registeredPasskeys = accountRepository.listRegisteredPasskeys()
                                            refreshSecurityAndMaybeEnable()
                                        }.onFailure {
                                            pendingAutoEnableTwoFactor = false
                                            error = it.message ?: "Passkey 创建失败"
                                        }
                                        busy = false
                                    }
                                    LoginMethod.OTP -> Unit
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("添加${method.label}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddMethodDialog = false
                        pendingAutoEnableTwoFactor = false
                    },
                    enabled = !busy
                ) { Text("取消") }
            }
        )
    }

    if (showDisableMethodDialog) {
        val purpose = securityVerificationPurpose
        val completedFactors = securityVerificationProgress?.completedFactors.orEmpty()
        val methods = LoginMethod.entries.filter {
            it.factorName in (securityStatus?.availableMethods ?: emptySet()) &&
                it.factorName !in completedFactors
        }
        AlertDialog(
            onDismissRequest = { if (!busy) showDisableMethodDialog = false },
            title = {
                Text(
                    when (purpose) {
                        SecurityVerificationPurpose.UPDATE_PASSWORD -> "修改密码前验证身份"
                        SecurityVerificationPurpose.DISABLE_TWO_FACTOR -> "关闭两步验证"
                        SecurityVerificationPurpose.REFUND_ORDER -> "退款前验证身份"
                        SecurityVerificationPurpose.DELETE_ACCOUNT -> "注销账号前验证身份"
                        null -> "验证身份"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (completedFactors.isEmpty()) {
                            "请选择一种方式重新验证身份。"
                        } else {
                            "账号启用了两步验证，请再使用一种不同的方式。"
                        }
                    )
                    methods.forEach { method ->
                        OutlinedButton(
                            onClick = {
                                runAction {
                                    busy = true
                                    securityVerificationError = null
                                    runCatching {
                                        val progress = securityVerificationProgress ?: when (purpose) {
                                            SecurityVerificationPurpose.UPDATE_PASSWORD ->
                                                accountRepository.startPasswordSecurityVerification()
                                            SecurityVerificationPurpose.DISABLE_TWO_FACTOR ->
                                                accountRepository.startSecurityVerification()
                                            SecurityVerificationPurpose.REFUND_ORDER ->
                                                accountRepository.startActionSecurityVerification("refund-order")
                                            SecurityVerificationPurpose.DELETE_ACCOUNT ->
                                                accountRepository.startActionSecurityVerification("delete-account")
                                            null -> error("安全验证用途缺失")
                                        }
                                        if (method == LoginMethod.PASSKEY) {
                                            finishSecurityVerification(
                                                loginWithPasskey("", progress.transactionId)
                                            )
                                        } else {
                                            if (method == LoginMethod.OTP) {
                                                accountRepository.requestPhoneOtpFactor(progress.transactionId)
                                                message = "验证码已发送"
                                            }
                                            securityVerificationProgress = progress
                                            securityVerificationMethod = method
                                            securityVerificationInput = ""
                                            showDisableMethodDialog = false
                                        }
                                    }.onFailure {
                                        securityVerificationError = accountSecurityErrorMessage(
                                            it,
                                            "身份验证启动失败"
                                        )
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("使用${method.label}验证") }
                    }
                    securityVerificationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDisableMethodDialog = false }, enabled = !busy) {
                    Text("取消")
                }
            }
        )
    }

    securityVerificationMethod?.let { method ->
        val isCode = method == LoginMethod.OTP || method == LoginMethod.TOTP
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    securityVerificationMethod = null
                    securityVerificationProgress = null
                }
            },
            title = { Text("使用${method.label}验证") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = securityVerificationInput,
                        onValueChange = {
                            securityVerificationInput = if (isCode) {
                                it.filter(Char::isDigit).take(6)
                            } else {
                                it.take(128)
                            }
                        },
                        label = { Text(if (isCode) "6 位验证码" else "账号密码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (isCode) KeyboardType.Number else KeyboardType.Password
                        ),
                        visualTransformation = if (isCode) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = when (method) {
                            LoginMethod.OTP -> Modifier.semantics { contentType = ContentType.SmsOtpCode }
                            LoginMethod.PASSWORD -> Modifier.semantics { contentType = ContentType.Password }
                            else -> Modifier
                        }
                    )
                    if (method == LoginMethod.OTP) {
                        Text(
                            "发送验证码，请输入来自杭州绳匠科技有限公司的短信验证码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    securityVerificationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val progress = securityVerificationProgress ?: return@TextButton
                        runAction {
                            busy = true
                            securityVerificationError = null
                            runCatching {
                                val completed = when (method) {
                                    LoginMethod.PASSWORD -> accountRepository.loginWithPasswordFactor(
                                        progress.transactionId,
                                        securityVerificationInput
                                    )
                                    LoginMethod.OTP -> accountRepository.verifyPhoneOtpFactor(
                                        progress.transactionId,
                                        securityVerificationInput
                                    )
                                    LoginMethod.TOTP -> accountRepository.verifyTotpFactor(
                                        progress.transactionId,
                                        securityVerificationInput
                                    )
                                    LoginMethod.PASSKEY -> error("Passkey 使用系统验证")
                                }
                                finishSecurityVerification(completed)
                            }.onFailure {
                                securityVerificationError = accountSecurityErrorMessage(
                                    it,
                                    "身份验证失败"
                                )
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && if (isCode) {
                        securityVerificationInput.length == 6
                    } else {
                        securityVerificationInput.length >= 10
                    }
                ) {
                    Text(
                        when (securityVerificationPurpose) {
                            SecurityVerificationPurpose.DISABLE_TWO_FACTOR -> "验证并关闭"
                            else -> "继续"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        securityVerificationMethod = null
                        securityVerificationProgress = null
                    },
                    enabled = !busy
                ) { Text("取消") }
            }
        )
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    showPasswordDialog = false
                    pendingAutoEnableTwoFactor = false
                }
            },
            title = { Text("设置账号密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("使用至少 10 位的长密码；支持密码管理器生成和保存。密码不保存在应用中。")
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it.take(128) },
                        label = { Text("新密码") },
                        singleLine = true,
                        isError = newPassword.isNotEmpty() && newPassword.length < 10,
                        supportingText = {
                            if (newPassword.isNotEmpty() && newPassword.length < 10) Text("还需 ${10 - newPassword.length} 位")
                        },
                        visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                                Icon(
                                    imageVector = if (newPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (newPasswordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        modifier = Modifier.semantics { contentType = ContentType.NewPassword }
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it.take(128) },
                        label = { Text("确认密码") },
                        singleLine = true,
                        isError = confirmPassword.isNotEmpty() && confirmPassword != newPassword,
                        supportingText = {
                            if (confirmPassword.isNotEmpty() && confirmPassword != newPassword) Text("两次输入不一致")
                        },
                        visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                Icon(
                                    imageVector = if (confirmPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (confirmPasswordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        modifier = Modifier.semantics { contentType = ContentType.NewPassword }
                    )
                    passwordDialogError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runAction {
                            busy = true
                            passwordDialogError = null
                            runCatching {
                                val verificationToken = passwordVerificationToken
                                    ?: error("请先重新验证身份")
                                accountRepository.updatePassword(newPassword, verificationToken)
                                passwordVerificationToken = null
                                showPasswordDialog = false
                                pendingAutoEnableTwoFactor = false
                                message = "密码已更新，所有设备需要重新登录"
                            }
                                .onFailure { passwordDialogError = it.message ?: "密码更新失败" }
                            busy = false
                        }
                    },
                    enabled = !busy && newPassword.length >= 10 && newPassword == confirmPassword
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        pendingAutoEnableTwoFactor = false
                    },
                    enabled = !busy
                ) { Text("取消") }
            }
        )
    }

    totpEnrollment?.let { enrollment ->
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    totpEnrollment = null
                    pendingAutoEnableTwoFactor = false
                }
            },
            title = { Text("添加 TOTP 验证器") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("1. 扫描二维码", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("使用任意验证器应用扫描。动态验证码验证成功后完成绑定。")
                    val qrCode = remember(enrollment.uri) {
                        runCatching { generateQRCode(enrollment.uri, 512).asImageBitmap() }.getOrNull()
                    }
                    qrCode?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "TOTP 设置二维码",
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .widthIn(max = 280.dp)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        )
                    }
                    Text("也可以手动输入以下密钥：", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(enrollment.secret.chunked(4).joinToString(" "), fontWeight = FontWeight.Bold)
                    }
                    Text("账户名称：腕上RSS", style = MaterialTheme.typography.bodySmall)
                    Text("2. 输入动态验证码", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = enrollmentCode,
                        onValueChange = { enrollmentCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("动态验证码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    enrollmentError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runAction {
                            busy = true
                            enrollmentError = null
                            runCatching {
                                accountRepository.confirmTotpEnrollment(
                                    enrollment,
                                    enrollmentCode
                                )
                                totpFactors = accountRepository.listTotpFactors()
                                refreshSecurityAndMaybeEnable()
                            }.onSuccess { autoEnabled ->
                                totpEnrollment = null
                                enrollmentCode = ""
                                if (!autoEnabled) message = "TOTP 验证器已添加"
                            }.onFailure { enrollmentError = it.message ?: "动态验证码无效" }
                            busy = false
                        }
                    },
                    enabled = !busy && enrollmentCode.length == 6
                ) { Text("开启") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        totpEnrollment = null
                        pendingAutoEnableTwoFactor = false
                    },
                    enabled = !busy
                ) { Text("取消") }
            }
        )
    }

    disableTotpTarget?.let { factor ->
        AlertDialog(
            onDismissRequest = { if (!busy) disableTotpTarget = null },
            title = { Text("移除 TOTP 验证器？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请输入当前验证器中的 6 位动态验证码确认移除。")
                    OutlinedTextField(
                        value = disableTotpCode,
                        onValueChange = { disableTotpCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("动态验证码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    disableTotpError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runAction {
                            busy = true
                            disableTotpError = null
                            runCatching {
                                accountRepository.disableTotp(factor, disableTotpCode)
                                accountRepository.listTotpFactors()
                            }.onSuccess {
                                totpFactors = it
                                disableTotpTarget = null
                                securityStatus = accountRepository.securityStatus()
                                message = "TOTP 验证器已移除"
                            }.onFailure {
                                disableTotpError = accountSecurityErrorMessage(it, "无法移除验证器")
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && disableTotpCode.length == 6
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { disableTotpTarget = null }, enabled = !busy) { Text("取消") } }
        )
    }

    renameTarget?.let { passkey ->
        AlertDialog(
            onDismissRequest = {
                if (!busy) renameTarget = null
            },
            title = { Text("重命名 Passkey") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { value ->
                        if (value.length <= MAX_PASSKEY_DISPLAY_NAME_CHARS) {
                            renameValue = value
                        }
                    },
                    label = { Text("名称") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val displayName = renameValue.trim()
                        runAction {
                            busy = true
                            error = null
                            message = null
                            runCatching {
                                accountRepository.renameRegisteredPasskey(
                                    passkey.credentialId,
                                    displayName
                                )
                                accountRepository.listRegisteredPasskeys()
                            }.onSuccess {
                                registeredPasskeys = it
                                renameTarget = null
                                message = "Passkey 已重命名"
                            }.onFailure {
                                error = it.message ?: "Passkey 重命名失败"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && renameValue.trim().isNotEmpty()
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { renameTarget = null },
                    enabled = !busy
                ) {
                    Text("取消")
                }
            }
        )
    }

    deleteTarget?.let { passkey ->
        val displayName = passkey.displayName.ifBlank { "Passkey" }
        AlertDialog(
            onDismissRequest = {
                if (!busy) deleteTarget = null
            },
            title = { Text("删除 Passkey？") },
            text = {
                Text(
                    "删除“$displayName”后，它将立即无法用于腕上RSS登录。" +
                        "系统密码管理器中的副本需要在系统中另行删除。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runAction {
                            busy = true
                            error = null
                            message = null
                            runCatching {
                                accountRepository.deleteRegisteredPasskey(passkey.credentialId)
                                accountRepository.listRegisteredPasskeys()
                            }.onSuccess {
                                registeredPasskeys = it
                                deleteTarget = null
                                message = "Passkey 已删除"
                            }.onFailure {
                                error = accountSecurityErrorMessage(it, "Passkey 删除失败")
                            }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    enabled = !busy
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showPaymentAgreement) {
        PaidServiceAgreementDialog(
            onOpenAgreement = {
                context.startActivity(
                    LegalDocumentActivity.createIntent(
                        context,
                        LegalDocument.PAID_SERVICE_AGREEMENT
                    )
                )
            },
            onConfirm = {
                showPaymentAgreement = false
                runAction {
                    busy = true
                    runCatching { accessCoordinator.startPayment(agreementAccepted = true) }
                        .onSuccess { order ->
                            order.paymentUrl?.let { context.openExternally(it) }
                        }
                        .onFailure { error = it.message ?: "订单创建失败" }
                    busy = false
                }
            },
            onDismiss = { showPaymentAgreement = false }
        )
    }

    val refundOrder = pendingRefundOrder
    val refundToken = refundVerificationToken
    if (refundOrder != null && refundToken != null) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    pendingRefundOrder = null
                    refundVerificationToken = null
                }
            },
            title = { Text("确认七天无理由退款？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("订单 ${refundOrder.merchantOrderId} 将全额原路退回 ¥${refundOrder.amountFen / 100}。")
                    Text("退款成功后立即减少 3 台手机容量；若设备超额，系统会自动撤销最早激活的设备。该操作不可撤销。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runAction {
                            busy = true
                            runCatching {
                                accountRepository.refundPaymentOrder(
                                    refundOrder.orderId,
                                    refundToken,
                                    "refund-${refundOrder.orderId}"
                                )
                            }.onSuccess { updated ->
                                paymentOrders = accountRepository.paymentOrders()
                                accessCoordinator.reconcile()
                                message = when (updated.status) {
                                    "refunded" -> "退款成功，授权容量已回收"
                                    "refund_pending" -> "退款申请已提交，正在等待支付渠道处理"
                                    else -> "退款未完成，请稍后重试"
                                }
                                pendingRefundOrder = null
                                refundVerificationToken = null
                            }.onFailure { error = it.message ?: "退款失败" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("确认退款") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingRefundOrder = null
                        refundVerificationToken = null
                    },
                    enabled = !busy
                ) { Text("取消") }
            }
        )
    }

    deleteVerificationToken?.let { verificationToken ->
        val merchantIds = paymentOrders.map { it.merchantOrderId }.filter(String::isNotBlank)
        AlertDialog(
            onDismissRequest = { if (!busy) deleteVerificationToken = null },
            title = { Text("永久注销账号？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账号、登录凭据、设备授权和云同步数据将永久删除，无法恢复。")
                    if (merchantIds.isNotEmpty()) {
                        Text("注销后不能在 App 内自助退款，只能凭以下商户订单号加入 QQ 群 1083518433 联系支持。请先保存：")
                        SelectionContainer { Text(merchantIds.joinToString("\n")) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runAction {
                            busy = true
                            runCatching { accessCoordinator.deleteAccount(verificationToken) }
                                .onSuccess { result ->
                                    deleteVerificationToken = null
                                    accountDeletionResult = result
                                }
                                .onFailure { error = it.message ?: "账号注销失败" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("永久注销") }
            },
            dismissButton = {
                TextButton(onClick = { deleteVerificationToken = null }, enabled = !busy) {
                    Text("取消")
                }
            }
        )
    }

    accountDeletionResult?.let { result ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("账号已注销") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账号、登录凭据、设备授权和云同步资料已删除。")
                    if (result.retainedMerchantOrderIds.isNotEmpty()) {
                        Text("如需退款，请保存以下商户订单号，并加入 QQ 群 1083518433 联系支持：")
                        SelectionContainer {
                            Text(result.retainedMerchantOrderIds.joinToString("\n"))
                        }
                    }
                    if (result.storageCleanupPending) {
                        Text("部分云端文件正在后台继续清理。")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                        )
                    }
                ) { Text("我已保存，返回登录") }
            }
        )
    }

    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
        val rootPane: @Composable () -> Unit = {
            AccountPageScaffold(title = "账号", onBack = onBack) {
                AccountIdentityPanel(summary = session?.phoneMasked ?: "未登录")
                val accessSummary = when (val access = appAccessState) {
                    is AppAccessState.Authorized -> access.summary
                    is AppAccessState.PurchaseRequired -> access.summary
                    is AppAccessState.Revoked -> access.summary
                    is AppAccessState.ReauthenticationRequired -> access.summary
                    else -> null
                }
                if (session != null && accessSummary != null) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (accessSummary.accessMode == "trial") "手机版预览授权" else "手机版设备授权包",
                                fontWeight = FontWeight.SemiBold
                            )
                            if (accessSummary.accessMode == "trial") {
                                Text("这是旧版临时预览授权；页面不显示倒计时。到期不会删除本地资料。")
                            } else {
                                Text("已购买 ${accessSummary.purchaseCount} 次 · 容量 ${accessSummary.capacity} 台 · 已占用 ${accessSummary.occupied} 台")
                            }
                            PaidAccessTransparencyCard(
                                product = accessSummary.product,
                                compact = true
                            )
                            Button(onClick = { showPaymentAgreement = true }) {
                                Text("购买手机版设备授权 ¥6")
                            }
                        }
                    }
                }
                if (session != null) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("订单与退款", fontWeight = FontWeight.SemiBold)
                            Text(
                                "订单对应的是手机版设备授权包，不是哔哩哔哩、抖音或WatchRSS云会员。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            when {
                                paymentOrdersLoading -> Text("正在加载订单…")
                                paymentOrders.isEmpty() -> Text("暂无订单")
                                else -> paymentOrders.forEach { order ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${order.product.productName} · 订单 ${order.merchantOrderId}")
                                        Text(
                                            paymentOrderStatusText(order),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (order.refundable) {
                                            TextButton(
                                                onClick = {
                                                    pendingRefundOrder = order
                                                    refundVerificationToken = null
                                                    securityVerificationPurpose =
                                                        SecurityVerificationPurpose.REFUND_ORDER
                                                    securityVerificationProgress = null
                                                    securityVerificationMethod = null
                                                    securityVerificationError = null
                                                    showDisableMethodDialog = true
                                                },
                                                enabled = !busy
                                            ) { Text("申请七天无理由退款") }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
                accountControls(::openCloudSync)
                if (page == AccountPage.ROOT) {
                    AccountFeedback(message = message, error = error)
                }
            }
        }
        val cloudPane: @Composable () -> Unit = {
            AccountPageScaffold(
                title = "云同步",
                onBack = ::leaveCloudSync,
                backEnabled = paneTransition == null
            ) {
                val activeSession = session
                if (activeSession == null) {
                    Text("登录后可管理云同步。")
                } else {
                    CloudAccountPanel(
                        service = cloudSyncService,
                        userId = activeSession.userId,
                        rssSources = rssSources,
                        busy = busy,
                        runAction = runAction,
                        onBusyChange = { busy = it },
                        onMessage = {
                            message = it
                            error = null
                            if (it == "加密云备份已启用") {
                                tipManager?.recordEvent(TipEvents.CLOUD_ENCRYPTION_ENABLED)
                            }
                        },
                        onError = {
                            error = it
                            message = null
                        }
                    )
                }
                if (page == AccountPage.CLOUD_SYNC) {
                    AccountFeedback(message = message, error = error)
                }
            }
        }

        if (leadingPane != null && windowInfo.isMediumOrExpanded) {
            when (paneTransition) {
                AccountPaneTransition.OPENING_CLOUD -> AdaptiveReaderOpenThreePane(
                    windowInfo = windowInfo,
                    progress = paneTransitionProgress,
                    horizontalPadding = 0.dp,
                    paneSpacing = 0.dp,
                    startPane = leadingPane,
                    movingPane = rootPane,
                    readerPane = cloudPane
                )
                AccountPaneTransition.CLOSING_CLOUD -> AdaptiveReaderReturnThreePane(
                    windowInfo = windowInfo,
                    progress = paneTransitionProgress,
                    horizontalPadding = 0.dp,
                    paneSpacing = 0.dp,
                    startPane = leadingPane,
                    movingPane = rootPane,
                    readerPane = cloudPane
                )
                null -> AdaptiveTwoPane(
                    windowInfo = windowInfo,
                    horizontalPadding = 0.dp,
                    paneSpacing = 0.dp,
                    startPane = if (page == AccountPage.ROOT) leadingPane else rootPane,
                    endPane = if (page == AccountPage.ROOT) rootPane else cloudPane
                )
            }
        } else {
            AnimatedContent(
                targetState = page,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                transitionSpec = {
                    val direction = if (targetState == AccountPage.CLOUD_SYNC) 1 else -1
                    (
                        slideInHorizontally(
                            animationSpec = tween(320, easing = FastOutSlowInEasing)
                        ) { fullWidth -> fullWidth * direction } + fadeIn()
                        ) togetherWith (
                        slideOutHorizontally(
                            animationSpec = tween(320, easing = FastOutSlowInEasing)
                        ) { fullWidth -> -fullWidth * direction } + fadeOut()
                        )
                },
                label = "account-secondary-page"
            ) { targetPage ->
                if (targetPage == AccountPage.ROOT) rootPane() else cloudPane()
            }
        }
    }
}

private const val ACCOUNT_LOG_TAG = "AccountActivity"

internal fun cloudSyncMenuSummary(
    member: CloudMemberState?,
    hasLocalKey: Boolean
): String = when {
    member == null && hasLocalKey -> "已启用 · 端到端加密"
    member == null -> "管理容量、恢复密钥、快照和同步策略"
    !member.readable -> "当前账号未开通会员云空间"
    !member.writable && hasLocalKey -> "已启用 · 当前为只读恢复期"
    !member.writable -> "只读恢复期 · 可恢复已有快照"
    hasLocalKey -> "已启用 · 端到端加密"
    else -> "云备份可用 · 等待启用或恢复"
}

internal fun shouldShowCloudSyncEntry(isDebugBuild: Boolean): Boolean = isDebugBuild

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPageScaffold(
    title: String,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack, enabled = backEnabled) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                AdaptiveContentFrame(
                    windowInfo = windowInfo,
                    mediumMaxWidth = 680.dp,
                    expandedMaxWidth = 760.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(
                                horizontal = if (windowInfo.isMediumOrExpanded) 32.dp else 20.dp,
                                vertical = 20.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudSyncEntryCard(
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "云同步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AccountFeedback(message: String?, error: String?) {
    message?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    error?.let {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            SupportContactInlineFooter(
                hint = "登录遇到问题？联系客服并提供上方提示"
            )
        }
    }
}

@Composable
private fun AccountIdentityPanel(
    summary: String,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "账号用于设备绑定、使用数据归属、云端同步和后续会员权益。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RegisteredPasskeyCard(
    passkey: RegisteredPasskey,
    enabled: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = passkey.displayName.ifBlank { "Passkey" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = passkeyMetadata(passkey),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRename, enabled = enabled) {
                Text("重命名")
            }
            TextButton(
                onClick = onDelete,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("删除")
            }
        }
    }
}

private fun passkeyMetadata(passkey: RegisteredPasskey): String = buildString {
    append("创建于 ")
    append(formatPasskeyDate(passkey.createdAtMillis))
    passkey.lastUsedAtMillis?.let {
        append(" · 最近使用 ")
        append(formatPasskeyDate(it))
    }
}

internal fun securityMethodLabel(method: String): String = when (method) {
    "sms" -> "短信验证码"
    "password" -> "密码"
    "totp" -> "TOTP 验证器"
    "passkey" -> "Passkey"
    else -> method
}

internal fun shouldAutoEnableTwoFactor(
    pending: Boolean,
    status: AccountSecurityStatus
): Boolean = pending && status.availableMethodCount >= 2

internal fun missingTwoFactorMethods(availableMethods: Set<String>): List<String> =
    listOf("password", "totp", "passkey").filterNot(availableMethods::contains)

private fun formatPasskeyDate(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "未知时间"
    return PASSKEY_DATE_FORMATTER.format(
        Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
    )
}

private val PASSKEY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun paymentOrderStatusText(order: AppPaymentOrder): String {
    val amount = "¥${order.amountFen / 100}"
    val status = when (order.status) {
        "pending" -> "等待支付"
        "paid" -> "支付成功"
        "refund_pending" -> "退款处理中"
        "refunded" -> "已退款"
        "refund_failed" -> "退款失败，可重新申请"
        "closed" -> "订单已关闭"
        else -> order.status
    }
    val deadline = order.refundEligibleUntilMillis?.let { millis ->
        runCatching {
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }.getOrNull()
    }
    return buildString {
        append(amount).append(" · ").append(status)
        if (order.refundable && deadline != null) append(" · 可退款至 ").append(deadline)
    }
}

internal fun trialRemainingText(expiresAtMillis: Long?, nowMillis: Long): String {
    if (expiresAtMillis == null || expiresAtMillis <= nowMillis) return "试用已到期"
    val remainingMinutes = ((expiresAtMillis - nowMillis) + 59_999L) / 60_000L
    val days = remainingMinutes / (24L * 60L)
    val hours = (remainingMinutes % (24L * 60L)) / 60L
    val minutes = remainingMinutes % 60L
    return when {
        days > 0L -> "试用剩余 ${days} 天 ${hours} 小时"
        hours > 0L -> "试用剩余 ${hours} 小时 ${minutes} 分钟"
        else -> "试用剩余 ${minutes} 分钟"
    }
}

private const val MAX_PASSKEY_DISPLAY_NAME_CHARS = 64
