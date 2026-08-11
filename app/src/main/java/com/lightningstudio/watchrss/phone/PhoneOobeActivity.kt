package com.lightningstudio.watchrss.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.privacy.PhoneOobeStage
import com.lightningstudio.watchrss.phone.privacy.PhonePrivacyConsentStore
import com.lightningstudio.watchrss.phone.privacy.phoneOobeStage
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

class PhoneOobeActivity : ComponentActivity() {
    private lateinit var consentStore: PhonePrivacyConsentStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentStore = PhonePrivacyConsentStore(this)
        setContent {
            WatchRssPhoneTheme {
                val accountRepository =
                    (application as PhoneCompanionApplication).container.accountRepository
                val session by accountRepository.session.collectAsState()
                var page by rememberSaveable { mutableIntStateOf(0) }
                var hasConsent by remember { mutableStateOf(consentStore.hasRequiredConsent()) }
                val hasUsableSession = session?.isExpired == false
                val stage = phoneOobeStage(page, hasConsent, hasUsableSession)

                BackHandler {
                    if (stage == PhoneOobeStage.AGREEMENT) page = 0 else finishAffinity()
                }

                PhoneOobeScreen(
                    stage = stage,
                    phoneLabel = session?.phoneMasked,
                    onContinueFromWelcome = { page = 1 },
                    onOpenAgreement = { openLegalDocument(LegalDocument.USER_AGREEMENT) },
                    onOpenPrivacy = { openLegalDocument(LegalDocument.PRIVACY_POLICY) },
                    onAcceptPolicies = {
                        consentStore.acceptRequiredPolicies()
                        hasConsent = true
                        (application as PhoneCompanionApplication).onPrivacyConsentGranted()
                    },
                    onLogin = {
                        startActivity(AccountActivity.createIntent(this, finishAfterLogin = true))
                    },
                    onFinish = {
                        consentStore.completeOobe()
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }

    private fun openLegalDocument(document: LegalDocument) {
        startActivity(LegalDocumentActivity.createIntent(this, document))
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, PhoneOobeActivity::class.java)
    }
}

@Composable
private fun PhoneOobeScreen(
    stage: PhoneOobeStage,
    phoneLabel: String?,
    onContinueFromWelcome: () -> Unit,
    onOpenAgreement: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onAcceptPolicies: () -> Unit,
    onLogin: () -> Unit,
    onFinish: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        AdaptiveWindowScope(Modifier.fillMaxSize()) { windowInfo ->
            AdaptiveContentFrame(
                windowInfo = windowInfo,
                mediumMaxWidth = 600.dp,
                expandedMaxWidth = 640.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 36.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    OobeProgress(stage)
                    when (stage) {
                        PhoneOobeStage.WELCOME -> WelcomeStep(onContinueFromWelcome)
                        PhoneOobeStage.AGREEMENT -> AgreementStep(
                            onOpenAgreement,
                            onOpenPrivacy,
                            onAcceptPolicies
                        )
                        PhoneOobeStage.ACCOUNT -> AccountStep(onLogin)
                        PhoneOobeStage.COMPLETE -> CompleteStep(phoneLabel, onFinish)
                    }
                }
            }
        }
    }
}

@Composable
private fun OobeProgress(stage: PhoneOobeStage) {
    val current = when (stage) {
        PhoneOobeStage.WELCOME -> 1
        PhoneOobeStage.AGREEMENT -> 2
        PhoneOobeStage.ACCOUNT -> 3
        PhoneOobeStage.COMPLETE -> 3
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(3) { index ->
            Surface(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(if (index + 1 == current) 30.dp else 10.dp)
                    .height(8.dp),
                shape = RoundedCornerShape(50),
                color = if (index + 1 <= current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {}
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    OobeBody(
        icon = Icons.Default.PhoneAndroid,
        title = "欢迎使用腕上RSS",
        description = "在手机上管理 RSS、文章与阅读资料，并与已配对的手表保持同步。",
        detail = "接下来需要阅读并同意服务条款，然后登录腕上RSS账号。"
    )
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text("开始设置")
    }
}

@Composable
private fun AgreementStep(
    onOpenAgreement: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onAccept: () -> Unit
) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        OobeBody(
            icon = Icons.Default.Lock,
            title = "服务条款与隐私保护",
            description = "请在使用前阅读用户协议和隐私政策。未经同意，应用不会启动统计、云同步或账号授权联网。",
            detail = "政策说明了账号信息、内容同步、统计分析、第三方平台与 AI 功能涉及的数据处理。"
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                TextButton(onClick = onOpenAgreement, modifier = Modifier.fillMaxWidth()) {
                    Text("查看《用户协议》")
                }
                TextButton(onClick = onOpenPrivacy, modifier = Modifier.fillMaxWidth()) {
                    Text("查看《隐私政策》")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text("我已阅读并同意《用户协议》和《隐私政策》")
        }
    }
    Button(
        onClick = onAccept,
        enabled = checked,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("同意并继续")
    }
}

@Composable
private fun AccountStep(onLogin: () -> Unit) {
    OobeBody(
        icon = Icons.Default.Lock,
        title = "登录腕上RSS账号",
        description = "登录用于验证应用授权，并支持账号安全、会员权益和加密云同步。",
        detail = "可以使用当前账号页提供的手机号、密码、验证码或 Passkey 完成验证。"
    )
    Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
        Text("登录账号")
    }
}

@Composable
private fun CompleteStep(phoneLabel: String?, onFinish: () -> Unit) {
    OobeBody(
        icon = Icons.Default.CheckCircle,
        title = "设置完成",
        description = phoneLabel?.let { "账号 $it 已登录。" } ?: "账号已登录。",
        detail = "接下来将校验本机授权，然后进入腕上RSS。"
    )
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
        Text("开始使用")
    }
}

@Composable
private fun OobeBody(
    icon: ImageVector,
    title: String,
    description: String,
    detail: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
