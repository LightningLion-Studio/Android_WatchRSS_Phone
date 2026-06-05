package com.lightningstudio.watchrss.phone.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private enum class MainPage {
    DASHBOARD,
    RSS,
    IMPORTS,
    CHANNEL
}

private val TopLevelMainPages = listOf(
    MainPage.DASHBOARD,
    MainPage.RSS,
    MainPage.IMPORTS
)

private fun MainPage.topLevelIndex(): Int = TopLevelMainPages.indexOf(this).coerceAtLeast(0)

private enum class UrlDialogMode {
    ARTICLE,
    RSS
}

private const val CONTENT_SOURCE_PREFIX = "source:"
private const val CONTENT_CHANNEL_FAVORITES = "virtual:favorites"
private const val CONTENT_CHANNEL_WATCH_LATER = "virtual:watch_later"
private const val CONTENT_CHANNEL_INDEPENDENT = "virtual:independent"
private const val CONTENT_CHANNEL_IMPORTED_TEXT = "virtual:imported_text"
private const val CHANNEL_TRANSITION_MS = 260
private const val CHANNEL_PREDICTIVE_EXIT_MS = 180
private const val CHANNEL_PREDICTIVE_EXIT_PROGRESS = 2f
private const val TAB_PREDICTIVE_EXIT_MS = 180
private const val TAB_PREDICTIVE_EXIT_PROGRESS = 1f

@Composable
private fun defaultMainElevatedCardColors() = CardDefaults.elevatedCardColors()

private data class MainContentChannel(
    val key: String,
    val title: String,
    val supportingText: String,
    val articleCount: Int,
    val source: PhoneRssSourceEntity? = null,
    val articles: List<PhoneArticleEntity> = emptyList(),
    val icon: MainContentChannelIcon,
    val emptyTitle: String,
    val emptyText: String,
    val canRefresh: Boolean = false,
    val canDrag: Boolean = false,
    val sortOrder: Long = 0L
)

private data class MainContentReorderRequest(
    val sourceUrlsInDisplayOrder: List<String>,
    val independentIndex: Int?
)

private enum class MainContentChannelIcon {
    RSS,
    BOOK,
    FAVORITE,
    WATCH_LATER,
    ARTICLE,
    IMPORTED
}

private sealed interface RecentImportEntry {
    val key: String
    val sortAt: Long

    data class Channel(
        val channel: MainContentChannel,
        override val sortAt: Long
    ) : RecentImportEntry {
        override val key: String = "channel:${channel.key}"
    }

    data class Article(
        val article: PhoneArticleEntity
    ) : RecentImportEntry {
        override val key: String = "article:${article.articleId}"
        override val sortAt: Long = maxOf(article.importedAt, article.updatedAt)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    onReorderContentChannels: (List<String>, Int?) -> Unit,
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
    var selectedContentChannelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lastContentChannelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var channelReturnPageName by rememberSaveable { mutableStateOf(MainPage.RSS.name) }
    var channelBackProgress by remember { mutableFloatStateOf(0f) }
    var tabBackProgress by remember { mutableFloatStateOf(0f) }
    var tabBackActive by remember { mutableStateOf(false) }
    var channelTabSwitchDestinationName by remember { mutableStateOf<String?>(null) }
    var channelTabSwitchProgress by remember { mutableFloatStateOf(0f) }
    var channelTabSwitchDirection by remember { mutableFloatStateOf(1f) }
    var suppressNextChannelExit by remember { mutableStateOf(false) }
    var urlDialogMode by remember { mutableStateOf<UrlDialogMode?>(null) }
    val pagerState = rememberPagerState(initialPage = MainPage.DASHBOARD.topLevelIndex()) {
        TopLevelMainPages.size
    }
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    val articlesBySource = remember(uiState.rssArticles) {
        uiState.rssArticles.groupBy { it.rssSourceUrl.orEmpty() }
    }
    val contentChannels = remember(uiState, articlesBySource) {
        buildContentChannels(uiState, articlesBySource)
    }
    val importedContentChannelKey = contentChannels.firstOrNull { channel ->
        channel.source?.url?.let(ImportedContentIds::isImportedTextSourceUrl) == true
    }?.key ?: CONTENT_CHANNEL_IMPORTED_TEXT
    val selectedContentChannel = selectedContentChannelKey?.let { key ->
        contentChannels.firstOrNull { it.key == key }
    }
    LaunchedEffect(selectedContentChannelKey) {
        selectedContentChannelKey?.let { key ->
            lastContentChannelKey = key
            channelBackProgress = 0f
        }
    }
    val animatedContentChannel = (selectedContentChannelKey ?: lastContentChannelKey)?.let { key ->
        contentChannels.firstOrNull { it.key == key }
    }
    val selectedSource = selectedContentChannel?.source
    val currentTopLevelPage = TopLevelMainPages.getOrElse(pagerState.currentPage) {
        MainPage.DASHBOARD
    }
    val channelReturnPage = runCatching { MainPage.valueOf(channelReturnPageName) }
        .getOrDefault(MainPage.RSS)
        .takeIf { it in TopLevelMainPages }
        ?: MainPage.RSS
    val channelTabSwitchDestination = channelTabSwitchDestinationName
        ?.let { runCatching { MainPage.valueOf(it) }.getOrNull() }
        ?.takeIf { it in TopLevelMainPages }
    val page = if (selectedContentChannel != null) MainPage.CHANNEL else currentTopLevelPage
    val selectedBottomPage = if (page == MainPage.CHANNEL) channelReturnPage else currentTopLevelPage

    fun navigateToTopLevelPage(destination: MainPage) {
        selectedContentChannelKey = null
        coroutineScope.launch {
            pagerState.animateScrollToPage(destination.topLevelIndex())
        }
    }

    fun navigateToContentChannel(channelKey: String, returnPage: MainPage = currentTopLevelPage) {
        val resolvedChannelKey = when (channelKey) {
            CONTENT_CHANNEL_IMPORTED_TEXT -> importedContentChannelKey
            else -> channelKey
        }
        channelReturnPageName = returnPage.name
        selectedContentChannelKey = resolvedChannelKey
    }

    fun switchChannelToTopLevelPage(destination: MainPage) {
        if (channelTabSwitchDestinationName != null || destination !in TopLevelMainPages) return
        val sourceIndex = channelReturnPage.topLevelIndex()
        val destinationIndex = destination.topLevelIndex()
        if (destinationIndex == sourceIndex) {
            navigateToTopLevelPage(destination)
            return
        }
        channelTabSwitchDestinationName = destination.name
        channelTabSwitchDirection = if (destinationIndex > sourceIndex) 1f else -1f
        channelTabSwitchProgress = 0f
        coroutineScope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(CHANNEL_TRANSITION_MS)
            ) { value, _ ->
                channelTabSwitchProgress = value
            }
            suppressNextChannelExit = true
            selectedContentChannelKey = null
            pagerState.scrollToPage(destinationIndex)
            delay(32L)
            channelTabSwitchDestinationName = null
            channelTabSwitchProgress = 0f
            suppressNextChannelExit = false
        }
    }

    PredictiveBackHandler(enabled = page == MainPage.CHANNEL) { backEvents ->
        try {
            backEvents.collect { backEvent ->
                channelBackProgress = backEvent.progress.coerceIn(0f, 1f)
            }
            animate(
                initialValue = channelBackProgress,
                targetValue = CHANNEL_PREDICTIVE_EXIT_PROGRESS,
                animationSpec = tween(CHANNEL_PREDICTIVE_EXIT_MS)
            ) { value, _ ->
                channelBackProgress = value
            }
            selectedContentChannelKey = null
            coroutineScope.launch {
                pagerState.animateScrollToPage(channelReturnPage.topLevelIndex())
            }
            delay(CHANNEL_TRANSITION_MS.toLong() + 32L)
            if (selectedContentChannelKey == null) {
                channelBackProgress = 0f
            }
        } catch (exception: CancellationException) {
            animate(
                initialValue = channelBackProgress,
                targetValue = 0f,
                animationSpec = tween(CHANNEL_TRANSITION_MS)
            ) { value, _ ->
                channelBackProgress = value
            }
        }
    }

    PredictiveBackHandler(enabled = page != MainPage.DASHBOARD && page != MainPage.CHANNEL) { backEvents ->
        try {
            tabBackActive = true
            tabBackProgress = 0f
            backEvents.collect { backEvent ->
                tabBackProgress = backEvent.progress.coerceIn(0f, 1f)
            }
            animate(
                initialValue = tabBackProgress,
                targetValue = TAB_PREDICTIVE_EXIT_PROGRESS,
                animationSpec = tween(TAB_PREDICTIVE_EXIT_MS)
            ) { value, _ ->
                tabBackProgress = value
            }
            pagerState.scrollToPage(MainPage.DASHBOARD.topLevelIndex())
            tabBackActive = false
            tabBackProgress = 0f
        } catch (exception: CancellationException) {
            animate(
                initialValue = tabBackProgress,
                targetValue = 0f,
                animationSpec = tween(CHANNEL_TRANSITION_MS)
            ) { value, _ ->
                tabBackProgress = value
            }
            tabBackActive = false
        }
    }

    Scaffold(
        topBar = {
            MainTopBar(
                page = page,
                selectedChannel = selectedContentChannel,
                canRefreshRss = uiState.rssSources.any { !ImportedContentIds.isImportedContentUrl(it.url) } && !uiState.isBusy,
                canRefreshSource = selectedContentChannel?.canRefresh == true &&
                    selectedSource != null &&
                    selectedSource.url !in uiState.refreshingRssSourceUrls &&
                    !uiState.isBusy,
                onBack = { navigateToTopLevelPage(channelReturnPage) },
                onRefreshAllRssSources = onRefreshAllRssSources,
                onRefreshSelectedSource = {
                    if (selectedContentChannel?.canRefresh == true) {
                        selectedSource?.let(onRefreshRssSource)
                    }
                },
                onExportBluetoothLog = onExportBluetoothLog
            )
        },
        bottomBar = {
            MainNavigationBar(
                selectedPage = selectedBottomPage,
                onSelectPage = { destination ->
                    if (page == MainPage.CHANNEL && destination != channelReturnPage) {
                        switchChannelToTopLevelPage(destination)
                    } else {
                        navigateToTopLevelPage(destination)
                    }
                }
            )
        },
        floatingActionButton = {
            MainFloatingActionButton(
                page = page,
                isBusy = uiState.isBusy,
                selectedSource = selectedSource,
                selectedSourceRefreshing = selectedSource?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                canRefreshSelectedSource = selectedContentChannel?.canRefresh == true,
                onSyncLibrary = onSyncLibrary,
                onAddRssSource = { urlDialogMode = UrlDialogMode.RSS },
                onRefreshSelectedSource = {
                    if (selectedContentChannel?.canRefresh == true) {
                        selectedSource?.let(onRefreshRssSource)
                    }
                }
            )
        }
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            channelTabSwitchDestination?.let { destination ->
                OpaquePageLayer(
                    modifier = Modifier
                        .fillMaxSize()
                        .channelTabSwitchTargetPreview(
                            progress = channelTabSwitchProgress,
                            direction = channelTabSwitchDirection
                        )
                        .zIndex(0.5f)
                ) {
                    when (destination) {
                        MainPage.DASHBOARD -> DashboardPage(
                            uiState = uiState,
                            articlesBySource = articlesBySource,
                            contentPadding = contentPadding,
                            onSyncLibrary = onSyncLibrary,
                            onExportBluetoothLog = onExportBluetoothLog,
                            onOpenRss = { navigateToTopLevelPage(MainPage.RSS) },
                            onOpenFavorites = { navigateToContentChannel(CONTENT_CHANNEL_FAVORITES) },
                            onOpenWatchLater = { navigateToContentChannel(CONTENT_CHANNEL_WATCH_LATER) },
                            onOpenIndependent = { navigateToContentChannel(CONTENT_CHANNEL_INDEPENDENT) },
                            onOpenImportedContent = { navigateToContentChannel(CONTENT_CHANNEL_IMPORTED_TEXT) },
                            onDismissMessage = onDismissMessage
                        )

                        MainPage.RSS -> ContentPage(
                            uiState = uiState,
                            channels = contentChannels,
                            contentPadding = contentPadding,
                            onOpenChannel = { channel -> navigateToContentChannel(channel.key, MainPage.RSS) },
                            onMoveToTop = onMoveRssSourceToTop,
                            onReorderContentChannels = onReorderContentChannels,
                            onTogglePinned = onToggleRssSourcePinned,
                            onDelete = onDeleteRssSource,
                            onRefreshAllRssSources = onRefreshAllRssSources
                        )

                        MainPage.IMPORTS -> ImportsPage(
                            uiState = uiState,
                            contentPadding = contentPadding,
                            onUrlChange = onUrlChange,
                            onImportArticle = onImportArticle,
                            onImportFile = onImportFile,
                            onAddRssSource = onAddRssSource,
                            onClearImportedContent = onClearImportedContent,
                            onDismissMessage = onDismissMessage,
                            recentEntries = buildRecentImportEntries(contentChannels, uiState.independentArticles),
                            onOpenChannel = { channel -> navigateToContentChannel(channel.key, MainPage.IMPORTS) },
                            onOpenArticle = onOpenArticle,
                            onOpenOriginalLink = { uriHandler.openUri(it) },
                            onToggleFavorite = onToggleFavorite,
                            onToggleWatchLater = onToggleWatchLater,
                            onDeleteArticle = onDeleteArticle
                        )

                        MainPage.CHANNEL -> Unit
                    }
                }
            }

            if (tabBackActive) {
                OpaquePageLayer(
                    modifier = Modifier
                        .tabPredictiveBackTargetPreview(tabBackProgress)
                        .zIndex(0.5f)
                ) {
                    DashboardPage(
                        uiState = uiState,
                        articlesBySource = articlesBySource,
                        contentPadding = contentPadding,
                        onSyncLibrary = onSyncLibrary,
                        onExportBluetoothLog = onExportBluetoothLog,
                        onOpenRss = { navigateToTopLevelPage(MainPage.RSS) },
                        onOpenFavorites = { navigateToContentChannel(CONTENT_CHANNEL_FAVORITES) },
                        onOpenWatchLater = { navigateToContentChannel(CONTENT_CHANNEL_WATCH_LATER) },
                        onOpenIndependent = { navigateToContentChannel(CONTENT_CHANNEL_INDEPENDENT) },
                        onOpenImportedContent = { navigateToContentChannel(CONTENT_CHANNEL_IMPORTED_TEXT) },
                        onDismissMessage = onDismissMessage
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (tabBackActive) {
                            Modifier.tabPredictiveBackPreview(tabBackProgress)
                        } else {
                            Modifier
                        }
                    )
                    .zIndex(if (tabBackActive) 1f else 0f)
            ) { pageIndex ->
                when (TopLevelMainPages[pageIndex]) {
                    MainPage.DASHBOARD -> DashboardPage(
                        uiState = uiState,
                        articlesBySource = articlesBySource,
                        contentPadding = contentPadding,
                        onSyncLibrary = onSyncLibrary,
                        onExportBluetoothLog = onExportBluetoothLog,
                        onOpenRss = { navigateToTopLevelPage(MainPage.RSS) },
                        onOpenFavorites = { navigateToContentChannel(CONTENT_CHANNEL_FAVORITES, MainPage.DASHBOARD) },
                        onOpenWatchLater = { navigateToContentChannel(CONTENT_CHANNEL_WATCH_LATER, MainPage.DASHBOARD) },
                        onOpenIndependent = { navigateToContentChannel(CONTENT_CHANNEL_INDEPENDENT, MainPage.DASHBOARD) },
                        onOpenImportedContent = { navigateToContentChannel(CONTENT_CHANNEL_IMPORTED_TEXT, MainPage.DASHBOARD) },
                        onDismissMessage = onDismissMessage
                    )

                    MainPage.RSS -> ContentPage(
                        uiState = uiState,
                        channels = contentChannels,
                        contentPadding = contentPadding,
                        onOpenChannel = { channel -> navigateToContentChannel(channel.key, MainPage.RSS) },
                        onMoveToTop = onMoveRssSourceToTop,
                        onReorderContentChannels = onReorderContentChannels,
                        onTogglePinned = onToggleRssSourcePinned,
                        onDelete = onDeleteRssSource,
                        onRefreshAllRssSources = onRefreshAllRssSources
                    )

                    MainPage.IMPORTS -> ImportsPage(
                        uiState = uiState,
                        contentPadding = contentPadding,
                        onUrlChange = onUrlChange,
                        onImportArticle = onImportArticle,
                        onImportFile = onImportFile,
                        onAddRssSource = onAddRssSource,
                        onClearImportedContent = onClearImportedContent,
                        onDismissMessage = onDismissMessage,
                        recentEntries = buildRecentImportEntries(contentChannels, uiState.independentArticles),
                        onOpenChannel = { channel -> navigateToContentChannel(channel.key, MainPage.IMPORTS) },
                        onOpenArticle = onOpenArticle,
                        onOpenOriginalLink = { uriHandler.openUri(it) },
                        onToggleFavorite = onToggleFavorite,
                        onToggleWatchLater = onToggleWatchLater,
                        onDeleteArticle = onDeleteArticle
                    )

                    MainPage.CHANNEL -> Unit
                }
            }

            AnimatedVisibility(
                visible = page == MainPage.CHANNEL,
                enter = slideInHorizontally(
                    animationSpec = tween(CHANNEL_TRANSITION_MS)
                ) { fullWidth -> fullWidth / 4 } + fadeIn(
                    animationSpec = tween(CHANNEL_TRANSITION_MS)
                ),
                exit = if (suppressNextChannelExit) {
                    fadeOut(animationSpec = tween(0))
                } else {
                    slideOutHorizontally(
                        animationSpec = tween(CHANNEL_TRANSITION_MS)
                    ) { fullWidth -> fullWidth / 4 } + fadeOut(
                        animationSpec = tween(CHANNEL_TRANSITION_MS)
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
            ) {
                val animatedSource = animatedContentChannel?.source
                ChannelPage(
                    channel = animatedContentChannel,
                    isRefreshing = animatedSource?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                    contentPadding = contentPadding,
                    onRefreshSource = {
                        if (animatedContentChannel?.canRefresh == true) {
                            animatedSource?.let(onRefreshRssSource)
                        }
                    },
                    onOpenArticle = onOpenArticle,
                    onOpenOriginalLink = { uriHandler.openUri(it) },
                    onToggleFavorite = onToggleFavorite,
                    onToggleWatchLater = onToggleWatchLater,
                    onDeleteArticle = onDeleteArticle,
                    modifier = if (channelTabSwitchDestination != null) {
                        Modifier.channelTabSwitchCurrentPreview(
                            progress = channelTabSwitchProgress,
                            direction = channelTabSwitchDirection
                        )
                    } else {
                        Modifier.channelPredictiveBackPreview(
                            progress = channelBackProgress
                        )
                    }
                )
            }
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
            onImportLinkAsArticle = { url ->
                navigateToTopLevelPage(MainPage.IMPORTS)
                onImportSharedLinkAsArticle(url)
            },
            onImportLinkAsRss = { url ->
                navigateToTopLevelPage(MainPage.IMPORTS)
                onImportSharedLinkAsRss(url)
            },
            onConfirmFileImport = { filePrompt ->
                navigateToTopLevelPage(MainPage.IMPORTS)
                onConfirmSharedFileImport(filePrompt)
            },
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

@Composable
private fun OpaquePageLayer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    page: MainPage,
    selectedChannel: MainContentChannel?,
    canRefreshRss: Boolean,
    canRefreshSource: Boolean,
    onBack: () -> Unit,
    onRefreshAllRssSources: () -> Unit,
    onRefreshSelectedSource: () -> Unit,
    onExportBluetoothLog: () -> Unit
) {
    val title = when (page) {
        MainPage.DASHBOARD -> "腕上RSS"
        MainPage.RSS -> "内容"
        MainPage.IMPORTS -> "导入"
        MainPage.CHANNEL -> selectedChannel?.title?.takeIf { it.isNotBlank() } ?: "频道"
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
                    Icon(Icons.Default.Sync, contentDescription = "刷新内容")
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
        TopLevelMainPages.forEach { destination ->
            NavigationBarItem(
                selected = selectedPage == destination,
                onClick = { onSelectPage(destination) },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            MainPage.DASHBOARD -> Icons.Default.Home
                            MainPage.RSS -> Icons.Default.RssFeed
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
                            MainPage.RSS -> "内容"
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
    isBusy: Boolean,
    selectedSource: PhoneRssSourceEntity?,
    selectedSourceRefreshing: Boolean,
    canRefreshSelectedSource: Boolean,
    onSyncLibrary: () -> Unit,
    onAddRssSource: () -> Unit,
    onRefreshSelectedSource: () -> Unit
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
            if (selectedSource != null && canRefreshSelectedSource && !selectedSourceRefreshing && !isBusy) {
                ExtendedFloatingActionButton(
                    onClick = onRefreshSelectedSource,
                    icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                    text = { Text("刷新") }
                )
            }
        }
        MainPage.IMPORTS -> {}
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
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = mainContentPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SyncStatusCard(
                message = uiState.syncStatusMessage,
                error = uiState.syncStatusError,
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
                Text(
                    text = it,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismissMessage)
                )
            }
            error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismissMessage)
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
    val cardColors = defaultMainElevatedCardColors()

    ElevatedCard(
        colors = cardColors
    ) {
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
                    title = "内容",
                    value = "$rssSourceCount 频道",
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
                    title = "导入内容",
                    value = "$importedContentCount 篇",
                    supporting = "TXT",
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

private fun buildContentChannels(
    uiState: MainUiState,
    articlesBySource: Map<String, List<PhoneArticleEntity>>
): List<MainContentChannel> {
    val fixedChannels = listOf(
        MainContentChannel(
            key = CONTENT_CHANNEL_FAVORITES,
            title = "收藏",
            supportingText = "已保存的文章",
            articleCount = uiState.favorites.size,
            articles = uiState.favorites,
            icon = MainContentChannelIcon.FAVORITE,
            emptyTitle = "暂无收藏",
            emptyText = "在文章列表中标记后会显示在这里"
        ),
        MainContentChannel(
            key = CONTENT_CHANNEL_WATCH_LATER,
            title = "稍后再看",
            supportingText = "待阅读的文章",
            articleCount = uiState.watchLater.size,
            articles = uiState.watchLater,
            icon = MainContentChannelIcon.WATCH_LATER,
            emptyTitle = "暂无稍后再看",
            emptyText = "在文章列表中标记后会显示在这里"
        )
    )
    val independentSortOrder = uiState.independentArticles.maxOfOrNull { article ->
        maxOf(article.independentSortOrder, article.independentChangedAt, article.importedAt)
    } ?: 0L
    val independentChannel = MainContentChannel(
        key = CONTENT_CHANNEL_INDEPENDENT,
        title = "独立文章",
        supportingText = "网页导入",
        articleCount = uiState.independentArticles.size,
        articles = uiState.independentArticles,
        icon = MainContentChannelIcon.ARTICLE,
        emptyTitle = "暂无独立文章",
        emptyText = "可在导入页添加网页文章",
        canDrag = uiState.independentArticles.isNotEmpty(),
        sortOrder = independentSortOrder
    )
    val sourceChannels = uiState.rssSources.map { source ->
        val isImportedTextSource = ImportedContentIds.isImportedTextSourceUrl(source.url)
        val isImportedEpubSource = ImportedContentIds.isImportedEpubSourceUrl(source.url)
        val isImportedSource = isImportedTextSource || isImportedEpubSource
        val articles = if (isImportedTextSource) {
            uiState.importedContentArticles
        } else {
            articlesBySource[source.url].orEmpty()
        }
        MainContentChannel(
            key = sourceContentChannelKey(source.url),
            title = source.title.ifBlank { source.url },
            supportingText = source.description.ifBlank {
                when {
                    isImportedTextSource -> "TXT 导入"
                    isImportedEpubSource -> "EPUB 导入"
                    else -> source.url
                }
            },
            articleCount = articles.size,
            source = source,
            articles = articles,
            icon = when {
                isImportedEpubSource -> MainContentChannelIcon.BOOK
                isImportedTextSource -> MainContentChannelIcon.IMPORTED
                else -> MainContentChannelIcon.RSS
            },
            emptyTitle = "暂无文章",
            emptyText = if (isImportedSource) "此频道暂无内容" else "下拉或点击刷新",
            canRefresh = !isImportedSource,
            canDrag = true,
            sortOrder = source.sortOrder
        )
    }
    val hasImportedTextSource = sourceChannels.any { channel ->
        channel.source?.url?.let(ImportedContentIds::isImportedTextSourceUrl) == true
    }
    val importedTextFallback = if (!hasImportedTextSource) {
        listOf(
            MainContentChannel(
                key = CONTENT_CHANNEL_IMPORTED_TEXT,
                title = ImportedContentIds.ROOT_SOURCE_TITLE,
                supportingText = "TXT 导入",
                articleCount = uiState.importedContentArticles.size,
                articles = uiState.importedContentArticles,
                icon = MainContentChannelIcon.IMPORTED,
                emptyTitle = "暂无导入内容",
                emptyText = "TXT 文件导入后会显示在这里"
            )
        )
    } else {
        emptyList()
    }
    val reorderableChannels = (listOf(independentChannel) + importedTextFallback + sourceChannels)
        .sortedWith(
            compareByDescending<MainContentChannel> { it.sortOrder }
                .thenBy { it.title }
        )
    return fixedChannels + reorderableChannels
}

private fun sourceContentChannelKey(sourceUrl: String): String = "$CONTENT_SOURCE_PREFIX$sourceUrl"

private fun Modifier.contentChannelDrag(
    channelKey: String,
    draggedChannelKey: String?,
    draggedOffsetY: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit
): Modifier {
    val isDragging = draggedChannelKey == channelKey
    return this
        .zIndex(if (isDragging) 1f else 0f)
        .graphicsLayer {
            translationY = if (isDragging) draggedOffsetY else 0f
            alpha = if (isDragging) 0.92f else 1f
            shadowElevation = if (isDragging) 8.dp.toPx() else 0f
        }
        .pointerInput(channelKey) {
            detectDragGesturesAfterLongPress(
                onDragStart = { onDragStart() },
                onDragEnd = onDragFinished,
                onDragCancel = onDragFinished,
                onDrag = { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            )
        }
}

private fun Modifier.channelPredictiveBackPreview(
    progress: Float
): Modifier {
    val previewProgress = progress.coerceIn(0f, 1f)
    val exitProgress = (progress - 1f).coerceIn(0f, 1f)
    if (previewProgress <= 0f && exitProgress <= 0f) return this
    return this.graphicsLayer {
        translationX = 96.dp.toPx() * previewProgress + 1280.dp.toPx() * exitProgress
        scaleX = 1f - 0.04f * previewProgress
        scaleY = 1f - 0.04f * previewProgress
        alpha = (1f - 0.16f * previewProgress) * (1f - exitProgress)
    }
}

private fun Modifier.tabPredictiveBackPreview(
    progress: Float
): Modifier {
    val pageProgress = progress.coerceIn(0f, 1f)
    if (pageProgress <= 0f) return this
    return this.graphicsLayer {
        translationX = size.width * pageProgress
    }
}

private fun Modifier.tabPredictiveBackTargetPreview(
    progress: Float
): Modifier {
    val pageProgress = progress.coerceIn(0f, 1f)
    return this.graphicsLayer {
        translationX = size.width * (pageProgress - 1f)
    }
}

private fun Modifier.channelTabSwitchCurrentPreview(
    progress: Float,
    direction: Float
): Modifier {
    val pageProgress = progress.coerceIn(0f, 1f)
    return this.graphicsLayer {
        translationX = -direction.signForPageTransition() * size.width * pageProgress
    }
}

private fun Modifier.channelTabSwitchTargetPreview(
    progress: Float,
    direction: Float
): Modifier {
    val pageProgress = progress.coerceIn(0f, 1f)
    return this.graphicsLayer {
        translationX = direction.signForPageTransition() * size.width * (1f - pageProgress)
    }
}

private fun Float.signForPageTransition(): Float = if (this < 0f) -1f else 1f

private fun findContentDragTargetKey(
    listState: LazyListState,
    draggedKey: String,
    draggedOffsetY: Float
): String? {
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    val draggedItem = visibleItems.firstOrNull { it.key == draggedKey } ?: return null
    val draggedCenter = draggedItem.offset + draggedItem.size / 2f + draggedOffsetY
    return visibleItems
        .filterNot { it.key == draggedKey }
        .firstOrNull { item ->
            draggedCenter >= item.offset && draggedCenter <= item.offset + item.size
        }
        ?.key as? String
}

private fun reorderSourceChannelsForDrop(
    channels: List<MainContentChannel>,
    draggedKey: String,
    targetKey: String
): MainContentReorderRequest? {
    if (draggedKey == targetKey) return null
    val reorderableChannels = channels.filter { it.canDrag }.toMutableList()
    val fromIndex = reorderableChannels.indexOfFirst { it.key == draggedKey }
    val toIndex = reorderableChannels.indexOfFirst { it.key == targetKey }
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return null
    val moved = reorderableChannels.removeAt(fromIndex)
    reorderableChannels.add(toIndex, moved)
    val sourceUrls = reorderableChannels.mapNotNull { it.source?.url }
    val independentIndex = reorderableChannels.indexOfFirst { it.key == CONTENT_CHANNEL_INDEPENDENT }
        .takeIf { it >= 0 && reorderableChannels[it].articleCount > 0 }
    return MainContentReorderRequest(
        sourceUrlsInDisplayOrder = sourceUrls,
        independentIndex = independentIndex
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentPage(
    uiState: MainUiState,
    channels: List<MainContentChannel>,
    contentPadding: PaddingValues,
    onOpenChannel: (MainContentChannel) -> Unit,
    onMoveToTop: (PhoneRssSourceEntity) -> Unit,
    onReorderContentChannels: (List<String>, Int?) -> Unit,
    onTogglePinned: (PhoneRssSourceEntity) -> Unit,
    onDelete: (PhoneRssSourceEntity) -> Unit,
    onRefreshAllRssSources: () -> Unit
) {
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    var draggedChannelKey by remember { mutableStateOf<String?>(null) }
    var draggedOffsetY by remember { mutableStateOf(0f) }

    fun finishDrag() {
        val draggedKey = draggedChannelKey
        val targetKey = draggedKey?.let {
            findContentDragTargetKey(
                listState = listState,
                draggedKey = it,
                draggedOffsetY = draggedOffsetY
            )
        }
        val reorderRequest = if (draggedKey != null && targetKey != null) {
            reorderSourceChannelsForDrop(
                channels = channels,
                draggedKey = draggedKey,
                targetKey = targetKey
            )
        } else {
            null
        }
        draggedChannelKey = null
        draggedOffsetY = 0f
        if (reorderRequest != null) {
            onReorderContentChannels(
                reorderRequest.sourceUrlsInDisplayOrder,
                reorderRequest.independentIndex
            )
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.refreshingRssSourceUrls.isNotEmpty(),
        onRefresh = onRefreshAllRssSources,
        state = pullState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = uiState.refreshingRssSourceUrls.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = contentPadding.calculateTopPadding() + 12.dp)
            )
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = mainContentPadding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (channels.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
                        title = "暂无内容",
                        text = "可在导入页添加 RSS、网页文章或本地文件"
                    )
                }
            } else {
                items(channels, key = { it.key }) { channel ->
                    val source = channel.source
                    val dragModifier = if (channel.canDrag) {
                        Modifier.contentChannelDrag(
                            channelKey = channel.key,
                            draggedChannelKey = draggedChannelKey,
                            draggedOffsetY = draggedOffsetY,
                            onDragStart = {
                                draggedChannelKey = channel.key
                                draggedOffsetY = 0f
                            },
                            onDrag = { deltaY ->
                                draggedOffsetY += deltaY
                            },
                            onDragFinished = ::finishDrag
                        )
                    } else {
                        Modifier
                    }
                    if (source == null) {
                        MainContentChannelRow(
                            channel = channel,
                            onClick = { onOpenChannel(channel) },
                            modifier = dragModifier
                        )
                    } else {
                        MainScreenSourceRow(
                            source = source,
                            icon = channel.icon,
                            articleCount = channel.articleCount,
                            onClick = { onOpenChannel(channel) },
                            onMoveToTop = { onMoveToTop(source) },
                            onTogglePinned = { onTogglePinned(source) },
                            onDelete = { onDelete(source) },
                            modifier = dragModifier
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelPage(
    channel: MainContentChannel?,
    isRefreshing: Boolean,
    contentPadding: PaddingValues,
    onRefreshSource: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize()
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefreshSource,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = mainContentPadding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (channel == null || channel.articles.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = { MainContentChannelLeadingIcon(channel?.icon ?: MainContentChannelIcon.ARTICLE) },
                            title = channel?.emptyTitle ?: "暂无文章",
                            text = channel?.emptyText ?: "频道不存在"
                        )
                    }
                } else {
                    items(channel.articles, key = { it.articleId }) { article ->
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
}

private fun buildRecentImportEntries(
    contentChannels: List<MainContentChannel>,
    independentArticles: List<PhoneArticleEntity>
): List<RecentImportEntry> {
    val sourceEntries = contentChannels
        .asSequence()
        .filter { channel ->
            val sourceUrl = channel.source?.url ?: return@filter false
            sourceUrl == ImportedContentIds.ROOT_SOURCE_URL ||
                ImportedContentIds.isImportedEpubSourceUrl(sourceUrl) ||
                !ImportedContentIds.isImportedContentUrl(sourceUrl)
        }
        .filter { channel -> channel.articleCount > 0 || channel.source?.url?.let { !ImportedContentIds.isImportedContentUrl(it) } == true }
        .map { channel ->
            RecentImportEntry.Channel(
                channel = channel,
                sortAt = maxOf(channel.source?.updatedAt ?: 0L, channel.source?.createdAt ?: 0L)
            )
        }
    val articleEntries = independentArticles.asSequence()
        .map(RecentImportEntry::Article)
    return (sourceEntries + articleEntries)
        .sortedWith(
            compareByDescending<RecentImportEntry> { it.sortAt }
                .thenBy { it.key }
        )
        .take(20)
        .toList()
}

@Composable
private fun ImportsPage(
    uiState: MainUiState,
    contentPadding: PaddingValues,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onClearImportedContent: () -> Unit,
    onDismissMessage: () -> Unit,
    recentEntries: List<RecentImportEntry>,
    onOpenChannel: (MainContentChannel) -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit
) {
    val importMessage = uiState.message?.takeUnless { it == uiState.syncStatusMessage }
    val importError = uiState.error?.takeUnless { it == uiState.syncStatusError }
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
                onClearImportedContent = onClearImportedContent,
                message = importMessage,
                error = importError,
                onDismissMessage = onDismissMessage
            )
        }
        item {
            Text(
                text = "最近导入",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (recentEntries.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                    title = "暂无导入",
                    text = "添加网页文章或导入 TXT / EPUB 文件后会显示在这里"
                )
            }
        } else {
            items(recentEntries, key = { it.key }) { entry ->
                when (entry) {
                    is RecentImportEntry.Channel -> MainContentChannelRow(
                        channel = entry.channel,
                        onClick = { onOpenChannel(entry.channel) }
                    )
                    is RecentImportEntry.Article -> MainScreenArticleRow(
                        article = entry.article,
                        onOpenArticle = onOpenArticle,
                        onOpenOriginalLink = onOpenOriginalLink,
                        onToggleFavorite = onToggleFavorite,
                        onToggleWatchLater = onToggleWatchLater,
                        onDeleteArticle = onDeleteArticle,
                        mainScreenCanDeleteArticle = mainScreenCanDeleteArticle(entry.article)
                    )
                }
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
    onClearImportedContent: () -> Unit,
    message: String?,
    error: String?,
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
            if (message?.isNotBlank() == true || error?.isNotBlank() == true) {
                ImportStatusContent(
                    message = message,
                    error = error,
                    onDismissMessage = onDismissMessage
                )
            }
        }
    }
}

@Composable
private fun ImportStatusContent(
    message: String?,
    error: String?,
    onDismissMessage: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismissMessage)
            )
        }
        error?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismissMessage)
            )
        }
    }
}

@Composable
private fun MainContentChannelRow(
    channel: MainContentChannel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardColors = defaultMainElevatedCardColors()

    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = cardColors
    ) {
        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = cardColors.containerColor
            ),
            headlineContent = {
                Text(
                    text = channel.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = channel.supportingText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("${channel.articleCount} 篇文章")
                }
            },
            leadingContent = {
                MainContentChannelLeadingIcon(channel.icon)
            }
        )
    }
}

@Composable
private fun MainContentChannelLeadingIcon(icon: MainContentChannelIcon) {
    when (icon) {
        MainContentChannelIcon.RSS -> Icon(Icons.Default.RssFeed, contentDescription = null)
        MainContentChannelIcon.BOOK -> Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
        MainContentChannelIcon.FAVORITE -> Icon(Icons.Default.Favorite, contentDescription = null)
        MainContentChannelIcon.WATCH_LATER -> Icon(Icons.Default.Bookmark, contentDescription = null)
        MainContentChannelIcon.ARTICLE -> Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
        MainContentChannelIcon.IMPORTED -> Icon(Icons.Default.Description, contentDescription = null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainScreenSourceRow(
    source: PhoneRssSourceEntity,
    icon: MainContentChannelIcon,
    articleCount: Int,
    onClick: () -> Unit,
    onMoveToTop: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val cardColors = defaultMainElevatedCardColors()

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            ),
        colors = cardColors
    ) {
        Box {
            ListItem(
                colors = ListItemDefaults.colors(
                    containerColor = cardColors.containerColor
                ),
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
                    MainContentChannelLeadingIcon(icon)
                },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "频道操作")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
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
            )
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
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "文章操作")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
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
