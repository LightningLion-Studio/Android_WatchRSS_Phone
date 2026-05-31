package com.lightningstudio.watchrss.phone.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.MenuBook
import com.lightningstudio.watchrss.phone.R
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.ui.theme.AppCard
import com.lightningstudio.watchrss.phone.ui.theme.AppPrimaryCard
import com.lightningstudio.watchrss.phone.ui.theme.AppListCard
import com.lightningstudio.watchrss.phone.viewmodel.MainSyncProgressUi
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState
import androidx.compose.foundation.border

// ==================== 首页（三卡片设计）====================

@Composable
fun HomePage(
    uiState: MainUiState,
    onNavigateToGuide: () -> Unit,
    onNavigateToRss: () -> Unit,
    onNavigateToNovel: () -> Unit,
    onDismissMessage: () -> Unit
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 卡片1：开始使用腕上RSS
    AppCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "开始使用腕上RSS",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "腕上RSS 提供了 RSS 资讯、视频、小说功能，与 OPPO 手表兼容性最佳。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "您需要在手表上下载腕上RSS，并把手机与手表配对。",
                style = MaterialTheme.typography.bodyMedium
            )
            GlassButton(
                onClick = { uriHandler.openUri("https://github.com/LightningLion-Studio/Android_WatchRSS/releases") }
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text("前往 GitHub 下载手表端")
            }
        }
    }

    // 卡片2：同步RSS和小说到手表
    AppCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "同步RSS和小说到手表",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "学习如何添加 RSS 源、导入 TXT/EPUB 小说，并同步到已配对的手表。",
                style = MaterialTheme.typography.bodyMedium
            )
            GlassButton(onClick = onNavigateToGuide) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                Text("查看使用指南")
            }
        }
    }

    // 卡片3：加群学习更多玩法
    var qrMenuExpanded by remember { mutableStateOf(false) }
    AppCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "加群学习更多玩法",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "群里有开发者和高玩小伙伴，欢迎加入交流！",
                style = MaterialTheme.typography.bodyMedium
            )

            // QQ群二维码
            Box {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.qq_group_qr),
                    contentDescription = "QQ群二维码",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF3F2F6))
                        .combinedClickable(
                            onClick = { qrMenuExpanded = true },
                            onLongClick = { qrMenuExpanded = true }
                        ),
                    contentScale = ContentScale.Fit
                )
                DropdownMenu(
                    expanded = qrMenuExpanded,
                    onDismissRequest = { qrMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("保存到相册") },
                        leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                        onClick = {
                            qrMenuExpanded = false
                            coroutineScope.launch(Dispatchers.IO) {
                                val bitmap = BitmapFactory.decodeResource(
                                    context.resources, R.drawable.qq_group_qr
                                )
                                val values = ContentValues().apply {
                                    put(
                                        MediaStore.Images.Media.DISPLAY_NAME,
                                        "腕上RSS_QQ群二维码_${System.currentTimeMillis()}.png"
                                    )
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(
                                        MediaStore.Images.Media.RELATIVE_PATH,
                                        Environment.DIRECTORY_PICTURES + "/腕上RSS"
                                    )
                                }
                                val uri = context.contentResolver.insert(
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                                )
                                uri?.let {
                                    context.contentResolver.openOutputStream(it)?.use { out ->
                                        bitmap.compress(
                                            android.graphics.Bitmap.CompressFormat.PNG, 100, out
                                        )
                                    }
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("识别二维码") },
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            qrMenuExpanded = false
                            uriHandler.openUri("https://qm.qq.com/q/y5s7gt9WV2")
                        }
                    )
                }
            }

            Text(
                text = "群号：1083518433",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
}

// ==================== 状态卡片 ====================

@Composable
fun StatusCard(
    message: String?,
    error: String?,
    syncProgress: MainSyncProgressUi?,
    onDismissMessage: () -> Unit
) {
    AppPrimaryCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "蓝牙互联", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "导入网页、添加 RSS 源后，可手动与已配对手表双向同步资料库。",
                style = MaterialTheme.typography.bodyMedium
            )
            if (syncProgress != null) {
                Text(
                    text = syncProgress.phase,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                LinearProgressIndicator(
                    progress = { syncProgress.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "进度:${syncProgress.percent}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (syncProgress.bytesPerSecond > 0) {
                        Text(
                            text = "${syncProgress.bytesPerSecond / 1024} KB/s",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (syncProgress.bytesPerSecond > 0 && syncProgress.percent in 1..99) {
                    val remainingBytes = (syncProgress.bytesTransferred * (100 - syncProgress.percent)) / syncProgress.percent
                    val remainingSeconds = if (syncProgress.bytesPerSecond > 0) remainingBytes / syncProgress.bytesPerSecond else 0
                    val remainingText = when {
                        remainingSeconds >= 120 -> "约 ${remainingSeconds / 60} 分钟"
                        remainingSeconds >= 60 -> "约 1 分钟"
                        remainingSeconds > 0 -> "约 ${remainingSeconds} 秒"
                        else -> "即将完成"
                    }
                    Text(
                        text = "预计剩余: $remainingText",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                message?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(onClick = onDismissMessage)
                    )
                }
            }
            error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onDismissMessage)
                )
            }
        }
    }
}

// ==================== 频道列表页 ====================

@Composable
fun ChannelsPage(
    sources: List<PhoneRssSourceEntity>,
    articlesBySource: Map<String, List<PhoneArticleEntity>>,
    onOpenSource: (PhoneRssSourceEntity) -> Unit,
    onMoveToTop: (PhoneRssSourceEntity) -> Unit,
    onTogglePinned: (PhoneRssSourceEntity) -> Unit,
    onDelete: (PhoneRssSourceEntity) -> Unit
) {
    if (sources.isEmpty()) {
        Text(
            text = "暂无频道",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp)
        )
        return
    }
    sources.forEach { source ->
        SourceRow(
            source = source,
            articleCount = articlesBySource[source.url].orEmpty().size,
            onClick = { onOpenSource(source) },
            onMoveToTop = { onMoveToTop(source) },
            onTogglePinned = { onTogglePinned(source) },
            onDelete = { onDelete(source) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceRow(
    source: PhoneRssSourceEntity,
    articleCount: Int,
    onClick: () -> Unit,
    onMoveToTop: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        AppListCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = source.title.ifBlank { source.url },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                source.description.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = buildString {
                        if (source.isPinned) append("已置顶 · ")
                        append("$articleCount 篇文章")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(text = "移到顶部") },
                leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onMoveToTop()
                }
            )
            DropdownMenuItem(
                text = { Text(text = if (source.isPinned) "取消置顶" else "置顶") },
                leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onTogglePinned()
                }
            )
            DropdownMenuItem(
                text = { Text(text = "删除") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

// ==================== 文章行 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleRow(
    article: PhoneArticleEntity,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    canDeleteArticle: Boolean
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        AppListCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onOpenArticle(article) },
                    onLongClick = {
                        if (canDeleteArticle) {
                            menuExpanded = true
                        }
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = article.title.ifBlank { article.url },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val sourceLabel = article.rssSourceTitle?.takeIf { it.isNotBlank() } ?: article.siteName
                if (sourceLabel.isNotBlank()) {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val summary = article.excerpt.ifBlank { article.contentText }
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { onToggleFavorite(article) }) {
                        Icon(
                            imageVector = if (article.favoriteSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (article.favoriteSaved) "取消收藏" else "收藏",
                            tint = if (article.favoriteSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onToggleWatchLater(article) }) {
                        Icon(
                            imageVector = if (article.watchLaterSaved) Icons.Default.WatchLater else Icons.Default.Schedule,
                            contentDescription = if (article.watchLaterSaved) "移出稍后" else "稍后再看",
                            tint = if (article.watchLaterSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (article.url.isNotBlank() && !ImportedContentIds.isImportedContentUrl(article.url)) {
                        IconButton(onClick = { onOpenOriginalLink(article.url) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "原网页")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(text = "删除") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onDeleteArticle(article)
                }
            )
        }
    }
}

fun canDeleteArticle(article: PhoneArticleEntity): Boolean {
    return article.independentSaved ||
        ImportedContentIds.isImportedContentUrl(article.url) ||
        ImportedContentIds.isImportedContentUrl(article.rssSourceUrl)
}
