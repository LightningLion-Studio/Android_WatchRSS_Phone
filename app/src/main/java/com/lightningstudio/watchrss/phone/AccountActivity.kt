package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.account.PhoneAccountRepository
import com.lightningstudio.watchrss.phone.cloud.CloudAccountPanel
import com.lightningstudio.watchrss.phone.cloud.PhoneCloudSyncService
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.launch

class AccountActivity : ComponentActivity() {
    private var screenStartedAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PhoneCompanionApplication).container
        val accountRepository = container.accountRepository

        setContent {
            WatchRssPhoneTheme {
                val rssSources by container.repository.observeRssSources()
                    .collectAsState(initial = emptyList())
                AccountScreen(
                    accountRepository = accountRepository,
                    cloudSyncService = container.cloudSyncService,
                    rssSources = rssSources,
                    onBack = ::finish,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    accountRepository: PhoneAccountRepository,
    cloudSyncService: PhoneCloudSyncService,
    rssSources: List<com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity>,
    onBack: () -> Unit,
    runAction: (suspend () -> Unit) -> Unit
) {
    val session by accountRepository.session.collectAsState()
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val accountControls: @Composable ColumnScope.() -> Unit = {
        if (session == null) {
            Text(
                "手机号登录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "登录后可管理云端资料和设备归属。",
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
                                .onFailure { error = it.message ?: "验证码发送失败" }
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
                                .onSuccess { message = "登录成功" }
                                .onFailure { error = it.message ?: "登录失败" }
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
            CloudAccountPanel(
                service = cloudSyncService,
                userId = requireNotNull(session).userId,
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
            OutlinedButton(
                onClick = {
                    runAction {
                        busy = true
                        runCatching { accountRepository.logout() }
                            .onSuccess { message = "已退出登录" }
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
        Text(
            text = "微信、QQ、OPPO 欢太账号登录已预留后端能力，当前版本先使用手机号验证码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("账号") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AccountIdentityPanel(summary = session?.phoneMasked ?: "未登录")
                        accountControls()
                    }
                }
            }
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
