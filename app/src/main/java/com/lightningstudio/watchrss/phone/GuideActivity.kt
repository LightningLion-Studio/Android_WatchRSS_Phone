package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.adaptiveContentWidth
import com.lightningstudio.watchrss.phone.ui.CapsuleFloatingButton
import com.lightningstudio.watchrss.phone.ui.PageColumn
import com.lightningstudio.watchrss.phone.ui.GlassTopBar
import com.lightningstudio.watchrss.phone.ui.theme.AppCard
import com.lightningstudio.watchrss.phone.ui.theme.AppPrimaryCard
import com.lightningstudio.watchrss.phone.ui.PredictiveBackSurface
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

class GuideActivity : ComponentActivity() {
    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, GuideActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchRssPhoneTheme {
                val onBack = { finish() }
                PredictiveBackSurface(onBack = onBack) {
                    GuideScreen(
                        onBack = onBack
                    )
                }
            }
        }
    }
}

@Composable
fun GuideScreen(onBack: () -> Unit) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }
    val uriHandler = LocalUriHandler.current

    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
                .padding(top = 56.dp)
        ) {
            AdaptiveContentFrame(
                windowInfo = windowInfo,
                mediumMaxWidth = 720.dp,
                expandedMaxWidth = 840.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                Text(
                    text = "开始使用腕上RSS",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "腕上RSS 是一款专为智能手表设计的 RSS 阅读器。通过本指南，您可以快速学会如何添加 RSS 源和导入小说内容。",
                    style = MaterialTheme.typography.bodyLarge
                )

                // Step 1
                AppPrimaryCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RssFeed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "第一步：添加 RSS 源",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Text(
                            text = "1. 在 RSS 页面点击右下角的「添加 RSS」按钮\n2. 输入 RSS 源地址（例如：https://example.com/feed.xml）\n3. 点击确认，等待文章拉取",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Step 2
                AppCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "第二步：导入小说 / TXT / EPUB",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Text(
                            text = "1. 在「导入内容」页面点击「导入文件」\n2. 选择手机中的 .txt 或 .epub 文件\n3. 腕上RSS 会自动将章节解析为可阅读的文章列表",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // Step 3
                AppCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "第三步：同步到手表",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Text(
                            text = "1. 确保手表已安装腕上RSS 并与手机配对\n2. 回到首页，点击「同步手表」\n3. 选择已配对的手表设备，等待数据传输完成",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // 手表下载
                AppPrimaryCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "下载手表端",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "手表端 APK 请在 GitHub Releases 下载，与 OPPO 手表兼容性最佳。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        CapsuleFloatingButton(
                            backdrop = backdrop,
                            onClick = { uriHandler.openUri("https://github.com/LightningLion-Studio/Android_WatchRSS/releases") }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Text("前往 GitHub 下载")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }

        GlassTopBar(
            backdrop = backdrop,
            title = "使用指南",
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .adaptiveContentWidth(
                    windowInfo = windowInfo,
                    mediumMaxWidth = 720.dp,
                    expandedMaxWidth = 840.dp
                )
        )
    }
    }
}
