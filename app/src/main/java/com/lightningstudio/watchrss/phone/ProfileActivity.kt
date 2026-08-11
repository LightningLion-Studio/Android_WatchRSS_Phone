package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveTwoPane
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowInfo
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.lightningstudio.watchrss.phone.account.PhonePasskeyCoordinator

class ProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PhoneCompanionApplication).container
        val accountRepository = container.accountRepository
        val passkeyCoordinator = PhonePasskeyCoordinator(this, accountRepository)

        setContent {
            WatchRssPhoneTheme {
                val session by accountRepository.session.collectAsState()
                val rssSources by container.repository.observeRssSources()
                    .collectAsState(initial = emptyList())
                val onBack = ::finish
                var selectedDetail by rememberSaveable {
                    mutableStateOf<ProfileDetailPage?>(null)
                }

                ProfileScreen(
                    accountSummary = session?.phoneMasked ?: "未登录",
                    selectedDetail = selectedDetail,
                    onDetailSelected = { selectedDetail = it },
                    onBack = onBack,
                    onAccountClick = {
                        startActivity(AccountActivity.createIntent(this@ProfileActivity))
                    },
                    onSettingsClick = {
                        startActivity(SettingsActivity.createIntent(this@ProfileActivity))
                    },
                    onManageDataClick = {
                        startActivity(DataManagementActivity.createIntent(this@ProfileActivity))
                    },
                    onAboutClick = {
                        startActivity(Intent(this@ProfileActivity, AboutActivity::class.java))
                    },
                    onContactDeveloperClick = {
                        startActivity(
                            Intent(
                                this@ProfileActivity,
                                ContactDeveloperActivity::class.java
                            )
                        )
                    },
                    accountContent = { leadingPane, onClose ->
                        AccountScreen(
                            accountRepository = accountRepository,
                            cloudSyncService = container.cloudSyncService,
                            rssSources = rssSources,
                            usageTelemetry = container.usageTelemetry,
                            onBack = onClose,
                            onLoginComplete = {},
                            loginWithPasskey = passkeyCoordinator::login,
                            createPasskey = passkeyCoordinator::createPasskey,
                            runAction = { action ->
                                lifecycleScope.launch { action() }
                            },
                            leadingPane = leadingPane
                        )
                    },
                    settingsContent = { leadingPane, onClose ->
                        ReaderSettingsHost(
                            repository = container.readerPresetRepository,
                            onFinish = onClose,
                            leadingPane = leadingPane
                        )
                    },
                    detailContent = { detail, onClose ->
                        when (detail) {
                            ProfileDetailPage.ACCOUNT -> AccountScreen(
                                accountRepository = accountRepository,
                                cloudSyncService = container.cloudSyncService,
                                rssSources = rssSources,
                                usageTelemetry = container.usageTelemetry,
                                onBack = onClose,
                                onLoginComplete = {},
                                loginWithPasskey = passkeyCoordinator::login,
                                createPasskey = passkeyCoordinator::createPasskey,
                                runAction = { action ->
                                    lifecycleScope.launch { action() }
                                }
                            )
                            ProfileDetailPage.SETTINGS -> ReaderSettingsHost(
                                repository = container.readerPresetRepository,
                                onFinish = onClose
                            )
                            ProfileDetailPage.ABOUT -> AboutScreen(
                                onBackClick = onClose,
                                onOpenUserAgreement = {
                                    startActivity(
                                        LegalDocumentActivity.createIntent(
                                            this@ProfileActivity,
                                            LegalDocument.USER_AGREEMENT
                                        )
                                    )
                                },
                                onOpenPrivacyPolicy = {
                                    startActivity(
                                        LegalDocumentActivity.createIntent(
                                            this@ProfileActivity,
                                            LegalDocument.PRIVACY_POLICY
                                        )
                                    )
                                },
                                onBeianClick = {
                                    startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://beian.miit.gov.cn/")
                                        )
                                    )
                                }
                            )
                            ProfileDetailPage.CONTACT -> ContactDeveloperScreen(
                                onBack = onClose,
                                onJoinQQ = {
                                    val qqIntent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                            "mqqopensdkapi://bizAgent/qm/qr?url=" +
                                                "http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr" +
                                                "%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi" +
                                                "%26k%3D1083518433"
                                        )
                                    )
                                    if (runCatching { startActivity(qqIntent) }.isFailure) {
                                        startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://qm.qq.com/q/cJNTQuxfoW")
                                            )
                                        )
                                    }
                                },
                                onBeianClick = {
                                    startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://beian.miit.gov.cn/")
                                        )
                                    )
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, ProfileActivity::class.java)
    }
}

private enum class ProfileDetailPage {
    ACCOUNT,
    SETTINGS,
    ABOUT,
    CONTACT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen(
    accountSummary: String,
    selectedDetail: ProfileDetailPage?,
    onDetailSelected: (ProfileDetailPage?) -> Unit,
    onBack: () -> Unit,
    onAccountClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageDataClick: () -> Unit,
    onAboutClick: () -> Unit,
    onContactDeveloperClick: () -> Unit,
    accountContent: @Composable (
        leadingPane: @Composable () -> Unit,
        onClose: () -> Unit
    ) -> Unit,
    settingsContent: @Composable (
        leadingPane: @Composable () -> Unit,
        onClose: () -> Unit
    ) -> Unit,
    detailContent: @Composable (ProfileDetailPage, () -> Unit) -> Unit
) {
    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
        val closeDetail = { onDetailSelected(null) }
        if (windowInfo.isMediumOrExpanded) {
            PredictiveBackHandler(
                enabled = selectedDetail != null && selectedDetail != ProfileDetailPage.ACCOUNT
            ) { events ->
                events.collect { }
                closeDetail()
            }
            val masterPane: @Composable () -> Unit = {
                ProfileMasterPane(
                    accountSummary = accountSummary,
                    windowInfo = windowInfo,
                    onBack = onBack,
                    onAccountClick = { onDetailSelected(ProfileDetailPage.ACCOUNT) },
                    onSettingsClick = { onDetailSelected(ProfileDetailPage.SETTINGS) },
                    onManageDataClick = onManageDataClick,
                    onAboutClick = { onDetailSelected(ProfileDetailPage.ABOUT) },
                    onContactDeveloperClick = {
                        onDetailSelected(ProfileDetailPage.CONTACT)
                    }
                )
            }
            when (selectedDetail) {
                ProfileDetailPage.ACCOUNT -> accountContent(masterPane, closeDetail)
                ProfileDetailPage.SETTINGS -> settingsContent(masterPane, closeDetail)
                else -> {
                    AdaptiveTwoPane(
                        windowInfo = windowInfo,
                        horizontalPadding = 0.dp,
                        paneSpacing = 0.dp,
                        startPane = masterPane,
                        endPane = {
                            AnimatedContent(
                                targetState = selectedDetail,
                                transitionSpec = {
                                    (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                                        (slideOutHorizontally { -it / 8 } + fadeOut())
                                },
                                label = "profile-detail-page"
                            ) { detail ->
                                if (detail == null) {
                                    ProfileDetailPlaceholder()
                                } else {
                                    detailContent(detail, closeDetail)
                                }
                            }
                        }
                    )
                }
            }
        } else if (selectedDetail != null) {
            PredictiveBackHandler(
                enabled = selectedDetail != ProfileDetailPage.ACCOUNT
            ) { events ->
                events.collect { }
                closeDetail()
            }
            detailContent(selectedDetail, closeDetail)
        } else {
            ProfileMasterPane(
                accountSummary = accountSummary,
                windowInfo = windowInfo,
                onBack = onBack,
                onAccountClick = onAccountClick,
                onSettingsClick = onSettingsClick,
                onManageDataClick = onManageDataClick,
                onAboutClick = onAboutClick,
                onContactDeveloperClick = onContactDeveloperClick
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileMasterPane(
    accountSummary: String,
    windowInfo: AdaptiveWindowInfo,
    onBack: () -> Unit,
    onAccountClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onManageDataClick: () -> Unit,
    onAboutClick: () -> Unit,
    onContactDeveloperClick: () -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("我的") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        AdaptiveContentFrame(
            windowInfo = windowInfo,
            modifier = Modifier.padding(padding),
            mediumMaxWidth = 720.dp,
            expandedMaxWidth = 840.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                ProfileIdentityCard(
                    accountSummary = accountSummary,
                    onClick = onAccountClick
                )
                ProfileNavigation(
                    windowInfo = windowInfo,
                    onSettingsClick = onSettingsClick,
                    onManageDataClick = onManageDataClick,
                    onAboutClick = onAboutClick,
                    onContactDeveloperClick = onContactDeveloperClick
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ProfileDetailPlaceholder() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "从左侧选择一个项目",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun ProfileIdentityCard(
    accountSummary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "腕上RSS",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = accountSummary,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "管理账号、云端资料和设备归属",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "进入账号",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }
    }
}

@Composable
private fun ProfileNavigation(
    windowInfo: AdaptiveWindowInfo,
    onSettingsClick: () -> Unit,
    onManageDataClick: () -> Unit,
    onAboutClick: () -> Unit,
    onContactDeveloperClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        ProfileGroupTitle("个性化与功能")
        ProfileEntry(
            title = "设置",
            supportingText = if (windowInfo.isMediumOrExpanded) {
                if (BuildConfig.DEBUG) {
                    "阅读器预设、字体、背景、AI 总结及应用行为"
                } else {
                    "阅读器预设、字体、背景及应用行为"
                }
            } else {
                "阅读器、预设、字体与应用功能"
            },
            icon = Icons.Default.Settings,
            onClick = onSettingsClick
        )
        ProfileEntry(
            title = "管理资料",
            supportingText = "导出、恢复或删除本机资料库",
            icon = Icons.Default.Storage,
            onClick = onManageDataClick
        )
        ProfileGroupTitle("支持与信息")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileEntry(
                title = "关于腕上RSS",
                supportingText = "版本、服务协议、隐私政策与备案信息",
                icon = Icons.Default.Info,
                onClick = onAboutClick
            )
            ProfileEntry(
                title = "联系开发者",
                supportingText = "加入用户群，反馈问题或提出建议",
                icon = Icons.Default.SupportAgent,
                onClick = onContactDeveloperClick
            )
        }
    }
}

@Composable
private fun ProfileGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun ProfileEntry(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    supportingText: String? = null
) {
    ElevatedCard(
        onClick = onClick,
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
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                supportingText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
