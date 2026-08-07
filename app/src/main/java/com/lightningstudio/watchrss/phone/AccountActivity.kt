package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.account.AccountLoginAction
import com.lightningstudio.watchrss.phone.account.AppAccessState
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.account.PhonePasskeyCoordinator
import com.lightningstudio.watchrss.phone.account.RegisteredPasskey
import com.lightningstudio.watchrss.phone.account.accountLoginErrorMessage
import com.lightningstudio.watchrss.phone.cloud.CloudAccountPanel
import com.lightningstudio.watchrss.phone.cloud.CloudMemberState
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncService
import com.lightningstudio.watchrss.phone.data.telemetry.PhoneUsageTelemetry
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveReaderOpenThreePane
import com.lightningstudio.watchrss.phone.ui.AdaptiveReaderReturnThreePane
import com.lightningstudio.watchrss.phone.ui.AdaptiveTwoPane
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.CancellationException
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

        setContent {
            WatchRssPhoneTheme {
                val rssSources by container.repository.observeRssSources()
                    .collectAsState(initial = emptyList())
                AccountScreen(
                    accountRepository = accountRepository,
                    cloudSyncService = container.cloudSyncService,
                    rssSources = rssSources,
                    usageTelemetry = container.usageTelemetry,
                    onBack = ::finish,
                    preparePasskeyLogin = passkeyCoordinator::prepareLogin,
                    loginWithPasskey = passkeyCoordinator::login,
                    createPasskey = passkeyCoordinator::createPasskey,
                    runAction = { action ->
                        lifecycleScope.launch { action() }
                    }
                )
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
        fun createIntent(context: Context): Intent =
            Intent(context, AccountActivity::class.java)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    accountRepository: PhoneAccountRepository,
    cloudSyncService: PhoneCloudSyncService,
    rssSources: List<com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity>,
    usageTelemetry: PhoneUsageTelemetry,
    onBack: () -> Unit,
    preparePasskeyLogin: suspend (String) -> Boolean,
    loginWithPasskey: suspend (String) -> com.lightningstudio.watchrss.phone.account.PhoneAccountSession,
    createPasskey: suspend () -> Unit,
    runAction: (suspend () -> Unit) -> Unit,
    leadingPane: (@Composable () -> Unit)? = null
) {
    val session by accountRepository.session.collectAsState()
    val context = LocalContext.current
    val accessCoordinator = (context.applicationContext as PhoneCompanionApplication).container.appAccessCoordinator
    val appAccessState by accessCoordinator.state.collectAsState()
    val cloudSyncState by cloudSyncService.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var page by rememberSaveable(session?.userId) { mutableStateOf(AccountPage.ROOT) }
    var paneTransition by remember { mutableStateOf<AccountPaneTransition?>(null) }
    var paneTransitionProgress by remember { mutableStateOf(1f) }
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var passkeyLoginAvailable by remember { mutableStateOf(false) }
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

    val passkeyProbePhone = phoneForPasskeyProbe(phone)
    LaunchedEffect(session?.userId, passkeyProbePhone) {
        passkeyLoginAvailable = false
        if (session != null || passkeyProbePhone == null) return@LaunchedEffect
        delay(PASSKEY_PROBE_DEBOUNCE_MILLIS)
        passkeyLoginAvailable = try {
            preparePasskeyLogin(passkeyProbePhone)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
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
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("手机号") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            AnimatedVisibility(
                visible = passkeyLoginAvailable,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            val readyPhone = passkeyProbePhone ?: return@Button
                            runAction {
                                busy = true
                                error = null
                                message = null
                                runCatching { loginWithPasskey(readyPhone) }
                                    .onSuccess { session ->
                                        message = "登录成功"
                                        usageTelemetry.recordAccountSignedIn(session.userId)
                                    }
                                    .onFailure {
                                        Log.w(ACCOUNT_LOG_TAG, "Passkey login failed", it)
                                        error = accountLoginErrorMessage(
                                            AccountLoginAction.PASSKEY_LOGIN,
                                            it
                                        )
                                    }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("使用本机通行密钥继续")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            "或使用短信验证码",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }
                }
            }
            OutlinedTextField(
                value = otp,
                onValueChange = { otp = it },
                label = { Text("验证码") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        runAction {
                            busy = true
                            error = null
                            runCatching { accountRepository.requestPhoneOtp(phone) }
                                .onSuccess { message = "验证码已发送" }
                                .onFailure {
                                    Log.w(ACCOUNT_LOG_TAG, "Phone OTP request failed", it)
                                    error = accountLoginErrorMessage(
                                        AccountLoginAction.REQUEST_OTP,
                                        it
                                    )
                                }
                            busy = false
                        }
                    },
                    enabled = !busy && phone.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("获取验证码")
                }
                Button(
                    onClick = {
                        runAction {
                            busy = true
                            error = null
                            runCatching { accountRepository.verifyPhoneOtp(phone, otp) }
                                .onSuccess { session ->
                                    message = "登录成功"
                                    usageTelemetry.recordAccountSignedIn(session.userId)
                                }
                                .onFailure {
                                    Log.w(ACCOUNT_LOG_TAG, "Phone OTP verification failed", it)
                                    error = accountLoginErrorMessage(
                                        AccountLoginAction.VERIFY_OTP,
                                        it
                                    )
                                }
                            busy = false
                        }
                    },
                    enabled = !busy && phone.isNotBlank() && otp.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("登录")
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
            CloudSyncEntryCard(
                summary = cloudSyncMenuSummary(
                    member = cloudSyncState.member,
                    hasLocalKey = cloudSyncService.hasLocalAccountKey()
                ),
                enabled = !busy && appAccessState is AppAccessState.Authorized,
                onClick = onCloudSyncClick
            )
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
                                error = it.message ?: "Passkey 删除失败"
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
                            Text("手机版永久授权", fontWeight = FontWeight.SemiBold)
                            Text("已购买 ${accessSummary.purchaseCount} 次 · 容量 ${accessSummary.capacity} 台 · 已占用 ${accessSummary.occupied} 台")
                            Button(onClick = {
                                coroutineScope.launch {
                                    runCatching { accessCoordinator.startPayment() }.onSuccess { order ->
                                        order.paymentUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                                    }.onFailure { error = it.message ?: "订单创建失败" }
                                }
                            }) { Text("再付 ¥6 增加 3 台") }
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

private const val PASSKEY_PROBE_DEBOUNCE_MILLIS = 500L
private const val ACCOUNT_LOG_TAG = "AccountActivity"

internal fun phoneForPasskeyProbe(input: String): String? {
    val digits = input.filter(Char::isDigit)
    return when {
        digits.length == 11 && digits.startsWith('1') -> digits
        digits.length == 13 && digits.startsWith("861") -> "+$digits"
        else -> null
    }
}

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
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
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

private fun formatPasskeyDate(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "未知时间"
    return PASSKEY_DATE_FORMATTER.format(
        Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
    )
}

private val PASSKEY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd")

private const val MAX_PASSKEY_DISPLAY_NAME_CHARS = 64
