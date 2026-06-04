package com.lightningstudio.watchrss.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.viewmodel.MainBluetoothDevicePromptUi
import com.lightningstudio.watchrss.phone.viewmodel.MainBluetoothDeviceUi
import com.lightningstudio.watchrss.phone.viewmodel.MainConflictPromptUi
import com.lightningstudio.watchrss.phone.viewmodel.MainSyncProgressUi
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState
import com.lightningstudio.watchrss.phone.viewmodel.SharedImportPromptKind
import com.lightningstudio.watchrss.phone.viewmodel.SharedImportPromptUi

private enum class MainPage {
    DASHBOARD,
    RSS,
    SAVED,
    IMPORTS,
    CHANNEL
}

private enum class SavedSection {
    FAVORITES,
    WATCH_LATER
}

private enum class ImportSection {
    INDEPENDENT,
    LOCAL_CONTENT
}

private enum class UrlDialogMode {
    ARTICLE,
    RSS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onImportSharedLinkAsArticle: (String) -> Unit,
    onImportSharedLinkAsRss: (String) -> Unit,
    onConfirmSharedFileImport: (SharedImportPromptUi) -> Unit,
    onDismissSharedImport: () -> Unit,
    onSyncLibrary: () -> Unit,
    onChooseBluetoothDevice: (MainBluetoothDeviceUi) -> Unit,
    onDismissBluetoothDevicePrompt: () -> Unit,
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
    var page by rememberSaveable { mutableStateOf(MainPage.DASHBOARD) }
    var savedSection by rememberSaveable { mutableStateOf(SavedSection.FAVORITES) }
    var importSection by rememberSaveable { mutableStateOf(ImportSection.INDEPENDENT) }
    var selectedSourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var urlDialogMode by remember { mutableStateOf<UrlDialogMode?>(null) }
    val uriHandler = LocalUriHandler.current

    val articlesBySource = remember(uiState.rssArticles) {
        uiState.rssArticles.groupBy { it.rssSourceUrl.orEmpty() }
    }
    val selectedSource = selectedSourceUrl?.let { url ->
        uiState.rssSources.firstOrNull { it.url == url }
    }
    val selectedBottomPage = if (page == MainPage.CHANNEL) MainPage.RSS else page

    BackHandler(enabled = page != MainPage.DASHBOARD) {
        if (page == MainPage.CHANNEL) {
            selectedSourceUrl = null
            page = MainPage.RSS
        } else {
            selectedSourceUrl = null
            page = MainPage.DASHBOARD
        }
    }

    Scaffold(
        topBar = {
            MainTopBar(
                page = page,
                selectedSource = selectedSource,
                canRefreshRss = uiState.rssSources.isNotEmpty() && !uiState.isBusy,
                canRefreshSource = selectedSource != null &&
                    selectedSource.url !in uiState.refreshingRssSourceUrls &&
                    !uiState.isBusy,
                onBack = {
                    selectedSourceUrl = null
                    page = MainPage.RSS
                },
                onRefreshAllRssSources = onRefreshAllRssSources,
                onRefreshSelectedSource = { selectedSource?.let(onRefreshRssSource) },
                onExportBluetoothLog = onExportBluetoothLog
            )
        },
        bottomBar = {
            MainNavigationBar(
                selectedPage = selectedBottomPage,
                onSelectPage = { destination ->
                    selectedSourceUrl = null
                    page = destination
                }
            )
        },
        floatingActionButton = {
            MainFloatingActionButton(
                page = page,
                importSection = importSection,
                isBusy = uiState.isBusy,
                selectedSource = selectedSource,
                selectedSourceRefreshing = selectedSourceUrl?.let { it in uiState.refreshingRssSourceUrls } == true,
                onSyncLibrary = onSyncLibrary,
                onAddRssSource = { urlDialogMode = UrlDialogMode.RSS },
                onRefreshSelectedSource = { selectedSource?.let(onRefreshRssSource) },
                onImportArticle = { urlDialogMode = UrlDialogMode.ARTICLE },
                onImportFile = onImportFile
            )
        }
    ) { contentPadding ->
        when (page) {
            MainPage.DASHBOARD -> DashboardPage(
                uiState = uiState,
                articlesBySource = articlesBySource,
                contentPadding = contentPadding,
                onSyncLibrary = onSyncLibrary,
                onExportBluetoothLog = onExportBluetoothLog,
                onOpenRss = { page = MainPage.RSS },
                onOpenFavorites = {
                    savedSection = SavedSection.FAVORITES
                    page = MainPage.SAVED
                },
                onOpenWatchLater = {
                    savedSection = SavedSection.WATCH_LATER
                    page = MainPage.SAVED
                },
                onOpenIndependent = {
                    importSection = ImportSection.INDEPENDENT
                    page = MainPage.IMPORTS
                },
                onOpenImportedContent = {
                    importSection = ImportSection.LOCAL_CONTENT
                    page = MainPage.IMPORTS
                },
                onDismissMessage = onDismissMessage
            )

            MainPage.RSS -> RssPage(
                uiState = uiState,
                articlesBySource = articlesBySource,
                contentPadding = contentPadding,
                onOpenSource = { source ->
                    selectedSourceUrl = source.url
                    page = MainPage.CHANNEL
                },
                onMoveToTop = onMoveRssSourceToTop,
                onTogglePinned = onToggleRssSourcePinned,
                onDelete = onDeleteRssSource,
                onRefreshAllRssSources = onRefreshAllRssSources
            )

            MainPage.CHANNEL -> ChannelPage(
                source = selectedSource,
                articles = selectedSourceUrl?.let { articlesBySource[it] }.orEmpty(),
                isRefreshing = selectedSourceUrl?.let { it in uiState.refreshingRssSourceUrls } == true,
                contentPadding = contentPadding,
                onRefreshSource = { selectedSource?.let(onRefreshRssSource) },
                onOpenArticle = onOpenArticle,
                onOpenOriginalLink = { uriHandler.openUri(it) },
                onToggleFavorite = onToggleFavorite,
                onToggleWatchLater = onToggleWatchLater,
                onDeleteArticle = onDeleteArticle
            )

            MainPage.SAVED -> SavedPage(
                section = savedSection,
                uiState = uiState,
                contentPadding = contentPadding,
                onSectionChange = { savedSection = it },
                onOpenArticle = onOpenArticle,
                onOpenOriginalLink = { uriHandler.openUri(it) },
                onToggleFavorite = onToggleFavorite,
                onToggleWatchLater = onToggleWatchLater,
                onDeleteArticle = onDeleteArticle
            )

            MainPage.IMPORTS -> ImportsPage(
                section = importSection,
                uiState = uiState,
                contentPadding = contentPadding,
                onSectionChange = { importSection = it },
                onUrlChange = onUrlChange,
                onImportArticle = onImportArticle,
                onImportFile = onImportFile,
                onAddRssSource = onAddRssSource,
                onClearImportedContent = onClearImportedContent,
                onOpenArticle = onOpenArticle,
                onOpenOriginalLink = { uriHandler.openUri(it) },
                onToggleFavorite = onToggleFavorite,
                onToggleWatchLater = onToggleWatchLater,
                onDeleteArticle = onDeleteArticle
            )
        }
    }

    urlDialogMode?.let { mode ->
        UrlEntryDialog(
            mode = mode,
            urlInput = uiState.urlInput,
            enabled = !uiState.isBusy,
            onUrlChange = onUrlChange,
            onConfirm = {
                urlDialogMode = null
                when (mode) {
                    UrlDialogMode.ARTICLE -> onImportArticle()
                    UrlDialogMode.RSS -> onAddRssSource()
                }
            },
            onDismiss = { urlDialogMode = null }
        )
    }
    uiState.conflictPrompt?.let { prompt ->
        MainScreenDeleteConflictDialog(
            prompt = prompt,
            onChooseResolution = onChooseConflictResolution,
            onShowManualOptions = onShowManualConflictOptions
        )
    }
    uiState.sharedImportPrompt?.let { prompt ->
        MainScreenSharedImportDialog(
            prompt = prompt,
            onImportLinkAsArticle = onImportSharedLinkAsArticle,
            onImportLinkAsRss = onImportSharedLinkAsRss,
            onConfirmFileImport = onConfirmSharedFileImport,
            onDismiss = onDismissSharedImport
        )
    }
    uiState.bluetoothDevicePrompt?.let { prompt ->
        MainScreenBluetoothDeviceChooserDialog(
            prompt = prompt,
            onChooseDevice = onChooseBluetoothDevice,
            onDismiss = onDismissBluetoothDevicePrompt
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    page: MainPage,
    selectedSource: PhoneRssSourceEntity?,
    canRefreshRss: Boolean,
    canRefreshSource: Boolean,
    onBack: () -> Unit,
    onRefreshAllRssSources: () -> Unit,
    onRefreshSelectedSource: () -> Unit,
    onExportBluetoothLog: () -> Unit
) {
    val title = when (page) {
        MainPage.DASHBOARD -> "腕上RSS"
        MainPage.RSS -> "RSS 频道"
        MainPage.SAVED -> "保存列表"
        MainPage.IMPORTS -> "导入内容"
        MainPage.CHANNEL -> selectedSource?.title?.takeIf { it.isNotBlank() } ?: "频道"
    }
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            if (page == MainPage.CHANNEL) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = {
            when (page) {
                MainPage.DASHBOARD -> IconButton(onClick = onExportBluetoothLog) {
                    Icon(Icons.Default.BugReport, contentDescription = "导出蓝牙日志")
                }
                MainPage.RSS -> IconButton(
                    onClick = onRefreshAllRssSources,
                    enabled = canRefreshRss
                ) {
                    Icon(Icons.Default.Sync, contentDescription = "刷新 RSS")
                }
                MainPage.CHANNEL -> IconButton(
                    onClick = onRefreshSelectedSource,
                    enabled = canRefreshSource
                ) {
                    Icon(Icons.Default.Sync, contentDescription = "刷新频道")
                }
                else -> {}
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun MainNavigationBar(
    selectedPage: MainPage,
    onSelectPage: (MainPage) -> Unit
) {
    NavigationBar {
        listOf(
            MainPage.DASHBOARD,
            MainPage.RSS,
            MainPage.SAVED,
            MainPage.IMPORTS
        ).forEach { destination ->
            NavigationBarItem(
                selected = selectedPage == destination,
                onClick = { onSelectPage(destination) },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            MainPage.DASHBOARD -> Icons.Default.Home
                            MainPage.RSS -> Icons.Default.RssFeed
                            MainPage.SAVED -> Icons.Default.Favorite
                            MainPage.IMPORTS -> Icons.Default.FileOpen
                            MainPage.CHANNEL -> Icons.Default.RssFeed
                        },
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = when (destination) {
                            MainPage.DASHBOARD -> "总览"
                            MainPage.RSS -> "RSS"
                            MainPage.SAVED -> "保存"
                            MainPage.IMPORTS -> "导入"
                            MainPage.CHANNEL -> "频道"
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun MainFloatingActionButton(
    page: MainPage,
    importSection: ImportSection,
    isBusy: Boolean,
    selectedSource: PhoneRssSourceEntity?,
    selectedSourceRefreshing: Boolean,
    onSyncLibrary: () -> Unit,
    onAddRssSource: () -> Unit,
    onRefreshSelectedSource: () -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit
) {
    when (page) {
        MainPage.DASHBOARD -> {
            if (!isBusy) {
                ExtendedFloatingActionButton(
                    onClick = onSyncLibrary,
                    icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                    text = { Text("同步手表") }
                )
            }
        }
        MainPage.RSS -> ExtendedFloatingActionButton(
            onClick = onAddRssSource,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("添加 RSS") }
        )
        MainPage.CHANNEL -> {
            if (selectedSource != null && !selectedSourceRefreshing && !isBusy) {
                ExtendedFloatingActionButton(
                    onClick = onRefreshSelectedSource,
                    icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                    text = { Text("刷新") }
                )
            }
        }
        MainPage.IMPORTS -> ExtendedFloatingActionButton(
            onClick = if (importSection == ImportSection.INDEPENDENT) onImportArticle else onImportFile,
            icon = {
                Icon(
                    if (importSection == ImportSection.INDEPENDENT) Icons.Default.Add else Icons.Default.FileOpen,
                    contentDescription = null
                )
            },
            text = {
                Text(if (importSection == ImportSection.INDEPENDENT) "添加文章" else "导入文件")
            }
        )
        MainPage.SAVED -> {}
    }
}

@Composable
private fun DashboardPage(
    uiState: MainUiState,
    articlesBySource: Map<String, List<PhoneArticleEntity>>,
    contentPadding: PaddingValues,
    onSyncLibrary: () -> Unit,
    onExportBluetoothLog: () -> Unit,
    onOpenRss: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenIndependent: () -> Unit,
    onOpenImportedContent: () -> Unit,
    onDismissMessage: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = mainContentPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SyncStatusCard(
                message = uiState.message,
                error = uiState.error,
                syncProgress = uiState.syncProgress,
                isBusy = uiState.isBusy,
                onSyncLibrary = onSyncLibrary,
                onExportBluetoothLog = onExportBluetoothLog,
                onDismissMessage = onDismissMessage
            )
        }
        item {
            LibrarySummaryCard(
                rssSourceCount = uiState.rssSources.size,
                rssArticleCount = articlesBySource.values.sumOf { it.size },
                favoriteCount = uiState.favorites.size,
                watchLaterCount = uiState.watchLater.size,
                independentCount = uiState.independentArticles.size,
                importedContentCount = uiState.importedContentArticles.size,
                onOpenRss = onOpenRss,
                onOpenFavorites = onOpenFavorites,
                onOpenWatchLater = onOpenWatchLater,
                onOpenIndependent = onOpenIndependent,
                onOpenImportedContent = onOpenImportedContent
            )
        }
    }
}

@Composable
private fun SyncStatusCard(
    message: String?,
    error: String?,
    syncProgress: MainSyncProgressUi?,
    isBusy: Boolean,
    onSyncLibrary: () -> Unit,
    onExportBluetoothLog: () -> Unit,
    onDismissMessage: () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "蓝牙同步",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "已配对手表 · RFCOMM",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
            }
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
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${syncProgress.percent}%",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            message?.takeIf { it.isNotBlank() }?.let {
                AssistChip(
                    onClick = onDismissMessage,
                    label = {
                        Text(
                            text = it,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
            error?.takeIf { it.isNotBlank() }?.let {
                AssistChip(
                    onClick = onDismissMessage,
                    label = {
                        Text(
                            text = it,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onSyncLibrary,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("同步")
                }
                OutlinedButton(
                    onClick = onExportBluetoothLog,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("日志")
                }
            }
        }
    }
}

@Composable
private fun LibrarySummaryCard(
    rssSourceCount: Int,
    rssArticleCount: Int,
    favoriteCount: Int,
    watchLaterCount: Int,
    independentCount: Int,
    importedContentCount: Int,
    onOpenRss: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenIndependent: () -> Unit,
    onOpenImportedContent: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "资料库",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            SummaryRow {
                SummaryTile(
                    title = "RSS",
                    value = "$rssSourceCount 源",
                    supporting = "$rssArticleCount 篇",
                    icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
                    onClick = onOpenRss,
                    modifier = Modifier.weight(1f)
                )
                SummaryTile(
                    title = "收藏",
                    value = "$favoriteCount 篇",
                    supporting = "已保存",
                    icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                    onClick = onOpenFavorites,
                    modifier = Modifier.weight(1f)
                )
            }
            SummaryRow {
                SummaryTile(
                    title = "稍后",
                    value = "$watchLaterCount 篇",
                    supporting = "待阅读",
                    icon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                    onClick = onOpenWatchLater,
                    modifier = Modifier.weight(1f)
                )
                SummaryTile(
                    title = "独立文章",
                    value = "$independentCount 篇",
                    supporting = "网页导入",
                    icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                    onClick = onOpenIndependent,
                    modifier = Modifier.weight(1f)
                )
            }
            SummaryRow {
                SummaryTile(
                    title = "本地导入",
                    value = "$importedContentCount 篇",
                    supporting = "TXT / EPUB",
                    icon = { Icon(Icons.Default.Description, contentDescription = null) },
                    onClick = onOpenImportedContent,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun SummaryTile(
    title: String,
    value: String,
    supporting: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon()
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RssPage(
    uiState: MainUiState,
    articlesBySource: Map<String, List<PhoneArticleEntity>>,
    contentPadding: PaddingValues,
    onOpenSource: (PhoneRssSourceEntity) -> Unit,
    onMoveToTop: (PhoneRssSourceEntity) -> Unit,
    onTogglePinned: (PhoneRssSourceEntity) -> Unit,
    onDelete: (PhoneRssSourceEntity) -> Unit,
    onRefreshAllRssSources: () -> Unit
) {
    PullToRefreshBox(
        isRefreshing = uiState.refreshingRssSourceUrls.isNotEmpty(),
        onRefresh = onRefreshAllRssSources,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = mainContentPadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.rssSources.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
                        title = "暂无 RSS 源",
                        text = "点击右下角添加 RSS"
                    )
                }
            } else {
                items(uiState.rssSources, key = { it.url }) { source ->
                    MainScreenSourceRow(
                        source = source,
                        articleCount = articlesBySource[source.url].orEmpty().size,
                        onClick = { onOpenSource(source) },
                        onMoveToTop = { onMoveToTop(source) },
                        onTogglePinned = { onTogglePinned(source) },
                        onDelete = { onDelete(source) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelPage(
    source: PhoneRssSourceEntity?,
    articles: List<PhoneArticleEntity>,
    isRefreshing: Boolean,
    contentPadding: PaddingValues,
    onRefreshSource: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefreshSource,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = mainContentPadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (source == null || articles.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                        title = "暂无文章",
                        text = if (source == null) "频道不存在" else "下拉或点击刷新"
                    )
                }
            } else {
                items(articles, key = { it.articleId }) { article ->
                    MainScreenArticleRow(
                        article = article,
                        onOpenArticle = onOpenArticle,
                        onOpenOriginalLink = onOpenOriginalLink,
                        onToggleFavorite = onToggleFavorite,
                        onToggleWatchLater = onToggleWatchLater,
                        onDeleteArticle = onDeleteArticle,
                        mainScreenCanDeleteArticle = mainScreenCanDeleteArticle(article)
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedPage(
    section: SavedSection,
    uiState: MainUiState,
    contentPadding: PaddingValues,
    onSectionChange: (SavedSection) -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit
) {
    val articles = when (section) {
        SavedSection.FAVORITES -> uiState.favorites
        SavedSection.WATCH_LATER -> uiState.watchLater
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = mainContentPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = section == SavedSection.FAVORITES,
                    onClick = { onSectionChange(SavedSection.FAVORITES) },
                    label = { Text("收藏 ${uiState.favorites.size}") },
                    leadingIcon = {
                        Icon(Icons.Default.Favorite, contentDescription = null)
                    }
                )
                FilterChip(
                    selected = section == SavedSection.WATCH_LATER,
                    onClick = { onSectionChange(SavedSection.WATCH_LATER) },
                    label = { Text("稍后 ${uiState.watchLater.size}") },
                    leadingIcon = {
                        Icon(Icons.Default.Bookmark, contentDescription = null)
                    }
                )
            }
        }
        if (articles.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = {
                        Icon(
                            if (section == SavedSection.FAVORITES) Icons.Default.FavoriteBorder else Icons.Default.BookmarkBorder,
                            contentDescription = null
                        )
                    },
                    title = if (section == SavedSection.FAVORITES) "暂无收藏" else "暂无稍后再看",
                    text = "在文章列表中标记后会显示在这里"
                )
            }
        } else {
            items(articles, key = { it.articleId }) { article ->
                MainScreenArticleRow(
                    article = article,
                    onOpenArticle = onOpenArticle,
                    onOpenOriginalLink = onOpenOriginalLink,
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatchLater = onToggleWatchLater,
                    onDeleteArticle = onDeleteArticle,
                    mainScreenCanDeleteArticle = mainScreenCanDeleteArticle(article)
                )
            }
        }
    }
}

@Composable
private fun ImportsPage(
    section: ImportSection,
    uiState: MainUiState,
    contentPadding: PaddingValues,
    onSectionChange: (ImportSection) -> Unit,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onClearImportedContent: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit
) {
    val articles = when (section) {
        ImportSection.INDEPENDENT -> uiState.independentArticles
        ImportSection.LOCAL_CONTENT -> uiState.importedContentArticles
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = mainContentPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ImportActionsCard(
                urlInput = uiState.urlInput,
                enabled = !uiState.isBusy,
                importedContentCount = uiState.importedContentArticles.size,
                onUrlChange = onUrlChange,
                onImportArticle = onImportArticle,
                onImportFile = onImportFile,
                onAddRssSource = onAddRssSource,
                onClearImportedContent = onClearImportedContent
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = section == ImportSection.INDEPENDENT,
                    onClick = { onSectionChange(ImportSection.INDEPENDENT) },
                    label = { Text("独立 ${uiState.independentArticles.size}") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) }
                )
                FilterChip(
                    selected = section == ImportSection.LOCAL_CONTENT,
                    onClick = { onSectionChange(ImportSection.LOCAL_CONTENT) },
                    label = { Text("本地 ${uiState.importedContentArticles.size}") },
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) }
                )
            }
        }
        if (articles.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = {
                        Icon(
                            if (section == ImportSection.INDEPENDENT) Icons.AutoMirrored.Filled.Article else Icons.Default.Description,
                            contentDescription = null
                        )
                    },
                    title = if (section == ImportSection.INDEPENDENT) "暂无独立文章" else "暂无本地导入",
                    text = if (section == ImportSection.INDEPENDENT) "输入网页地址后添加" else "导入 TXT 或 EPUB 文件"
                )
            }
        } else {
            items(articles, key = { it.articleId }) { article ->
                MainScreenArticleRow(
                    article = article,
                    onOpenArticle = onOpenArticle,
                    onOpenOriginalLink = onOpenOriginalLink,
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatchLater = onToggleWatchLater,
                    onDeleteArticle = onDeleteArticle,
                    mainScreenCanDeleteArticle = mainScreenCanDeleteArticle(article)
                )
            }
        }
    }
}

@Composable
private fun ImportActionsCard(
    urlInput: String,
    enabled: Boolean,
    importedContentCount: Int,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onClearImportedContent: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "添加内容",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                label = { Text("网页或 RSS 地址") },
                placeholder = { Text("https://example.com/feed.xml") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onAddRssSource,
                    enabled = enabled && urlInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.RssFeed, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RSS")
                }
                Button(
                    onClick = onImportArticle,
                    enabled = enabled && urlInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("文章")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onImportFile,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("文件")
                }
                OutlinedButton(
                    onClick = onClearImportedContent,
                    enabled = enabled && importedContentCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("清空")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainScreenSourceRow(
    source: PhoneRssSourceEntity,
    articleCount: Int,
    onClick: () -> Unit,
    onMoveToTop: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            )
    ) {
        Box {
            ListItem(
                headlineContent = {
                    Text(
                        text = source.title.ifBlank { source.url },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        source.description.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = buildString {
                                if (source.isPinned) append("已置顶 · ")
                                append("$articleCount 篇文章")
                            }
                        )
                    }
                },
                leadingContent = {
                    Icon(Icons.Default.RssFeed, contentDescription = null)
                },
                trailingContent = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "频道操作")
                    }
                }
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                DropdownMenuItem(
                    text = { Text("移到顶部") },
                    leadingIcon = { Icon(Icons.Default.VerticalAlignTop, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onMoveToTop()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (source.isPinned) "取消置顶" else "置顶") },
                    leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onTogglePinned()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainScreenArticleRow(
    article: PhoneArticleEntity,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    mainScreenCanDeleteArticle: Boolean
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpenArticle(article) },
                onLongClick = {
                    if (mainScreenCanDeleteArticle) menuExpanded = true
                }
            )
    ) {
        Box {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = article.title.ifBlank { article.url },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (mainScreenCanDeleteArticle) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "文章操作")
                        }
                    }
                }
                val sourceLabel = article.rssSourceTitle?.takeIf { it.isNotBlank() } ?: article.siteName
                if (sourceLabel.isNotBlank()) {
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val summary = article.excerpt.ifBlank { article.contentText }
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onToggleFavorite(article) }) {
                        Icon(
                            imageVector = if (article.favoriteSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (article.favoriteSaved) "取消收藏" else "收藏"
                        )
                    }
                    IconButton(onClick = { onToggleWatchLater(article) }) {
                        Icon(
                            imageVector = if (article.watchLaterSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (article.watchLaterSaved) "移出稍后再看" else "稍后再看"
                        )
                    }
                    if (article.url.isNotBlank() && !ImportedContentIds.isImportedContentUrl(article.url)) {
                        IconButton(onClick = { onOpenOriginalLink(article.url) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "打开原网页")
                        }
                    }
                }
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDeleteArticle(article)
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyStateCard(
    icon: @Composable () -> Unit,
    title: String,
    text: String
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            icon()
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UrlEntryDialog(
    mode: UrlDialogMode,
    urlInput: String,
    enabled: Boolean,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (mode == UrlDialogMode.RSS) "添加 RSS 源" else "添加独立文章")
        },
        text = {
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                label = { Text(if (mode == UrlDialogMode.RSS) "RSS 地址" else "网页地址") },
                placeholder = { Text("https://example.com/feed.xml") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = enabled
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = enabled && urlInput.isNotBlank()
            ) {
                Icon(
                    if (mode == UrlDialogMode.RSS) Icons.Default.RssFeed else Icons.AutoMirrored.Filled.Article,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun MainScreenDeleteConflictDialog(
    prompt: MainConflictPromptUi,
    onChooseResolution: (PhoneSyncConflictResolution) -> Unit,
    onShowManualOptions: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(if (prompt.manual) "保留/删除" else "双端内容有冲突")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "检测到 ${prompt.conflicts.size} 篇内容一端已删除，另一端仍保留。",
                    style = MaterialTheme.typography.bodyMedium
                )
                prompt.conflicts.firstOrNull()?.let { conflict ->
                    Text(
                        text = conflict.title.ifBlank { conflict.url },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onShowManualOptions
                    ) {
                        Text("手动选择")
                    }
                }
            }
        }
    )
}

@Composable
private fun MainScreenBluetoothDeviceChooserDialog(
    prompt: MainBluetoothDevicePromptUi,
    onChooseDevice: (MainBluetoothDeviceUi) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择同步手表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prompt.devices.forEach { device ->
                    ElevatedCard(
                        onClick = { onChooseDevice(device) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = device.name.ifBlank { "未知手表" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = device.address,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.Sync, contentDescription = null)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun MainScreenSharedImportDialog(
    prompt: SharedImportPromptUi,
    onImportLinkAsArticle: (String) -> Unit,
    onImportLinkAsRss: (String) -> Unit,
    onConfirmFileImport: (SharedImportPromptUi) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (prompt.kind) {
                    SharedImportPromptKind.LINK -> "导入链接"
                    SharedImportPromptKind.FILE -> "导入文件"
                }
            )
        },
        text = {
            when (prompt.kind) {
                SharedImportPromptKind.LINK -> Text(
                    text = prompt.url,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                SharedImportPromptKind.FILE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = prompt.fileName.ifBlank { "未命名文件" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    prompt.mimeType?.takeIf { it.isNotBlank() }?.let { mimeType ->
                        Text(
                            text = mimeType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (prompt.kind) {
                    SharedImportPromptKind.LINK -> {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onImportLinkAsRss(prompt.url) }
                        ) {
                            Icon(Icons.Default.RssFeed, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("RSS 源")
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onImportLinkAsArticle(prompt.url) }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("独立文章")
                        }
                    }
                    SharedImportPromptKind.FILE -> {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onConfirmFileImport(prompt) }
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导入")
                        }
                    }
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss
                ) {
                    Text("取消")
                }
            }
        }
    )
}

private fun mainContentPadding(scaffoldPadding: PaddingValues): PaddingValues =
    PaddingValues(
        start = 16.dp,
        top = scaffoldPadding.calculateTopPadding() + 12.dp,
        end = 16.dp,
        bottom = scaffoldPadding.calculateBottomPadding() + 88.dp
    )

private fun mainScreenCanDeleteArticle(article: PhoneArticleEntity): Boolean {
    return article.independentSaved ||
        ImportedContentIds.isImportedContentUrl(article.url) ||
        ImportedContentIds.isImportedContentUrl(article.rssSourceUrl)
}
