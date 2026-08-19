package com.lightningstudio.watchrss.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.adaptiveContentWidth
import com.lightningstudio.watchrss.phone.ui.theme.roundedCombinedClickable

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme {
                val onBack = { finish() }
                AboutScreen(
                    onBackClick = onBack,
                    onOpenUserAgreement = {
                        startActivity(
                            LegalDocumentActivity.createIntent(
                                this,
                                LegalDocument.USER_AGREEMENT
                            )
                        )
                    },
                    onOpenPrivacyPolicy = {
                        startActivity(
                            LegalDocumentActivity.createIntent(
                                this,
                                LegalDocument.PRIVACY_POLICY
                            )
                        )
                    },
                    onBeianClick = {
                        openExternally("https://beian.miit.gov.cn/")
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    onOpenUserAgreement: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onBeianClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("关于腕上RSS") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                AdaptiveContentFrame(
                    windowInfo = windowInfo,
                    mediumMaxWidth = 680.dp,
                    expandedMaxWidth = 760.dp
                ) {
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(animationSpec = tween(500)) +
                            slideInVertically(
                                initialOffsetY = { 32 },
                                animationSpec = tween(500, easing = FastOutSlowInEasing)
                            )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    horizontal = if (windowInfo.isMediumOrExpanded) 32.dp else 20.dp,
                                    vertical = 20.dp
                                ),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            AboutSection()
                            LegalDocumentSection(
                                title = "用户协议",
                                description = "查看腕上RSS 手机版的服务内容、账号授权和使用规则",
                                onClick = onOpenUserAgreement
                            )
                            LegalDocumentSection(
                                title = "隐私政策",
                                description = "查看账号、同步、统计分析及第三方服务的数据处理说明",
                                onClick = onOpenPrivacyPolicy
                            )
                            Spacer(modifier = Modifier.height(64.dp))
                        }
                    }
                }

                // 备案号常显且底部居中：备案号是安全、可信、引以为傲的合规标识，
                // 不是需要弱化或隐藏的内容，因此不做延迟渐显、始终直接显示；
                // 底部居中与主流App对备案号的展示方式保持一致。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .adaptiveContentWidth(
                            windowInfo = windowInfo,
                            mediumMaxWidth = 680.dp,
                            expandedMaxWidth = 760.dp
                        )
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BeianNumberText(onClick = onBeianClick)
                }
            }
        }
    }
}

@Composable
fun AboutSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "腕上RSS",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "版本 1.0.1",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "腕上RSS是一款专为OPPO Watch设计的RSS阅读器配套应用。通过已配对蓝牙 RFCOMM 连接手表，您可以同步RSS订阅源、收藏内容、稍后阅读列表和本地导入文章。",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "主要功能：",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• 已配对蓝牙 RFCOMM 同步\n• 添加RSS订阅源\n• 查看收藏文章\n• 管理稍后阅读列表\n• 导入 TXT / EPUB 本地内容",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun LegalDocumentSection(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text("查看完整内容 ›", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BeianNumberText(onClick: () -> Unit) {
    val context = LocalContext.current
    val beianText = "浙ICP备2024111886号-5A"

    Text(
        text = beianText,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.roundedCombinedClickable(
            shape = RoundedCornerShape(8.dp),
            onClick = onClick,
            onLongClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("备案号", beianText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "备案号已复制", Toast.LENGTH_SHORT).show()
            }
        )
    )
}
