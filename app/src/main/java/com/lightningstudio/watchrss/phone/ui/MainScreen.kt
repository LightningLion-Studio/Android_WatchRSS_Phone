package com.lightningstudio.watchrss.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.viewmodel.MainConflictPromptUi
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState
import com.lightningstudio.watchrss.phone.viewmodel.MainSyncProgressUi

private enum class MainPage {
    HOME,
    CHANNELS,
    FAVORITES,
    WATCH_LATER,
    INDEPENDENT,
    IMPORTED_CONTENT,
    CHANNEL
}

@Composable
fun MainScreen(
    uiState: MainUiState,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onSyncLibrary: () -> Unit,
    onExportBluetoothLog: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onMoveRssSourceToTop: (PhoneRssSourceEntity) -> Unit,
    onToggleRssSourcePinned: (PhoneRssSourceEntity) -> Unit,
    onDeleteRssSource: (PhoneRssSourceEntity) -> Unit,
    onRefreshAllRssSources: () -> Unit,
    onRefreshRssSource: (PhoneRssSourceEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    onClearImportedContent: () -> Unit,
    onChooseConflictResolution: (PhoneSyncConflictResolution) -> Unit,
    onShowManualConflictOptions: () -> Unit,
    onDismissMessage: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var page by rememberSaveable { mutableStateOf(MainPage.HOME) }
    var selectedSourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val articlesBySource = remember(uiState.rssArticles) {
        uiState.rssArticles.groupBy { it.rssSourceUrl.orEmpty() }
    }
    val selectedSource = uiState.rssSources.firstOrNull { it.url == selectedSourceUrl }
    val goHome = {
        selectedSourceUrl = null
        page = MainPage.HOME
    }

    BackHandler(enabled = page != MainPage.HOME) {
        goHome()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (page) {
            MainPage.HOME -> PageColumn {
                HomePage(
                    uiState = uiState,
                    articlesBySource = articlesBySource,
                    onUrlChange = onUrlChange,
                    onImportArticle = onImportArticle,
                    onImportFile = onImportFile,
                    onAddRssSource = onAddRssSource,
                    onSyncLibrary = onSyncLibrary,
                    onExportBluetoothLog = onExportBluetoothLog,
                    onOpenChannels = { page = MainPage.CHANNELS },
                    onOpenFavorites = { page = MainPage.FAVORITES },
                    onOpenWatchLater = { page = MainPage.WATCH_LATER },
                    onOpenIndependent = { page = MainPage.INDEPENDENT },
                    onOpenImportedContent = { page = MainPage.IMPORTED_CONTENT },
                    onDismissMessage = onDismissMessage
                )
            }

            MainPage.CHANNELS -> RefreshablePageColumn(
                isRefreshing = uiState.refreshingRssSourceUrls.isNotEmpty(),
                onRefresh = onRefreshAllRssSources
            ) {
                ChannelsPage(
                    sources = uiState.rssSources,
                    articlesBySource = articlesBySource,
                    onBack = { page = MainPage.HOME },
                    onOpenSource = { source ->
                        selectedSourceUrl = source.url
                        page = MainPage.CHANNEL
                    },
                    onMoveToTop = onMoveRssSourceToTop,
                    onTogglePinned = onToggleRssSourcePinned,
                    onDelete = onDeleteRssSource
                )
            }

            MainPage.FAVORITES -> PageColumn {
                ArticleListPage(
                    title = "收藏",
                    emptyText = "暂无收藏",
                    articles = uiState.favorites,
                    onBack = { page = MainPage.HOME },
                    onOpenArticle = onOpenArticle,
                    onOpenOriginalLink = { uriHandler.openUri(it) },
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatchLater = onToggleWatchLater,
                    onDeleteArticle = onDeleteArticle,
                    canDeleteArticle = ::canDeleteArticle,
                    headerActionLabel = "清空",
                    onHeaderAction = onClearImportedContent,
                    headerActionEnabled = uiState.importedContentArticles.isNotEmpty()
                )
            }

            MainPage.WATCH_LATER -> PageColumn {
                ArticleListPage(
                    title = "稍后再看",
                    emptyText = "暂无稍后再看",
                    articles = uiState.watchLater,
                    onBack = { page = MainPage.HOME },
                    onOpenArticle = onOpenArticle,
                    onOpenOriginalLink = { uriHandler.openUri(it) },
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatchLater = onToggleWatchLater,
                    onDeleteArticle = onDeleteArticle,
                    canDeleteArticle = ::canDeleteArticle
                )
            }

            MainPage.INDEPENDENT -> PageColumn {
                ArticleListPage(
                    title = "独立文章",
                    emptyText = "暂无独立文章",
                    articles = uiState.independentArticles,
                    onBack = { page = MainPage.HOME },
                    onOpenArticle = onOpenArticle,
                    onOpenOriginalLink = { uriHandler.openUri(it) },
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatchLater = onToggleWatchLater,
                    onDeleteArticle = onDeleteArticle,
                    canDeleteArticle = ::canDeleteArticle
                )
            }

            MainPage.IMPORTED_CONTENT -> PageColumn {
                ArticleListPage(
                    title = "导入内容",
                    emptyText = "暂无导入内容",
                    articles = uiState.importedContentArticles,
                    onBack = { page = MainPage.HOME },
                    onOpenArticle = onOpenArticle,
                    onOpenOriginalLink = { uriHandler.openUri(it) },
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatchLater = onToggleWatchLater,
                    onDeleteArticle = onDeleteArticle,
                    canDeleteArticle = ::canDeleteArticle
                )
            }

            MainPage.CHANNEL -> RefreshablePageColumn(
                isRefreshing = selectedSourceUrl?.let { it in uiState.refreshingRssSourceUrls } == true,
                onRefresh = { selectedSource?.let(onRefreshRssSource) }
            ) {
                ArticleListPage(
                    title = selectedSource?.title ?: "频道",
                    emptyText = "此频道暂无文章",
                    articles = selectedSourceUrl?.let { articlesBySource[it] }.orEmpty(),
                    onBack = goHome,
                    onOpenArticle = onOpenArticle,
                    onOpenOriginalLink = { uriHandler.openUri(it) },
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatchLater = onToggleWatchLater,
                    onDeleteArticle = onDeleteArticle,
                    canDeleteArticle = ::canDeleteArticle
                )
            }
        }
    }
    uiState.conflictPrompt?.let { prompt ->
        DeleteConflictDialog(
            prompt = prompt,
            onChooseResolution = onChooseConflictResolution,
            onShowManualOptions = onShowManualConflictOptions
        )
    }
}

@Composable
private fun PageColumn(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshablePageColumn(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        PageColumn(content = content)
    }
}

@Composable
private fun HomePage(
    uiState: MainUiState,
    articlesBySource: Map<String, List<PhoneArticleEntity>>,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onSyncLibrary: () -> Unit,
    onExportBluetoothLog: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenIndependent: () -> Unit,
    onOpenImportedContent: () -> Unit,
    onDismissMessage: () -> Unit
) {
    Text(
        text = "WatchRSS 手机端",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )

    StatusCard(
        message = uiState.message,
        error = uiState.error,
        syncProgress = uiState.syncProgress,
        onDismissMessage = onDismissMessage
    )

    ImportAndSyncCard(
        urlInput = uiState.urlInput,
        enabled = !uiState.isBusy,
        onUrlChange = onUrlChange,
        onImportArticle = onImportArticle,
        onImportFile = onImportFile,
        onAddRssSource = onAddRssSource,
        onSyncLibrary = onSyncLibrary,
        onExportBluetoothLog = onExportBluetoothLog
    )

    LibraryEntryCard(
        rssSourceCount = uiState.rssSources.size,
        rssArticleCount = articlesBySource.values.sumOf { it.size },
        favoriteCount = uiState.favorites.size,
        watchLaterCount = uiState.watchLater.size,
        independentCount = uiState.independentArticles.size,
        importedContentCount = uiState.importedContentArticles.size,
        onOpenChannels = onOpenChannels,
        onOpenFavorites = onOpenFavorites,
        onOpenWatchLater = onOpenWatchLater,
        onOpenIndependent = onOpenIndependent,
        onOpenImportedContent = onOpenImportedContent
    )
}

@Composable
private fun StatusCard(
    message: String?,
    error: String?,
    syncProgress: MainSyncProgressUi?,
    onDismissMessage: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
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
                Text(
                    text = "进度:${syncProgress.percent}%",
                    style = MaterialTheme.typography.bodyMedium
                )
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

@Composable
private fun DeleteConflictDialog(
    prompt: MainConflictPromptUi,
    onChooseResolution: (PhoneSyncConflictResolution) -> Unit,
    onShowManualOptions: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(if (prompt.manual) "保留/删除" else "双端内容有冲突，请选择处理方式")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val countText = "检测到 ${prompt.conflicts.size} 篇内容一端已删除，另一端仍保留。"
                Text(text = countText, style = MaterialTheme.typography.bodyMedium)
                prompt.conflicts.firstOrNull()?.let { conflict ->
                    Text(
                        text = conflict.title.ifBlank { conflict.url },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (prompt.manual) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.KEEP_WATCH) }
                    ) {
                        Text("保留手表版本")
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.KEEP_PHONE) }
                    ) {
                        Text("保留手机版本")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.DELETE_CONTENT) }
                    ) {
                        Text("删除")
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.KEEP_LATEST) }
                    ) {
                        Text("保留最新操作")
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.MERGE_CONTENT) }
                    ) {
                        Text("合并内容")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.DELETE_CONTENT) }
                    ) {
                        Text("删除内容")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onShowManualOptions
                    ) {
                        Text("手动")
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ImportAndSyncCard(
    urlInput: String,
    enabled: Boolean,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onSyncLibrary: () -> Unit,
    onExportBluetoothLog: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "添加内容", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                label = { Text(text = "网页或 RSS 地址") },
                placeholder = { Text(text = "https://example.com/article-or-feed.xml") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportArticle,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "添加独立文章")
                }
                Button(
                    onClick = onAddRssSource,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "添加 RSS 源")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportFile,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "导入文件")
                }
                Button(
                    onClick = onSyncLibrary,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "同步手表")
                }
                OutlinedButton(
                    onClick = onExportBluetoothLog,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "导出日志")
                }
            }
        }
    }
}

@Composable
private fun LibraryEntryCard(
    rssSourceCount: Int,
    rssArticleCount: Int,
    favoriteCount: Int,
    watchLaterCount: Int,
    independentCount: Int,
    importedContentCount: Int,
    onOpenChannels: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenIndependent: () -> Unit,
    onOpenImportedContent: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = "资料库", style = MaterialTheme.typography.titleMedium)
            LibraryEntryRow(
                title = "频道",
                subtitle = "$rssSourceCount 个频道，$rssArticleCount 篇文章",
                onClick = onOpenChannels
            )
            LibraryEntryRow(
                title = "收藏",
                subtitle = "$favoriteCount 篇",
                onClick = onOpenFavorites
            )
            LibraryEntryRow(
                title = "稍后再看",
                subtitle = "$watchLaterCount 篇",
                onClick = onOpenWatchLater
            )
            LibraryEntryRow(
                title = "独立文章",
                subtitle = "$independentCount 篇",
                onClick = onOpenIndependent
            )
            LibraryEntryRow(
                title = "导入内容",
                subtitle = "$importedContentCount 篇",
                onClick = onOpenImportedContent
            )
        }
    }
}

@Composable
private fun LibraryEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChannelsPage(
    sources: List<PhoneRssSourceEntity>,
    articlesBySource: Map<String, List<PhoneArticleEntity>>,
    onBack: () -> Unit,
    onOpenSource: (PhoneRssSourceEntity) -> Unit,
    onMoveToTop: (PhoneRssSourceEntity) -> Unit,
    onTogglePinned: (PhoneRssSourceEntity) -> Unit,
    onDelete: (PhoneRssSourceEntity) -> Unit
) {
    PageHeader(title = "频道", onBack = onBack)
    if (sources.isEmpty()) {
        Text(text = "暂无频道", style = MaterialTheme.typography.bodyMedium)
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
private fun SourceRow(
    source: PhoneRssSourceEntity,
    articleCount: Int,
    onClick: () -> Unit,
    onMoveToTop: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
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
                onClick = {
                    menuExpanded = false
                    onMoveToTop()
                }
            )
            DropdownMenuItem(
                text = { Text(text = if (source.isPinned) "取消置顶" else "置顶") },
                onClick = {
                    menuExpanded = false
                    onTogglePinned()
                }
            )
            DropdownMenuItem(
                text = { Text(text = "删除") },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun ArticleListPage(
    title: String,
    emptyText: String,
    articles: List<PhoneArticleEntity>,
    onBack: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    canDeleteArticle: (PhoneArticleEntity) -> Boolean,
    headerActionLabel: String? = null,
    onHeaderAction: (() -> Unit)? = null,
    headerActionEnabled: Boolean = false
) {
    PageHeader(
        title = "$title (${articles.size})",
        onBack = onBack,
        actionLabel = headerActionLabel,
        actionEnabled = headerActionEnabled,
        onAction = onHeaderAction
    )
    if (articles.isEmpty()) {
        Text(text = emptyText, style = MaterialTheme.typography.bodyMedium)
        return
    }
    articles.forEach { article ->
        ArticleRow(
            article = article,
            onOpenArticle = onOpenArticle,
            onOpenOriginalLink = onOpenOriginalLink,
            onToggleFavorite = onToggleFavorite,
            onToggleWatchLater = onToggleWatchLater,
            onDeleteArticle = onDeleteArticle,
            canDeleteArticle = canDeleteArticle(article)
        )
    }
}

@Composable
private fun PageHeader(
    title: String,
    onBack: () -> Unit,
    actionLabel: String? = null,
    actionEnabled: Boolean = false,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack) {
            Text(text = "返回")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            OutlinedButton(
                onClick = onAction,
                enabled = actionEnabled
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArticleRow(
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
        Card(
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
                    OutlinedButton(onClick = { onToggleFavorite(article) }) {
                        Text(text = if (article.favoriteSaved) "取消收藏" else "收藏")
                    }
                    OutlinedButton(onClick = { onToggleWatchLater(article) }) {
                        Text(text = if (article.watchLaterSaved) "移出稍后" else "稍后再看")
                    }
                    if (article.url.isNotBlank() && !ImportedContentIds.isImportedContentUrl(article.url)) {
                        OutlinedButton(
                            onClick = { onOpenOriginalLink(article.url) },
                            enabled = true
                        ) {
                            Text(text = "原网页")
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
                onClick = {
                    menuExpanded = false
                    onDeleteArticle(article)
                }
            )
        }
    }
}

private fun canDeleteArticle(article: PhoneArticleEntity): Boolean {
    return article.independentSaved ||
        ImportedContentIds.isImportedContentUrl(article.url) ||
        ImportedContentIds.isImportedContentUrl(article.rssSourceUrl)
}
