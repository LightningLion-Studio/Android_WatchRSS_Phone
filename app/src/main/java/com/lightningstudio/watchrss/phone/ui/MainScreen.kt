package com.lightningstudio.watchrss.phone.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageDailyPojo
import com.lightningstudio.watchrss.phone.tips.TipEvents
import com.lightningstudio.watchrss.phone.tips.TipIds
import com.lightningstudio.watchrss.phone.tips.ui.LocalTipManager
import com.lightningstudio.watchrss.phone.tips.ui.TipSuppressionState
import com.lightningstudio.watchrss.phone.tips.ui.tipAnchor
import com.lightningstudio.watchrss.phone.data.db.PhoneLlmTokenUsageStatisticsPojo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lightningstudio.watchrss.phone.ArticleContentNodesKey
import com.lightningstudio.watchrss.phone.ArticleContentNodesSnapshot
import com.lightningstudio.watchrss.phone.ArticleReaderScreen
import com.lightningstudio.watchrss.phone.PlatformWebViewScreen
import com.lightningstudio.watchrss.phone.cloud.CloudRssInventoryMode
import com.lightningstudio.watchrss.phone.generateQRCode
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.data.backup.BackupImportMode
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.repo.PhoneImportedTextReader
import com.lightningstudio.watchrss.phone.platform.OnlineNovelLinkDetector
import com.lightningstudio.watchrss.phone.platform.PlatformLinkRouter
import com.lightningstudio.watchrss.phone.viewmodel.MainBluetoothDevicePromptUi
import com.lightningstudio.watchrss.phone.viewmodel.MainBluetoothDeviceUi
import com.lightningstudio.watchrss.phone.viewmodel.BackupImportPromptUi
import com.lightningstudio.watchrss.phone.viewmodel.MainConflictPromptUi
import com.lightningstudio.watchrss.phone.viewmodel.MainSyncProgressUi
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState
import com.lightningstudio.watchrss.phone.viewmodel.SharedImportPromptKind
import com.lightningstudio.watchrss.phone.viewmodel.SharedImportPromptUi
import com.lightningstudio.watchrss.phone.ui.theme.roundedClickable
import com.lightningstudio.watchrss.phone.ui.theme.roundedCombinedClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
private const val CHANNEL_PREDICTIVE_EXIT_MS = 480
private const val CHANNEL_PREDICTIVE_EXIT_PROGRESS = 2f
private const val TAB_PREDICTIVE_EXIT_MS = 480
private const val TAB_PREDICTIVE_EXIT_PROGRESS = 1f
private const val READER_LEFT_PANE_RETURN_TRANSITION_MS = 480
private const val READER_FULLSCREEN_BACK_SETTLE_MS = 480
private const val ARTICLE_CARD_TITLE_LINES = 2
private const val ONLINE_NOVEL_IMPORT_WARNING =
    "小说导入仅支持txt/epub等开放格式本地文件，不支持来自在线小说库的阅读链接分享（七猫/番茄/晋江/起点等平台均不支持），仍然要将页面作为网页或RSS尝试导入吗？"
private const val WATCH_RSS_QQ_GROUP_URL = "https://qm.qq.com/q/cJNTQuxfoW"
private const val WATCH_RSS_QQ_GROUP_NUMBER = "1083518433"
private val MainNavigationRailWidth = 80.dp

@Composable
private fun defaultMainElevatedCardColors() = CardDefaults.elevatedCardColors()

@Composable
private fun pinnedMainContentChannelContainerColor(): Color {
    val isDark = isSystemInDarkTheme()
    return lerpMainColor(
        start = MaterialTheme.colorScheme.surface,
        stop = if (isDark) Color.White else Color.Black,
        progress = if (isDark) 0.06f else 0.04f
    )
}

private data class ReaderLeftPaneReturnState(
    val channelKey: String,
    val articleId: String,
    val returnPage: MainPage,
    val initialProgress: Float = 0f
)

private data class MainContentChannel(
    val key: String,
    val title: String,
    val supportingText: String,
    val articleCount: Int,
    val source: PhoneRssSourceEntity? = null,
    val reorderSourceUrl: String? = source?.url,
    val articles: List<PhoneArticleEntity> = emptyList(),
    val icon: MainContentChannelIcon,
    val emptyTitle: String,
    val emptyText: String,
    val canRefresh: Boolean = false,
    val canDrag: Boolean = false,
    val dragGroup: MainContentDragGroup = MainContentDragGroup.FIXED,
    val sortOrder: Long = 0L
)

private data class MainContentReorderRequest(
    val sourceUrlsInDisplayOrder: List<String>,
    val independentIndex: Int?
)

private data class InlineReaderPaneInput(
    val article: PhoneArticleEntity,
    val readerArticle: PhoneArticleEntity?,
    val importedTextReader: PhoneImportedTextReader?,
    val onLoadImportedTextChunk: suspend (String, Int) -> String?,
    val onSaveReadingProgress: suspend (Float) -> Unit,
    val onBack: () -> Unit,
    val onOpenImportedArticle: (String) -> Unit,
    val onOpenOriginal: (String) -> Unit,
    val listState: LazyListState,
    val contentReady: Boolean,
    val contentNodesCache: MutableMap<ArticleContentNodesKey, ArticleContentNodesSnapshot>,
    val positionAlreadyRestored: Boolean,
    val onPositionRestored: (String) -> Unit,
    val fullscreen: Boolean,
    val continuePlaybackInBackground: Boolean,
    val showFullscreenControl: Boolean,
    val onToggleFullscreen: () -> Unit
)

private enum class MainContentChannelIcon {
    RSS,
    BOOK,
    FAVORITE,
    WATCH_LATER,
    ARTICLE,
    IMPORTED
}

private enum class MainContentDragGroup {
    FIXED,
    PINNED,
    NORMAL
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
    showAccountFeatures: Boolean = true,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onImportSharedLinkAsArticle: (String) -> Unit,
    onImportSharedLinkAsRss: (String) -> Unit,
    onConfirmSharedFileImport: (SharedImportPromptUi) -> Unit,
    onDismissSharedImport: () -> Unit,
    onSyncLibrary: () -> Unit,
    onSyncAccount: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenNotes: () -> Unit,
    onChooseBluetoothDevice: (MainBluetoothDeviceUi) -> Unit,
    onDismissBluetoothDevicePrompt: () -> Unit,
    onExportBluetoothLog: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    canOpenArticleInline: (PhoneArticleEntity) -> Boolean,
    onLoadArticleForInlineReader: suspend (String) -> PhoneArticleEntity?,
    onLoadImportedTextReaderForInlineReader: suspend (String) -> PhoneImportedTextReader?,
    onLoadImportedTextChunkForInlineReader: suspend (String, Int) -> String?,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onSaveArticleReadingProgress: suspend (String, Float) -> Unit,
    onMoveRssSourceToTop: (PhoneRssSourceEntity) -> Unit,
    onReorderContentChannels: (List<String>, Int?) -> Unit,
    onToggleRssSourcePinned: (PhoneRssSourceEntity) -> Unit,
    onSetRssSourceOriginalContentEnabled: (PhoneRssSourceEntity, Boolean) -> Unit,
    onSetRssSourceContinuePlaybackInBackground: (PhoneRssSourceEntity, Boolean) -> Unit,
    onClearRssSourceContent: (PhoneRssSourceEntity) -> Unit,
    rssInventoryMode: (String) -> CloudRssInventoryMode,
    onSetRssInventoryMode: (String, CloudRssInventoryMode) -> Unit,
    onDeleteRssSource: (PhoneRssSourceEntity) -> Unit,
    onRefreshAllRssSources: () -> Unit,
    onRefreshRssSource: (PhoneRssSourceEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: (BackupImportMode) -> Unit,
    onRequestBackupReplace: () -> Unit,
    onDismissBackupImport: () -> Unit,
    onChooseConflictResolution: (PhoneSyncConflictResolution) -> Unit,
    onShowManualConflictOptions: () -> Unit,
    onDismissMessage: () -> Unit,
    tipSuppression: TipSuppressionState? = null
) {
    var selectedContentChannelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedReaderArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerFullscreenActive by rememberSaveable { mutableStateOf(false) }
    var readerBackProgress by remember { mutableFloatStateOf(0f) }
    var readerFullscreenBackProgress by remember { mutableFloatStateOf(0f) }
    var readerBackAnimating by remember { mutableStateOf(false) }
    var readerOpenProgress by remember { mutableFloatStateOf(1f) }
    var readerOpenAnimating by remember { mutableStateOf(false) }
    var inlineReaderRestoredArticleId by remember { mutableStateOf<String?>(null) }
    var readerLeftPaneReturnState by remember { mutableStateOf<ReaderLeftPaneReturnState?>(null) }
    var lastContentChannelKey by rememberSaveable { mutableStateOf<String?>(null) }
    var channelReturnPageName by rememberSaveable { mutableStateOf(MainPage.RSS.name) }
    var singlePaneChannelReturnPageName by rememberSaveable { mutableStateOf(MainPage.RSS.name) }
    var channelBackProgress by remember { mutableFloatStateOf(0f) }
    var tabBackProgress by remember { mutableFloatStateOf(0f) }
    var tabBackActive by remember { mutableStateOf(false) }
    var suppressNextTopLevelTransition by remember { mutableStateOf(false) }
    var channelTabSwitchDestinationName by remember { mutableStateOf<String?>(null) }
    var currentTopLevelPageName by rememberSaveable { mutableStateOf(MainPage.DASHBOARD.name) }
    var isMediumOrExpandedLayout by remember { mutableStateOf(false) }
    var topLevelNavigationTargetName by remember { mutableStateOf<String?>(null) }
    var channelTabSwitchProgress by remember { mutableFloatStateOf(0f) }

    // 转场期间抑制情境提示，避免气泡锚定到渐隐的页面副本
    LaunchedEffect(tabBackActive, channelTabSwitchDestinationName, topLevelNavigationTargetName) {
        tipSuppression?.active =
            tabBackActive || channelTabSwitchDestinationName != null || topLevelNavigationTargetName != null
    }
    var channelTabSwitchDirection by remember { mutableFloatStateOf(1f) }
    var suppressNextChannelExit by remember { mutableStateOf(false) }
    var urlDialogMode by remember { mutableStateOf<UrlDialogMode?>(null) }
    var channelSettingsSourceUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(initialPage = MainPage.DASHBOARD.topLevelIndex()) {
        TopLevelMainPages.size
    }
    val contentPageListState = rememberLazyListState()
    val importsPageListState = rememberLazyListState()
    val channelArticleListState = rememberSaveable(
        selectedContentChannelKey,
        saver = LazyListState.Saver
    ) {
        LazyListState()
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
    val channelSettingsSource = channelSettingsSourceUrl?.let { sourceUrl ->
        uiState.rssSources.firstOrNull { it.url == sourceUrl }
    }
    val selectedReaderArticleListItem = selectedReaderArticleId?.let { articleId ->
        selectedContentChannel?.articles?.firstOrNull { it.articleId == articleId }
            ?: uiState.independentArticles.firstOrNull { it.articleId == articleId }
            ?: uiState.importedContentArticles.firstOrNull { it.articleId == articleId }
            ?: uiState.favorites.firstOrNull { it.articleId == articleId }
            ?: uiState.watchLater.firstOrNull { it.articleId == articleId }
            ?: uiState.rssArticles.firstOrNull { it.articleId == articleId }
    }
    val inlineReaderOpenSettled = selectedReaderArticleId != null &&
        !readerOpenAnimating &&
        readerOpenProgress >= 1f
    val inlineReaderContentReady = inlineReaderOpenSettled
    val inlineReaderLoadArticleId = selectedReaderArticleId
    val hydratedSelectedReaderArticle by produceState<PhoneArticleEntity?>(
        initialValue = null,
        inlineReaderLoadArticleId
    ) {
        val articleId = inlineReaderLoadArticleId
        value = null
        if (articleId != null) {
            value = runCatching { onLoadArticleForInlineReader(articleId) }.getOrNull()
        }
    }
    val selectedReaderArticle = hydratedSelectedReaderArticle
        ?.takeIf { it.articleId == selectedReaderArticleId }
        ?: selectedReaderArticleListItem
    val selectedImportedTextReader by produceState<PhoneImportedTextReader?>(
        initialValue = null,
        inlineReaderLoadArticleId
    ) {
        val articleId = inlineReaderLoadArticleId
        value = null
        if (articleId != null) {
            value = runCatching { onLoadImportedTextReaderForInlineReader(articleId) }.getOrNull()
        }
    }
    LaunchedEffect(selectedContentChannelKey) {
        selectedContentChannelKey?.let { key ->
            lastContentChannelKey = key
            channelBackProgress = 0f
        }
    }
    LaunchedEffect(selectedReaderArticleId, selectedReaderArticle) {
        if (selectedReaderArticleId != null && selectedReaderArticle == null) {
            selectedReaderArticleId = null
            readerFullscreenActive = false
            readerFullscreenBackProgress = 0f
            readerOpenProgress = 1f
            readerOpenAnimating = false
        }
    }
    LaunchedEffect(selectedReaderArticleId) {
        if (selectedReaderArticleId == null) {
            inlineReaderRestoredArticleId = null
            readerFullscreenActive = false
            readerBackProgress = 0f
            readerFullscreenBackProgress = 0f
            readerOpenProgress = 1f
            readerOpenAnimating = false
        } else if (inlineReaderRestoredArticleId != selectedReaderArticleId) {
            inlineReaderRestoredArticleId = null
        }
    }
    val animatedContentChannel = (selectedContentChannelKey ?: lastContentChannelKey)?.let { key ->
        contentChannels.firstOrNull { it.key == key }
    }
    val selectedSource = selectedContentChannel?.source
    val pagerTopLevelPage = TopLevelMainPages.getOrElse(pagerState.currentPage) {
        MainPage.DASHBOARD
    }
    val currentTopLevelPage = runCatching { MainPage.valueOf(currentTopLevelPageName) }
        .getOrDefault(pagerTopLevelPage)
        .takeIf { it in TopLevelMainPages }
        ?: pagerTopLevelPage
    val channelReturnPage = runCatching { MainPage.valueOf(channelReturnPageName) }
        .getOrDefault(MainPage.RSS)
        .takeIf { it in TopLevelMainPages }
        ?: MainPage.RSS
    val singlePaneChannelReturnPage = runCatching { MainPage.valueOf(singlePaneChannelReturnPageName) }
        .getOrDefault(MainPage.RSS)
        .takeIf { it in TopLevelMainPages }
        ?: MainPage.RSS
    val channelTabSwitchDestination = channelTabSwitchDestinationName
        ?.let { runCatching { MainPage.valueOf(it) }.getOrNull() }
        ?.takeIf { it in TopLevelMainPages }
    val topLevelNavigationTarget = topLevelNavigationTargetName
        ?.let { runCatching { MainPage.valueOf(it) }.getOrNull() }
        ?.takeIf { it in TopLevelMainPages }
    val page = if (selectedContentChannel != null) MainPage.CHANNEL else currentTopLevelPage
    val selectedBottomPage = if (page == MainPage.CHANNEL) singlePaneChannelReturnPage else currentTopLevelPage

    fun returnToDashboard(
        usePager: Boolean = !isMediumOrExpandedLayout,
        suppressTopLevelTransition: Boolean = false,
        keepPredictiveTransformUntilSettled: Boolean = false
    ) {
        if (suppressTopLevelTransition) {
            suppressNextTopLevelTransition = true
        }
        if (keepPredictiveTransformUntilSettled) {
            tabBackActive = true
            tabBackProgress = 1f
        } else {
            tabBackActive = false
            tabBackProgress = 0f
        }
        currentTopLevelPageName = MainPage.DASHBOARD.name
        selectedContentChannelKey = null
        selectedReaderArticleId = null
        readerFullscreenActive = false
        readerFullscreenBackProgress = 0f
        readerOpenProgress = 1f
        readerOpenAnimating = false
        readerLeftPaneReturnState = null
        if (usePager) {
            coroutineScope.launch {
                pagerState.scrollToPage(MainPage.DASHBOARD.topLevelIndex())
            }
        }
        if (keepPredictiveTransformUntilSettled) {
            coroutineScope.launch {
                delay(64L)
                tabBackActive = false
                tabBackProgress = 0f
            }
        }
    }

    fun navigateToTopLevelPage(
        destination: MainPage,
        usePager: Boolean = !isMediumOrExpandedLayout
    ) {
        tabBackActive = false
        tabBackProgress = 0f
        topLevelNavigationTargetName = destination.name
        currentTopLevelPageName = destination.name
        selectedContentChannelKey = null
        selectedReaderArticleId = null
        readerFullscreenActive = false
        readerFullscreenBackProgress = 0f
        readerOpenProgress = 1f
        readerOpenAnimating = false
        readerLeftPaneReturnState = null
        if (usePager) {
            coroutineScope.launch {
                pagerState.animateScrollToPage(destination.topLevelIndex())
            }
        }
        coroutineScope.launch {
            delay(CHANNEL_TRANSITION_MS.toLong())
            if (destination != MainPage.RSS) {
                selectedContentChannelKey = null
                selectedReaderArticleId = null
                readerFullscreenActive = false
                readerFullscreenBackProgress = 0f
                readerOpenProgress = 1f
                readerOpenAnimating = false
                readerLeftPaneReturnState = null
            }
            if (topLevelNavigationTargetName == destination.name) {
                topLevelNavigationTargetName = null
            }
        }
    }

    fun navigateToContentChannel(
        channelKey: String,
        hostPage: MainPage,
        singlePaneReturnPage: MainPage
    ) {
        val resolvedChannelKey = when (channelKey) {
            CONTENT_CHANNEL_IMPORTED_TEXT -> importedContentChannelKey
            else -> channelKey
        }
        channelReturnPageName = hostPage.name
        singlePaneChannelReturnPageName = singlePaneReturnPage.name
        if (hostPage in TopLevelMainPages) {
            currentTopLevelPageName = hostPage.name
        }
        selectedContentChannelKey = resolvedChannelKey
        selectedReaderArticleId = null
        readerFullscreenActive = false
        readerFullscreenBackProgress = 0f
        readerOpenProgress = 1f
        readerOpenAnimating = false
        readerLeftPaneReturnState = null
    }

    fun switchChannelToTopLevelPage(destination: MainPage, returnPage: MainPage) {
        if (channelTabSwitchDestinationName != null || destination !in TopLevelMainPages) return
        val sourceIndex = returnPage.topLevelIndex()
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
            currentTopLevelPageName = destination.name
            if (!isMediumOrExpandedLayout) {
                pagerState.scrollToPage(destinationIndex)
            }
            delay(32L)
            channelTabSwitchDestinationName = null
            channelTabSwitchProgress = 0f
            suppressNextChannelExit = false
        }
    }

    PredictiveBackHandler(
        enabled = !isMediumOrExpandedLayout &&
            page == MainPage.CHANNEL &&
            selectedReaderArticle == null
    ) { backEvents ->
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
            currentTopLevelPageName = singlePaneChannelReturnPage.name
            if (!isMediumOrExpandedLayout) {
                coroutineScope.launch {
                    pagerState.animateScrollToPage(singlePaneChannelReturnPage.topLevelIndex())
                }
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

    val topLevelBackEnabled = selectedReaderArticle == null &&
        if (isMediumOrExpandedLayout) {
            currentTopLevelPage != MainPage.DASHBOARD
        } else {
            page != MainPage.DASHBOARD && page != MainPage.CHANNEL
    }
    PredictiveBackHandler(enabled = topLevelBackEnabled) { backEvents ->
        var resetBackPreviewInFinally = true
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
            resetBackPreviewInFinally = !isMediumOrExpandedLayout
            returnToDashboard(
                usePager = !isMediumOrExpandedLayout,
                suppressTopLevelTransition = isMediumOrExpandedLayout,
                keepPredictiveTransformUntilSettled = isMediumOrExpandedLayout
            )
        } catch (exception: CancellationException) {
            animate(
                initialValue = tabBackProgress,
                targetValue = 0f,
                animationSpec = tween(CHANNEL_TRANSITION_MS)
            ) { value, _ ->
                tabBackProgress = value
            }
        } finally {
            if (resetBackPreviewInFinally) {
                tabBackActive = false
                tabBackProgress = 0f
            }
        }
    }

    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
        SideEffect {
            isMediumOrExpandedLayout = windowInfo.isMediumOrExpanded
        }

        val tipManager = LocalTipManager.current
        LaunchedEffect(windowInfo.isMediumOrExpanded, pagerTopLevelPage) {
            if (!windowInfo.isMediumOrExpanded) {
                currentTopLevelPageName = pagerTopLevelPage.name
            }
            if (pagerTopLevelPage == MainPage.IMPORTS) {
                tipManager?.recordEvent(TipEvents.IMPORTS_PAGE_OPENED)
            }
        }
        LaunchedEffect(currentTopLevelPage, suppressNextTopLevelTransition) {
            if (suppressNextTopLevelTransition) {
                delay(32L)
                suppressNextTopLevelTransition = false
            }
        }

        fun openAdaptiveContentChannel(
            channelKey: String,
            hostPage: MainPage,
            singlePaneReturnPage: MainPage
        ) {
            navigateToContentChannel(channelKey, hostPage, singlePaneReturnPage)
        }

        fun openInlineReaderWithMotion(articleId: String) {
            readerLeftPaneReturnState = null
            readerBackProgress = 0f
            readerFullscreenActive = false
            readerFullscreenBackProgress = 0f
            if (selectedReaderArticleId != null) {
                selectedReaderArticleId = articleId
                if (!readerOpenAnimating) {
                    readerOpenProgress = 1f
                }
                return
            }
            readerOpenProgress = 0f
            readerOpenAnimating = true
            selectedReaderArticleId = articleId
            coroutineScope.launch {
                try {
                    animate(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = READER_LEFT_PANE_RETURN_TRANSITION_MS,
                            easing = FastOutSlowInEasing
                        )
                    ) { value, _ ->
                        readerOpenProgress = value
                    }
                } finally {
                    readerOpenProgress = 1f
                    readerOpenAnimating = false
                }
            }
        }

        fun openAdaptiveArticle(article: PhoneArticleEntity) {
            if (windowInfo.isMediumOrExpanded && canOpenArticleInline(article)) {
                openInlineReaderWithMotion(article.articleId)
            } else {
                onOpenArticle(article)
            }
        }

        fun openIndependentArticleFromImports(article: PhoneArticleEntity) {
            if (windowInfo.isMediumOrExpanded && canOpenArticleInline(article)) {
                channelReturnPageName = MainPage.IMPORTS.name
                singlePaneChannelReturnPageName = MainPage.IMPORTS.name
                selectedContentChannelKey = CONTENT_CHANNEL_INDEPENDENT
                openInlineReaderWithMotion(article.articleId)
            } else {
                onOpenArticle(article)
            }
        }

        fun startReaderLeftPaneReturnTransition(initialProgress: Float = 0f) {
            val channelKey = selectedContentChannelKey
            val articleId = selectedReaderArticleId
            if (
                windowInfo.isMediumOrExpanded &&
                channelKey != null &&
                articleId != null &&
                currentTopLevelPage in TopLevelMainPages
            ) {
                readerLeftPaneReturnState = ReaderLeftPaneReturnState(
                    channelKey = channelKey,
                    articleId = articleId,
                    returnPage = currentTopLevelPage,
                    initialProgress = initialProgress.coerceIn(0f, 1f)
                )
            }
        }

        fun handleInlineReaderBack(returnMotionProgress: Float = 0f) {
            if (readerFullscreenActive && windowInfo.isMediumOrExpanded) {
                readerFullscreenActive = false
                readerBackProgress = 0f
                readerFullscreenBackProgress = 0f
            } else if (
                returnMotionProgress <= 0f &&
                windowInfo.isMediumOrExpanded &&
                selectedReaderArticleId != null &&
                selectedContentChannelKey != null
            ) {
                if (readerBackAnimating) return
                readerBackAnimating = true
                coroutineScope.launch {
                    try {
                        animate(
                            initialValue = readerBackProgress.coerceIn(0f, 1f),
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = READER_LEFT_PANE_RETURN_TRANSITION_MS,
                                easing = FastOutSlowInEasing
                            )
                        ) { value, _ ->
                            readerBackProgress = value
                        }
                        startReaderLeftPaneReturnTransition(1f)
                        selectedReaderArticleId = null
                        readerFullscreenActive = false
                        readerFullscreenBackProgress = 0f
                        readerOpenProgress = 1f
                        readerOpenAnimating = false
                    } finally {
                        readerBackProgress = 0f
                        readerBackAnimating = false
                    }
                }
            } else {
                startReaderLeftPaneReturnTransition(returnMotionProgress)
                selectedReaderArticleId = null
                readerFullscreenActive = false
                readerBackProgress = 0f
                readerFullscreenBackProgress = 0f
                readerOpenProgress = 1f
                readerOpenAnimating = false
            }
        }

        PredictiveBackHandler(enabled = selectedReaderArticle != null) { backEvents ->
            val fullscreenReaderBack = windowInfo.isMediumOrExpanded && readerFullscreenActive
            try {
                if (fullscreenReaderBack) {
                    readerBackProgress = 0f
                    readerFullscreenBackProgress = 0f
                    backEvents.collect { backEvent ->
                        readerFullscreenBackProgress = backEvent.progress.coerceIn(0f, 1f)
                    }
                    val remainingDuration = (READER_FULLSCREEN_BACK_SETTLE_MS *
                        (1f - readerFullscreenBackProgress.coerceIn(0f, 1f)))
                        .toInt()
                        .coerceAtLeast(1)
                    animate(
                        initialValue = readerFullscreenBackProgress,
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = remainingDuration,
                            easing = FastOutSlowInEasing
                        )
                    ) { value, _ ->
                        readerFullscreenBackProgress = value
                    }
                    readerFullscreenActive = false
                    delay(READER_FULLSCREEN_BACK_SETTLE_MS.toLong() + 32L)
                    if (!readerFullscreenActive) {
                        readerFullscreenBackProgress = 0f
                    }
                } else {
                    readerBackProgress = 0f
                    backEvents.collect { backEvent ->
                        readerBackProgress = backEvent.progress.coerceIn(0f, 1f)
                    }
                    val splitReaderBack = windowInfo.isMediumOrExpanded
                    val targetProgress = if (windowInfo.isMediumOrExpanded) 1f else PREDICTIVE_BACK_EXIT_PROGRESS
                    val exitDurationMs = if (splitReaderBack) {
                        READER_LEFT_PANE_RETURN_TRANSITION_MS
                    } else {
                        PREDICTIVE_BACK_EXIT_ANIMATION_MS
                    }
                    animate(
                        initialValue = readerBackProgress,
                        targetValue = targetProgress,
                        animationSpec = tween(
                            durationMillis = exitDurationMs,
                            easing = FastOutSlowInEasing
                        )
                    ) { value, _ ->
                        readerBackProgress = value
                    }
                    val returnMotionProgress = if (splitReaderBack) {
                        readerBackProgress.coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    handleInlineReaderBack(returnMotionProgress)
                }
            } catch (exception: CancellationException) {
                if (fullscreenReaderBack) {
                    animate(
                        initialValue = readerFullscreenBackProgress,
                        targetValue = 0f,
                        animationSpec = tween(PREDICTIVE_BACK_CANCEL_ANIMATION_MS)
                    ) { value, _ ->
                        readerFullscreenBackProgress = value
                    }
                } else {
                    animate(
                        initialValue = readerBackProgress,
                        targetValue = 0f,
                        animationSpec = tween(PREDICTIVE_BACK_CANCEL_ANIMATION_MS)
                    ) { value, _ ->
                        readerBackProgress = value
                    }
                }
            }
        }

        LaunchedEffect(
            windowInfo.isMediumOrExpanded,
            currentTopLevelPage,
            channelReturnPage,
            selectedContentChannelKey,
            contentChannels
        ) {
            val fallbackChannel = if (
                windowInfo.isMediumOrExpanded &&
                currentTopLevelPage == MainPage.RSS &&
                selectedContentChannel == null
            ) {
                contentChannels.firstOrNull { it.articleCount > 0 }
                    ?: contentChannels.firstOrNull()
            } else {
                null
            }
            fallbackChannel?.let { channel ->
                channelReturnPageName = MainPage.RSS.name
                singlePaneChannelReturnPageName = MainPage.RSS.name
                selectedContentChannelKey = channel.key
            }
        }

        LaunchedEffect(
            windowInfo.isMediumOrExpanded,
            currentTopLevelPage,
            channelReturnPage,
            selectedContentChannelKey
        ) {
            if (
                windowInfo.isMediumOrExpanded &&
                selectedContentChannelKey != null &&
                currentTopLevelPage != channelReturnPage
            ) {
                delay(CHANNEL_TRANSITION_MS.toLong() + 80L)
                selectedContentChannelKey = null
                selectedReaderArticleId = null
                readerFullscreenActive = false
                readerOpenProgress = 1f
                readerOpenAnimating = false
            }
        }

        val fabPage = when {
            windowInfo.isMediumOrExpanded && selectedReaderArticle != null -> MainPage.IMPORTS
            windowInfo.isMediumOrExpanded && page == MainPage.CHANNEL -> channelReturnPage
            else -> page
        }
        val activeChannelReturnPage = if (windowInfo.isMediumOrExpanded) {
            channelReturnPage
        } else {
            singlePaneChannelReturnPage
        }
        val topBarPage = if (selectedContentChannel == null) {
            topLevelNavigationTarget ?: page
        } else {
            page
        }
        val navigationSelectedPage = topLevelNavigationTarget ?: if (windowInfo.isMediumOrExpanded) {
            currentTopLevelPage
        } else {
            selectedBottomPage
        }
        val readerTakesCurrentPage = selectedReaderArticle != null &&
            (readerFullscreenActive || !windowInfo.isMediumOrExpanded)
        val readingSplitActive = windowInfo.isMediumOrExpanded && selectedReaderArticle != null
        val usesPaneTopBars = windowInfo.isMediumOrExpanded &&
            (topBarPage == MainPage.RSS || topBarPage == MainPage.IMPORTS || topBarPage == MainPage.CHANNEL)
        val showGlobalTopBar = !readingSplitActive && !readerTakesCurrentPage && !usesPaneTopBars
        val showScaffoldTopBar = showGlobalTopBar && windowInfo.navigationType != AdaptiveNavigationType.Rail
        val inlineReaderListState = remember(selectedReaderArticle?.articleId) {
            LazyListState()
        }
        val inlineReaderContentNodesCache = remember {
            mutableMapOf<ArticleContentNodesKey, ArticleContentNodesSnapshot>()
        }
        val movableInlineReaderPane = remember(
            selectedReaderArticle?.articleId,
            inlineReaderContentReady,
            hydratedSelectedReaderArticle?.articleId != null
        ) {
            movableContentOf<InlineReaderPaneInput> { input ->
                InlineArticleReaderPane(
                    article = input.article,
                    readerArticle = input.readerArticle,
                    importedTextReader = input.importedTextReader,
                    onLoadImportedTextChunk = input.onLoadImportedTextChunk,
                    onSaveReadingProgress = input.onSaveReadingProgress,
                    onBack = input.onBack,
                    onOpenImportedArticle = input.onOpenImportedArticle,
                    onOpenOriginal = input.onOpenOriginal,
                    listState = input.listState,
                    contentReady = input.contentReady,
                    contentNodesCache = input.contentNodesCache,
                    positionAlreadyRestored = input.positionAlreadyRestored,
                    onPositionRestored = input.onPositionRestored,
                    fullscreen = input.fullscreen,
                    continuePlaybackInBackground = input.continuePlaybackInBackground,
                    showFullscreenControl = input.showFullscreenControl,
                    onToggleFullscreen = input.onToggleFullscreen
                )
            }
        }
        val activeInlineReaderPane: @Composable (Boolean) -> Unit = { fullscreen ->
            val article = selectedReaderArticleListItem ?: selectedReaderArticle
            if (article != null) {
                movableInlineReaderPane(
                    InlineReaderPaneInput(
                        article = article,
                        readerArticle = hydratedSelectedReaderArticle
                            ?.takeIf { inlineReaderContentReady && it.articleId == article.articleId },
                        importedTextReader = selectedImportedTextReader,
                        onLoadImportedTextChunk = onLoadImportedTextChunkForInlineReader,
                        onSaveReadingProgress = { progress ->
                            onSaveArticleReadingProgress(article.articleId, progress)
                        },
                        onBack = { handleInlineReaderBack() },
                        onOpenImportedArticle = { url -> uriHandler.openUri(url) },
                        onOpenOriginal = { url -> uriHandler.openUri(url) },
                        listState = inlineReaderListState,
                        contentReady = inlineReaderContentReady,
                        contentNodesCache = inlineReaderContentNodesCache,
                        positionAlreadyRestored = inlineReaderRestoredArticleId == article.articleId,
                        onPositionRestored = { restoredArticleId ->
                            if (selectedReaderArticleId == restoredArticleId) {
                                inlineReaderRestoredArticleId = restoredArticleId
                            }
                        },
                        fullscreen = fullscreen,
                        continuePlaybackInBackground = article.rssSourceUrl
                            ?.let { sourceUrl ->
                                uiState.rssSources.firstOrNull { it.url == sourceUrl }
                            }
                            ?.continuePlaybackInBackground == true,
                        showFullscreenControl = windowInfo.isMediumOrExpanded,
                        onToggleFullscreen = {
                            readerFullscreenBackProgress = 0f
                            readerFullscreenActive = !readerFullscreenActive
                        }
                    )
                )
            }
        }
        @Composable
        fun RenderGlobalTopBar(modifier: Modifier = Modifier) {
            MainTopBar(
                page = if (windowInfo.isMediumOrExpanded && topBarPage == MainPage.CHANNEL) channelReturnPage else topBarPage,
                selectedChannel = if (windowInfo.isMediumOrExpanded) null else selectedContentChannel,
                canRefreshRss = uiState.rssSources.any { !ImportedContentIds.isImportedContentUrl(it.url) } && !uiState.isBusy,
                canRefreshSource = !windowInfo.isMediumOrExpanded &&
                    selectedContentChannel?.canRefresh == true &&
                    selectedSource != null &&
                    selectedSource.url !in uiState.refreshingRssSourceUrls &&
                    !uiState.isBusy,
                onBack = {
                    navigateToTopLevelPage(
                        activeChannelReturnPage,
                        usePager = !windowInfo.isMediumOrExpanded
                    )
                },
                onRefreshAllRssSources = onRefreshAllRssSources,
                onRefreshSelectedSource = {
                    if (selectedContentChannel?.canRefresh == true) {
                        selectedSource?.let(onRefreshRssSource)
                    }
                },
                onOpenChannelSettings = {
                    selectedSource?.let { channelSettingsSourceUrl = it.url }
                },
                onExportBluetoothLog = onExportBluetoothLog,
                onOpenProfile = onOpenProfile,
                modifier = modifier
            )
        }
        Scaffold(
            topBar = {
                if (showScaffoldTopBar) {
                    RenderGlobalTopBar()
                }
            },
            bottomBar = {
                if (windowInfo.navigationType == AdaptiveNavigationType.BottomBar && selectedReaderArticle == null) {
                    MainNavigationBar(
                        selectedPage = navigationSelectedPage,
                        onSelectPage = { destination ->
                            if (page == MainPage.CHANNEL && destination != activeChannelReturnPage) {
                                switchChannelToTopLevelPage(destination, activeChannelReturnPage)
                            } else {
                                navigateToTopLevelPage(
                                    destination,
                                    usePager = !windowInfo.isMediumOrExpanded
                                )
                            }
                        }
                    )
                }
            },
            floatingActionButton = {
                if (selectedReaderArticle == null) {
                    MainFloatingActionButton(
                        page = fabPage,
                        isBusy = uiState.isBusy,
                        selectedSource = if (windowInfo.isMediumOrExpanded) null else selectedSource,
                        selectedSourceRefreshing = selectedSource?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                        canRefreshSelectedSource = !windowInfo.isMediumOrExpanded && selectedContentChannel?.canRefresh == true,
                        onSyncLibrary = onSyncLibrary,
                        onAddRssSource = { urlDialogMode = UrlDialogMode.RSS },
                        onRefreshSelectedSource = {
                            if (selectedContentChannel?.canRefresh == true) {
                                selectedSource?.let(onRefreshRssSource)
                            }
                        }
                    )
                }
            }
        ) { contentPadding ->
            Row(modifier = Modifier.fillMaxSize()) {
                if (windowInfo.navigationType == AdaptiveNavigationType.Rail) {
                    MainNavigationRail(
                        selectedPage = navigationSelectedPage,
                        contentPadding = contentPadding,
                        onSelectPage = { destination ->
                            if (!windowInfo.isMediumOrExpanded && page == MainPage.CHANNEL && destination != activeChannelReturnPage) {
                                switchChannelToTopLevelPage(destination, activeChannelReturnPage)
                            } else {
                                navigateToTopLevelPage(
                                    destination,
                                    usePager = !windowInfo.isMediumOrExpanded
                                )
                            }
                        }
                    )
                    VerticalDivider(
                        modifier = Modifier
                            .zIndex(2f)
                            .fillMaxHeight()
                            .padding(
                                top = contentPadding.calculateTopPadding(),
                                bottom = contentPadding.calculateBottomPadding()
                            )
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                    channelTabSwitchDestination?.takeUnless { windowInfo.isMediumOrExpanded }?.let { destination ->
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
                                    windowInfo = windowInfo,
                                    onSyncLibrary = onSyncLibrary,
                                    onSyncAccount = onSyncAccount,
                                    onOpenProfile = onOpenProfile,
                                    onOpenNotes = onOpenNotes,
                                    showAccountActions = showAccountFeatures,
                                    onExportBluetoothLog = onExportBluetoothLog,
                                    onOpenRss = {
                                        navigateToTopLevelPage(
                                            MainPage.RSS,
                                            usePager = !windowInfo.isMediumOrExpanded
                                        )
                                    },
                                    onOpenFavorites = {
                                        navigateToContentChannel(
                                            CONTENT_CHANNEL_FAVORITES,
                                            MainPage.RSS,
                                            MainPage.DASHBOARD
                                        )
                                    },
                                    onOpenWatchLater = {
                                        navigateToContentChannel(
                                            CONTENT_CHANNEL_WATCH_LATER,
                                            MainPage.RSS,
                                            MainPage.DASHBOARD
                                        )
                                    },
                                    onOpenIndependent = {
                                        navigateToContentChannel(
                                            CONTENT_CHANNEL_INDEPENDENT,
                                            MainPage.RSS,
                                            MainPage.DASHBOARD
                                        )
                                    },
                                    onOpenImportedContent = {
                                        navigateToContentChannel(
                                            CONTENT_CHANNEL_IMPORTED_TEXT,
                                            MainPage.RSS,
                                            MainPage.DASHBOARD
                                        )
                                    },
                                    onDismissMessage = onDismissMessage
                                )

                                MainPage.RSS -> ContentPage(
                                    uiState = uiState,
                                    channels = contentChannels,
                                    contentPadding = contentPadding,
                                    windowInfo = windowInfo,
                                    listState = contentPageListState,
                                    onOpenChannel = { channel ->
                                        navigateToContentChannel(channel.key, MainPage.RSS, MainPage.RSS)
                                    },
                                    onMoveToTop = onMoveRssSourceToTop,
                                    onReorderContentChannels = onReorderContentChannels,
                                    onTogglePinned = onToggleRssSourcePinned,
                                    onDelete = onDeleteRssSource,
                                    onRefreshAllRssSources = onRefreshAllRssSources
                                )

                                MainPage.IMPORTS -> ImportsPage(
                                    uiState = uiState,
                                    contentPadding = contentPadding,
                                    windowInfo = windowInfo,
                                    listState = importsPageListState,
                                    onUrlChange = onUrlChange,
                                    onImportArticle = onImportArticle,
                                    onImportFile = onImportFile,
                                    onAddRssSource = onAddRssSource,
                                    onExportBackup = onExportBackup,
                                    onDismissMessage = onDismissMessage,
                                    recentEntries = buildRecentImportEntries(contentChannels, uiState.independentArticles),
                                    onOpenChannel = { channel ->
                                        navigateToContentChannel(channel.key, MainPage.IMPORTS, MainPage.IMPORTS)
                                    },
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
                                windowInfo = windowInfo,
                                onSyncLibrary = onSyncLibrary,
                                onSyncAccount = onSyncAccount,
                                    onOpenProfile = onOpenProfile,
                                    onOpenNotes = onOpenNotes,
                                showAccountActions = showAccountFeatures,
                                onExportBluetoothLog = onExportBluetoothLog,
                                onOpenRss = {
                                    navigateToTopLevelPage(
                                        MainPage.RSS,
                                        usePager = !windowInfo.isMediumOrExpanded
                                    )
                                },
                                onOpenFavorites = {
                                    navigateToContentChannel(
                                        CONTENT_CHANNEL_FAVORITES,
                                        MainPage.RSS,
                                        MainPage.DASHBOARD
                                    )
                                },
                                onOpenWatchLater = {
                                    navigateToContentChannel(
                                        CONTENT_CHANNEL_WATCH_LATER,
                                        MainPage.RSS,
                                        MainPage.DASHBOARD
                                    )
                                },
                                onOpenIndependent = {
                                    navigateToContentChannel(
                                        CONTENT_CHANNEL_INDEPENDENT,
                                        MainPage.RSS,
                                        MainPage.DASHBOARD
                                    )
                                },
                                onOpenImportedContent = {
                                    navigateToContentChannel(
                                        CONTENT_CHANNEL_IMPORTED_TEXT,
                                        MainPage.RSS,
                                        MainPage.DASHBOARD
                                    )
                                },
                                onDismissMessage = onDismissMessage
                            )
                        }
                    }
                    @Composable
                    fun RenderTopLevelPage(topLevelPage: MainPage) {
                        when (topLevelPage) {
                            MainPage.DASHBOARD -> DashboardPage(
                                uiState = uiState,
                                articlesBySource = articlesBySource,
                                contentPadding = contentPadding,
                                windowInfo = windowInfo,
                                onSyncLibrary = onSyncLibrary,
                                onSyncAccount = onSyncAccount,
                                onOpenProfile = onOpenProfile,
                                onOpenNotes = onOpenNotes,
                                showAccountActions = showAccountFeatures,
                                onExportBluetoothLog = onExportBluetoothLog,
                                onOpenRss = {
                                    navigateToTopLevelPage(
                                        MainPage.RSS,
                                        usePager = !windowInfo.isMediumOrExpanded
                                    )
                                },
                                onOpenFavorites = {
                                    openAdaptiveContentChannel(
                                        CONTENT_CHANNEL_FAVORITES,
                                        MainPage.RSS,
                                        MainPage.DASHBOARD
                                    )
                                },
                                onOpenWatchLater = {
                                    openAdaptiveContentChannel(
                                        CONTENT_CHANNEL_WATCH_LATER,
                                        MainPage.RSS,
                                        MainPage.DASHBOARD
                                    )
                                },
                                onOpenIndependent = {
                                    openAdaptiveContentChannel(
                                        CONTENT_CHANNEL_INDEPENDENT,
                                        MainPage.RSS,
                                        MainPage.DASHBOARD
                                    )
                                },
                                onOpenImportedContent = {
                                    openAdaptiveContentChannel(
                                        CONTENT_CHANNEL_IMPORTED_TEXT,
                                        MainPage.RSS,
                                        MainPage.DASHBOARD
                                    )
                                },
                                onDismissMessage = onDismissMessage
                            )

                            MainPage.RSS -> {
                                if (selectedReaderArticle != null) {
                                    val selectedChannel = selectedContentChannel
                                    if (windowInfo.isMediumOrExpanded && selectedChannel != null) {
                                        val startPane: @Composable () -> Unit = {
                                            ContentPage(
                                                uiState = uiState,
                                                channels = contentChannels,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = contentPageListState,
                                                selectedChannelKey = selectedContentChannelKey,
                                                onOpenChannel = { channel ->
                                                    openAdaptiveContentChannel(channel.key, MainPage.RSS, MainPage.RSS)
                                                },
                                                onMoveToTop = onMoveRssSourceToTop,
                                                onReorderContentChannels = onReorderContentChannels,
                                                onTogglePinned = onToggleRssSourcePinned,
                                                onDelete = onDeleteRssSource,
                                                onRefreshAllRssSources = onRefreshAllRssSources
                                            )
                                        }
                                        val movingPane: @Composable (Float) -> Unit = { progress ->
                                            val movingPaneSource = selectedChannel.source
                                            ReaderReturnMovingArticlePane(
                                                progress = progress,
                                                channel = selectedChannel,
                                                selectedArticleId = selectedReaderArticle.articleId,
                                                isRefreshing = movingPaneSource?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = channelArticleListState,
                                                onRefreshSource = {
                                                    if (selectedChannel.canRefresh) {
                                                        movingPaneSource?.let(onRefreshRssSource)
                                                    }
                                                },
                                                onOpenChannelSettings = { source ->
                                                    channelSettingsSourceUrl = source.url
                                                },
                                                onBackToChannels = { handleInlineReaderBack() },
                                                onOpenArticle = { article ->
                                                    openAdaptiveArticle(article)
                                                },
                                                onOpenOriginalLink = { uriHandler.openUri(it) },
                                                onToggleFavorite = onToggleFavorite,
                                                onToggleWatchLater = onToggleWatchLater,
                                                onDeleteArticle = onDeleteArticle
                                            )
                                        }
                                        val readerReturnAnimating = !readerFullscreenActive &&
                                            (readerBackAnimating || readerBackProgress > 0f)
                                        if (readerOpenAnimating || readerOpenProgress < 1f) {
                                            ActiveReaderOpenThreePane(
                                                progress = readerOpenProgress,
                                                windowInfo = windowInfo,
                                                startPane = startPane,
                                                movingPane = movingPane,
                                                readerPane = activeInlineReaderPane
                                            )
                                        } else if (readerReturnAnimating) {
                                            ActiveReaderReturnTwoPane(
                                                progress = readerBackProgress,
                                                windowInfo = windowInfo,
                                                startPane = startPane,
                                                movingPane = movingPane,
                                                readerPane = activeInlineReaderPane
                                            )
                                        } else {
                                            AdaptiveReadingPane(
                                                windowInfo = windowInfo,
                                                fullscreen = readerFullscreenActive,
                                                predictiveBackProgress = readerBackProgress,
                                                fullscreenBackProgress = readerFullscreenBackProgress,
                                                startPane = {
                                                    ReaderReturnMovingArticlePane(
                                                        progress = 0f,
                                                        channel = selectedChannel,
                                                        selectedArticleId = selectedReaderArticle.articleId,
                                                        isRefreshing = selectedChannel.source?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                                                        contentPadding = contentPadding,
                                                        windowInfo = windowInfo,
                                                        listState = channelArticleListState,
                                                        onRefreshSource = {
                                                            if (selectedChannel.canRefresh) {
                                                                selectedChannel.source?.let(onRefreshRssSource)
                                                            }
                                                        },
                                                        onOpenChannelSettings = { source ->
                                                            channelSettingsSourceUrl = source.url
                                                        },
                                                        onBackToChannels = { handleInlineReaderBack() },
                                                        onOpenArticle = { article ->
                                                            openAdaptiveArticle(article)
                                                        },
                                                        onOpenOriginalLink = { uriHandler.openUri(it) },
                                                        onToggleFavorite = onToggleFavorite,
                                                        onToggleWatchLater = onToggleWatchLater,
                                                        onDeleteArticle = onDeleteArticle
                                                    )
                                                },
                                                readerPane = activeInlineReaderPane
                                            )
                                        }
                                    } else {
                                        AdaptiveReadingPane(
                                            windowInfo = windowInfo,
                                            fullscreen = readerFullscreenActive || !windowInfo.isMediumOrExpanded,
                                            predictiveBackProgress = readerBackProgress,
                                            fullscreenBackProgress = readerFullscreenBackProgress,
                                            startPane = {
                                                if (selectedChannel != null) {
                                                    ChannelArticleListPane(
                                                        channel = selectedChannel,
                                                        selectedArticleId = selectedReaderArticle.articleId,
                                                        contentPadding = contentPadding,
                                                        windowInfo = windowInfo,
                                                        listState = channelArticleListState,
                                                        onBackToChannels = { handleInlineReaderBack() },
                                                        onOpenArticle = { article ->
                                                            openAdaptiveArticle(article)
                                                        }
                                                    )
                                                } else {
                                                    ContentPage(
                                                        uiState = uiState,
                                                        channels = contentChannels,
                                                        contentPadding = contentPadding,
                                                        windowInfo = windowInfo,
                                                        listState = contentPageListState,
                                                        selectedChannelKey = selectedContentChannelKey,
                                                        onOpenChannel = { channel ->
                                                            openAdaptiveContentChannel(channel.key, MainPage.RSS, MainPage.RSS)
                                                        },
                                                        onMoveToTop = onMoveRssSourceToTop,
                                                        onReorderContentChannels = onReorderContentChannels,
                                                        onTogglePinned = onToggleRssSourcePinned,
                                                        onDelete = onDeleteRssSource,
                                                        onRefreshAllRssSources = onRefreshAllRssSources
                                                    )
                                                }
                                            },
                                            readerPane = activeInlineReaderPane
                                        )
                                    }
                                } else if (windowInfo.isMediumOrExpanded) {
                                    val returnTransition = readerLeftPaneReturnState
                                        ?.takeIf { it.returnPage == MainPage.RSS }
                                    ReaderReturnTwoPane(
                                        transitionState = returnTransition,
                                        windowInfo = windowInfo,
                                        onTransitionFinished = { finishedState ->
                                            if (readerLeftPaneReturnState == finishedState) {
                                                readerLeftPaneReturnState = null
                                            }
                                        },
                                        startPane = {
                                            ContentPage(
                                                uiState = uiState,
                                                channels = contentChannels,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = contentPageListState,
                                                selectedChannelKey = selectedContentChannelKey,
                                                onOpenChannel = { channel ->
                                                    openAdaptiveContentChannel(channel.key, MainPage.RSS, MainPage.RSS)
                                                },
                                                onMoveToTop = onMoveRssSourceToTop,
                                                onReorderContentChannels = onReorderContentChannels,
                                                onTogglePinned = onToggleRssSourcePinned,
                                                onDelete = onDeleteRssSource,
                                                onRefreshAllRssSources = onRefreshAllRssSources
                                            )
                                        },
                                        endPane = {
                                            val paneSource = selectedContentChannel?.source
                                            ChannelArticlePane(
                                                channel = selectedContentChannel,
                                                isRefreshing = paneSource?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = channelArticleListState,
                                                onRefreshSource = {
                                                    if (selectedContentChannel?.canRefresh == true) {
                                                        paneSource?.let(onRefreshRssSource)
                                                    }
                                                },
                                                onOpenChannelSettings = { source ->
                                                    channelSettingsSourceUrl = source.url
                                                },
                                                onOpenArticle = { article ->
                                                    openAdaptiveArticle(article)
                                                },
                                                onOpenOriginalLink = { uriHandler.openUri(it) },
                                                onToggleFavorite = onToggleFavorite,
                                                onToggleWatchLater = onToggleWatchLater,
                                                onDeleteArticle = onDeleteArticle
                                            )
                                        },
                                        movingPane = { progress ->
                                            val movingChannel = returnTransition?.let { state ->
                                                contentChannels.firstOrNull { it.key == state.channelKey }
                                            } ?: selectedContentChannel
                                            val movingPaneSource = movingChannel?.source
                                            ReaderReturnMovingArticlePane(
                                                progress = progress,
                                                channel = movingChannel,
                                                selectedArticleId = returnTransition?.articleId,
                                                isRefreshing = movingPaneSource?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = channelArticleListState,
                                                onRefreshSource = {
                                                    if (movingChannel?.canRefresh == true) {
                                                        movingPaneSource?.let(onRefreshRssSource)
                                                    }
                                                },
                                                onOpenChannelSettings = { source ->
                                                    channelSettingsSourceUrl = source.url
                                                },
                                                onBackToChannels = { readerLeftPaneReturnState = null },
                                                onOpenArticle = { article ->
                                                    openAdaptiveArticle(article)
                                                },
                                                onOpenOriginalLink = { uriHandler.openUri(it) },
                                                onToggleFavorite = onToggleFavorite,
                                                onToggleWatchLater = onToggleWatchLater,
                                                onDeleteArticle = onDeleteArticle
                                            )
                                        }
                                    )
                                } else {
                                    ContentPage(
                                        uiState = uiState,
                                        channels = contentChannels,
                                        contentPadding = contentPadding,
                                        windowInfo = windowInfo,
                                        listState = contentPageListState,
                                        onOpenChannel = { channel ->
                                            navigateToContentChannel(channel.key, MainPage.RSS, MainPage.RSS)
                                        },
                                        onMoveToTop = onMoveRssSourceToTop,
                                        onReorderContentChannels = onReorderContentChannels,
                                        onTogglePinned = onToggleRssSourcePinned,
                                        onDelete = onDeleteRssSource,
                                        onRefreshAllRssSources = onRefreshAllRssSources
                                    )
                                }
                            }

                            MainPage.IMPORTS -> {
                                if (selectedReaderArticle != null) {
                                    val selectedChannel = selectedContentChannel
                                    if (windowInfo.isMediumOrExpanded && selectedChannel != null) {
                                        val startPane: @Composable () -> Unit = {
                                            ImportsPage(
                                                uiState = uiState,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = importsPageListState,
                                                selectedChannelKey = selectedContentChannelKey,
                                                onUrlChange = onUrlChange,
                                                onImportArticle = onImportArticle,
                                                onImportFile = onImportFile,
                                                onAddRssSource = onAddRssSource,
                                                onExportBackup = onExportBackup,
                                                onDismissMessage = onDismissMessage,
                                                recentEntries = buildRecentImportEntries(contentChannels, uiState.independentArticles),
                                                onOpenChannel = { channel ->
                                                    openAdaptiveContentChannel(
                                                        channel.key,
                                                        MainPage.IMPORTS,
                                                        MainPage.IMPORTS
                                                    )
                                                },
                                                onOpenArticle = { article -> openIndependentArticleFromImports(article) },
                                                onOpenOriginalLink = { uriHandler.openUri(it) },
                                                onToggleFavorite = onToggleFavorite,
                                                onToggleWatchLater = onToggleWatchLater,
                                                onDeleteArticle = onDeleteArticle
                                            )
                                        }
                                        val movingPane: @Composable (Float) -> Unit = { progress ->
                                            val movingPaneSource = selectedChannel.source
                                            ReaderReturnMovingArticlePane(
                                                progress = progress,
                                                channel = selectedChannel,
                                                selectedArticleId = selectedReaderArticle.articleId,
                                                isRefreshing = movingPaneSource?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = channelArticleListState,
                                                onRefreshSource = {
                                                    if (selectedChannel.canRefresh) {
                                                        movingPaneSource?.let(onRefreshRssSource)
                                                    }
                                                },
                                                onOpenChannelSettings = { source ->
                                                    channelSettingsSourceUrl = source.url
                                                },
                                                onBackToChannels = { handleInlineReaderBack() },
                                                onOpenArticle = { article ->
                                                    openAdaptiveArticle(article)
                                                },
                                                onOpenOriginalLink = { uriHandler.openUri(it) },
                                                onToggleFavorite = onToggleFavorite,
                                                onToggleWatchLater = onToggleWatchLater,
                                                onDeleteArticle = onDeleteArticle
                                            )
                                        }
                                        val readerReturnAnimating = !readerFullscreenActive &&
                                            (readerBackAnimating || readerBackProgress > 0f)
                                        if (readerOpenAnimating || readerOpenProgress < 1f) {
                                            ActiveReaderOpenThreePane(
                                                progress = readerOpenProgress,
                                                windowInfo = windowInfo,
                                                startPane = startPane,
                                                movingPane = movingPane,
                                                readerPane = activeInlineReaderPane
                                            )
                                        } else if (readerReturnAnimating) {
                                            ActiveReaderReturnTwoPane(
                                                progress = readerBackProgress,
                                                windowInfo = windowInfo,
                                                startPane = startPane,
                                                movingPane = movingPane,
                                                readerPane = activeInlineReaderPane
                                            )
                                        } else {
                                            AdaptiveReadingPane(
                                                windowInfo = windowInfo,
                                                fullscreen = readerFullscreenActive,
                                                predictiveBackProgress = readerBackProgress,
                                                fullscreenBackProgress = readerFullscreenBackProgress,
                                                startPane = {
                                                    ReaderReturnMovingArticlePane(
                                                        progress = 0f,
                                                        channel = selectedChannel,
                                                        selectedArticleId = selectedReaderArticle.articleId,
                                                        isRefreshing = selectedChannel.source?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                                                        contentPadding = contentPadding,
                                                        windowInfo = windowInfo,
                                                        listState = channelArticleListState,
                                                        onRefreshSource = {
                                                            if (selectedChannel.canRefresh) {
                                                                selectedChannel.source?.let(onRefreshRssSource)
                                                            }
                                                        },
                                                        onOpenChannelSettings = { source ->
                                                            channelSettingsSourceUrl = source.url
                                                        },
                                                        onBackToChannels = { handleInlineReaderBack() },
                                                        onOpenArticle = { article ->
                                                            openAdaptiveArticle(article)
                                                        },
                                                        onOpenOriginalLink = { uriHandler.openUri(it) },
                                                        onToggleFavorite = onToggleFavorite,
                                                        onToggleWatchLater = onToggleWatchLater,
                                                        onDeleteArticle = onDeleteArticle
                                                    )
                                                },
                                                readerPane = activeInlineReaderPane
                                            )
                                        }
                                    } else {
                                        AdaptiveReadingPane(
                                            windowInfo = windowInfo,
                                            fullscreen = readerFullscreenActive || !windowInfo.isMediumOrExpanded,
                                            predictiveBackProgress = readerBackProgress,
                                            fullscreenBackProgress = readerFullscreenBackProgress,
                                            startPane = {
                                                if (selectedChannel != null) {
                                                    ChannelArticleListPane(
                                                        channel = selectedChannel,
                                                        selectedArticleId = selectedReaderArticle.articleId,
                                                        contentPadding = contentPadding,
                                                        windowInfo = windowInfo,
                                                        listState = channelArticleListState,
                                                        onBackToChannels = { handleInlineReaderBack() },
                                                        onOpenArticle = { article ->
                                                            openAdaptiveArticle(article)
                                                        }
                                                    )
                                                } else {
                                                    ImportsPage(
                                                        uiState = uiState,
                                                        contentPadding = contentPadding,
                                                        windowInfo = windowInfo,
                                                        listState = importsPageListState,
                                                        selectedChannelKey = selectedContentChannelKey,
                                                        onUrlChange = onUrlChange,
                                                        onImportArticle = onImportArticle,
                                                        onImportFile = onImportFile,
                                                        onAddRssSource = onAddRssSource,
                                                        onExportBackup = onExportBackup,
                                                        onDismissMessage = onDismissMessage,
                                                        recentEntries = buildRecentImportEntries(contentChannels, uiState.independentArticles),
                                                        onOpenChannel = { channel ->
                                                            openAdaptiveContentChannel(
                                                                channel.key,
                                                                MainPage.IMPORTS,
                                                                MainPage.IMPORTS
                                                            )
                                                        },
                                                        onOpenArticle = { article -> openIndependentArticleFromImports(article) },
                                                        onOpenOriginalLink = { uriHandler.openUri(it) },
                                                        onToggleFavorite = onToggleFavorite,
                                                        onToggleWatchLater = onToggleWatchLater,
                                                        onDeleteArticle = onDeleteArticle
                                                    )
                                                }
                                            },
                                            readerPane = activeInlineReaderPane
                                        )
                                    }
                                } else if (windowInfo.isMediumOrExpanded) {
                                    val returnTransition = readerLeftPaneReturnState
                                        ?.takeIf { it.returnPage == MainPage.IMPORTS }
                                    ReaderReturnTwoPane(
                                        transitionState = returnTransition,
                                        windowInfo = windowInfo,
                                        onTransitionFinished = { finishedState ->
                                            if (readerLeftPaneReturnState == finishedState) {
                                                readerLeftPaneReturnState = null
                                            }
                                        },
                                        startPane = {
                                            ImportsPage(
                                                uiState = uiState,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = importsPageListState,
                                                selectedChannelKey = selectedContentChannelKey,
                                                onUrlChange = onUrlChange,
                                                onImportArticle = onImportArticle,
                                                onImportFile = onImportFile,
                                                onAddRssSource = onAddRssSource,
                                                onExportBackup = onExportBackup,
                                                onDismissMessage = onDismissMessage,
                                                recentEntries = buildRecentImportEntries(contentChannels, uiState.independentArticles),
                                                onOpenChannel = { channel ->
                                                    openAdaptiveContentChannel(
                                                        channel.key,
                                                        MainPage.IMPORTS,
                                                        MainPage.IMPORTS
                                                    )
                                                },
                                                onOpenArticle = { article -> openIndependentArticleFromImports(article) },
                                                onOpenOriginalLink = { uriHandler.openUri(it) },
                                                onToggleFavorite = onToggleFavorite,
                                                onToggleWatchLater = onToggleWatchLater,
                                                onDeleteArticle = onDeleteArticle
                                            )
                                        },
                                        endPane = {
                                            val paneSource = selectedContentChannel?.source
                                            ChannelArticlePane(
                                                channel = selectedContentChannel,
                                                isRefreshing = paneSource?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = channelArticleListState,
                                                onRefreshSource = {
                                                    if (selectedContentChannel?.canRefresh == true) {
                                                        paneSource?.let(onRefreshRssSource)
                                                    }
                                                },
                                                onOpenChannelSettings = { source ->
                                                    channelSettingsSourceUrl = source.url
                                                },
                                                onOpenArticle = { article ->
                                                    openAdaptiveArticle(article)
                                                },
                                                onOpenOriginalLink = { uriHandler.openUri(it) },
                                                onToggleFavorite = onToggleFavorite,
                                                onToggleWatchLater = onToggleWatchLater,
                                                onDeleteArticle = onDeleteArticle
                                            )
                                        },
                                        movingPane = { progress ->
                                            val movingChannel = returnTransition?.let { state ->
                                                contentChannels.firstOrNull { it.key == state.channelKey }
                                            } ?: selectedContentChannel
                                            val movingPaneSource = movingChannel?.source
                                            ReaderReturnMovingArticlePane(
                                                progress = progress,
                                                channel = movingChannel,
                                                selectedArticleId = returnTransition?.articleId,
                                                isRefreshing = movingPaneSource?.url?.let { it in uiState.refreshingRssSourceUrls } == true,
                                                contentPadding = contentPadding,
                                                windowInfo = windowInfo,
                                                listState = channelArticleListState,
                                                onRefreshSource = {
                                                    if (movingChannel?.canRefresh == true) {
                                                        movingPaneSource?.let(onRefreshRssSource)
                                                    }
                                                },
                                                onOpenChannelSettings = { source ->
                                                    channelSettingsSourceUrl = source.url
                                                },
                                                onBackToChannels = { readerLeftPaneReturnState = null },
                                                onOpenArticle = { article ->
                                                    openAdaptiveArticle(article)
                                                },
                                                onOpenOriginalLink = { uriHandler.openUri(it) },
                                                onToggleFavorite = onToggleFavorite,
                                                onToggleWatchLater = onToggleWatchLater,
                                                onDeleteArticle = onDeleteArticle
                                            )
                                        }
                                    )
                                } else {
                                    ImportsPage(
                                        uiState = uiState,
                                        contentPadding = contentPadding,
                                        windowInfo = windowInfo,
                                        listState = importsPageListState,
                                        onUrlChange = onUrlChange,
                                        onImportArticle = onImportArticle,
                                        onImportFile = onImportFile,
                                        onAddRssSource = onAddRssSource,
                                        onExportBackup = onExportBackup,
                                        onDismissMessage = onDismissMessage,
                                        recentEntries = buildRecentImportEntries(contentChannels, uiState.independentArticles),
                                        onOpenChannel = { channel ->
                                            navigateToContentChannel(
                                                channel.key,
                                                MainPage.IMPORTS,
                                                MainPage.IMPORTS
                                            )
                                        },
                                        onOpenArticle = onOpenArticle,
                                        onOpenOriginalLink = { uriHandler.openUri(it) },
                                        onToggleFavorite = onToggleFavorite,
                                        onToggleWatchLater = onToggleWatchLater,
                                        onDeleteArticle = onDeleteArticle
                                    )
                                }
                            }

                            MainPage.CHANNEL -> Unit
                        }
                    }

                    if (windowInfo.isMediumOrExpanded) {
                        AnimatedContent(
                            targetState = currentTopLevelPage,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (tabBackActive) {
                                        Modifier.tabPredictiveBackPreview(tabBackProgress)
                                    } else {
                                        Modifier
                                    }
                                )
                                .zIndex(if (tabBackActive) 1f else 0f),
                            transitionSpec = {
                                if (suppressNextTopLevelTransition) {
                                    fadeIn(animationSpec = tween(0)) togetherWith
                                        fadeOut(animationSpec = tween(0))
                                } else {
                                    val direction = if (
                                        targetState.topLevelIndex() >= initialState.topLevelIndex()
                                    ) {
                                        1
                                    } else {
                                        -1
                                    }
                                    (
                                        slideInHorizontally(
                                            animationSpec = tween(CHANNEL_TRANSITION_MS)
                                        ) { fullWidth -> fullWidth / 4 * direction } +
                                            fadeIn(animationSpec = tween(CHANNEL_TRANSITION_MS))
                                        ) togetherWith (
                                        slideOutHorizontally(
                                            animationSpec = tween(CHANNEL_TRANSITION_MS)
                                        ) { fullWidth -> -fullWidth / 4 * direction } +
                                            fadeOut(animationSpec = tween(CHANNEL_TRANSITION_MS))
                                        )
                                }
                            },
                            label = "WideTopLevelPage"
                        ) { topLevelPage ->
                            RenderTopLevelPage(topLevelPage)
                        }
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            userScrollEnabled = true,
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
                            RenderTopLevelPage(TopLevelMainPages[pageIndex])
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = page == MainPage.CHANNEL &&
                            !windowInfo.isMediumOrExpanded &&
                            selectedReaderArticle == null,
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
                            windowInfo = windowInfo,
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
        }
    }
    }

    channelSettingsSource?.let { source ->
        val canRefresh = !ImportedContentIds.isImportedContentUrl(source.url)
        val hasPlayableMedia = uiState.rssArticles.any { article ->
            article.rssSourceUrl == source.url &&
                (
                    PlatformLinkRouter.detect(article.url) != null ||
                        listOf("<audio", "<video", "<iframe").any {
                            article.contentHtml.orEmpty().contains(it, ignoreCase = true)
                        }
                )
        }
        ChannelSettingsSheet(
            source = source,
            canRefresh = canRefresh,
            showContinuePlaybackInBackground = hasPlayableMedia,
            clearEnabled = ImportedContentIds.isImportedContentUrl(source.url),
            isRefreshing = source.url in uiState.refreshingRssSourceUrls,
            isBusy = uiState.isBusy,
            initialRssInventoryMode = rssInventoryMode(source.url),
            onDismiss = { channelSettingsSourceUrl = null },
            onTogglePinned = { onToggleRssSourcePinned(source) },
            onToggleOriginalContent = { enabled ->
                onSetRssSourceOriginalContentEnabled(source, enabled)
            },
            onToggleContinuePlaybackInBackground = { enabled ->
                onSetRssSourceContinuePlaybackInBackground(source, enabled)
            },
            onSetRssInventoryMode = { mode ->
                onSetRssInventoryMode(source.url, mode)
            },
            onMoveToTop = { onMoveRssSourceToTop(source) },
            onRefresh = { onRefreshRssSource(source) },
            onClear = { onClearRssSourceContent(source) },
            onDelete = {
                channelSettingsSourceUrl = null
                if (selectedContentChannel?.source?.url == source.url) {
                    selectedContentChannelKey = null
                }
                onDeleteRssSource(source)
            }
        )
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
    uiState.backupImportPrompt?.let { prompt ->
        MainScreenBackupImportDialog(
            prompt = prompt,
            onMerge = { onImportBackup(BackupImportMode.MERGE) },
            onRequestReplace = onRequestBackupReplace,
            onConfirmReplace = { onImportBackup(BackupImportMode.REPLACE) },
            onDismiss = onDismissBackupImport
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
private fun ChannelSettingsSheet(
    source: PhoneRssSourceEntity,
    canRefresh: Boolean,
    showContinuePlaybackInBackground: Boolean,
    clearEnabled: Boolean,
    isRefreshing: Boolean,
    isBusy: Boolean,
    initialRssInventoryMode: CloudRssInventoryMode,
    onDismiss: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleOriginalContent: (Boolean) -> Unit,
    onToggleContinuePlaybackInBackground: (Boolean) -> Unit,
    onSetRssInventoryMode: (CloudRssInventoryMode) -> Unit,
    onMoveToTop: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember(source.url) { mutableStateOf(false) }
    var showClearConfirm by remember(source.url) { mutableStateOf(false) }
    var inventoryMode by remember(source.url) { mutableStateOf(initialRssInventoryMode) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "频道设置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = source.title.ifBlank { source.url },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = source.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (canRefresh) {
                ListItem(
                    headlineContent = { Text("原文阅读模式") },
                    supportingContent = { Text("刷新时抓取原文正文与图片") },
                    trailingContent = {
                        Switch(
                            checked = source.useOriginalContent,
                            onCheckedChange = onToggleOriginalContent,
                            enabled = !isBusy
                        )
                    }
                )
            }
            if (showContinuePlaybackInBackground) {
                ListItem(
                    headlineContent = { Text("在后台继续播放") },
                    trailingContent = {
                        Switch(
                            checked = source.continuePlaybackInBackground,
                            onCheckedChange = onToggleContinuePlaybackInBackground,
                            enabled = !isBusy
                        )
                    }
                )
            }
            if (canRefresh) {
                Text(
                    text = "云同步文章数量",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        CloudRssInventoryMode.RECENT_128 to "最近128条",
                        CloudRssInventoryMode.ALL to "全部"
                    ).forEach { (mode, label) ->
                        OutlinedButton(
                            onClick = {
                                inventoryMode = mode
                                onSetRssInventoryMode(mode)
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (inventoryMode == mode) "✓ $label" else label)
                        }
                    }
                }
                Text(
                    text = "“全部”最多同步8192条文章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ListItem(
                headlineContent = { Text("置顶频道") },
                supportingContent = { Text("置顶后显示在普通频道之前") },
                trailingContent = {
                    Switch(
                        checked = source.isPinned,
                        onCheckedChange = { onTogglePinned() },
                        enabled = !isBusy
                    )
                }
            )
            OutlinedButton(
                onClick = onMoveToTop,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("移到顶部")
            }
            if (canRefresh) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isBusy && !isRefreshing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isRefreshing) "正在刷新…" else "刷新频道")
                }
            }
            if (clearEnabled) {
                TextButton(
                    onClick = { showClearConfirm = true },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "清空内容",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            TextButton(
                onClick = { showDeleteConfirm = true },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "删除频道",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除频道") },
            text = { Text("删除后将从内容列表移除该频道。") },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空内容") },
            text = { Text("清空后将移除这个频道内的本地条目。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClear()
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun MainScreenBackupImportDialog(
    prompt: BackupImportPromptUi,
    onMerge: () -> Unit,
    onRequestReplace: () -> Unit,
    onConfirmReplace: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_backup_import"),
        title = {
            Text(if (prompt.confirmingReplace) "确认覆盖资料库" else "导入 WRSS 备份")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prompt.confirmingReplace) {
                    Text("当前资料库会被备份内容完整替换，此操作无法撤销。")
                } else {
                    Text(
                        text = prompt.fileName,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "导出时间：${formatBackupTime(prompt.preview.exportedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${prompt.preview.articleCount} 篇文章 · ${prompt.preview.sourceCount} 个 RSS 源",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "合并会保留较新的内容；覆盖会删除当前备份中没有的内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prompt.confirmingReplace) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onConfirmReplace
                    ) {
                        Text("确认覆盖")
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onMerge
                    ) {
                        Text("合并")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRequestReplace
                    ) {
                        Text("覆盖")
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

private fun formatBackupTime(timestamp: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(timestamp))
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

@Composable
private fun InlineArticleReaderPane(
    article: PhoneArticleEntity,
    readerArticle: PhoneArticleEntity?,
    importedTextReader: PhoneImportedTextReader?,
    onLoadImportedTextChunk: suspend (String, Int) -> String?,
    onSaveReadingProgress: suspend (Float) -> Unit,
    onBack: () -> Unit,
    onOpenImportedArticle: (String) -> Unit,
    onOpenOriginal: (String) -> Unit,
    listState: LazyListState,
    contentReady: Boolean,
    contentNodesCache: MutableMap<ArticleContentNodesKey, ArticleContentNodesSnapshot>,
    positionAlreadyRestored: Boolean,
    onPositionRestored: (String) -> Unit,
    fullscreen: Boolean,
    continuePlaybackInBackground: Boolean,
    showFullscreenControl: Boolean,
    onToggleFullscreen: () -> Unit
) {
    val fullscreenControl = if (showFullscreenControl) onToggleFullscreen else null
    val platform = PlatformLinkRouter.detect(article.url)
    if (contentReady && platform != null) {
        PlatformWebViewScreen(
            url = article.url,
            title = article.title.ifBlank { article.url },
            platform = platform,
            onBack = onBack,
            onOpenExternal = { onOpenOriginal(article.url) },
            continuePlaybackInBackground = continuePlaybackInBackground,
            embedded = true,
            initialScrollProgress = article.readingProgress,
            onSaveScrollProgress = onSaveReadingProgress,
            embeddedFullscreen = fullscreen,
            onOpenFullscreen = fullscreenControl
        )
        return
    }
    val loadedReaderArticle = readerArticle?.takeIf { it.articleId == article.articleId }
    val articleContentReady = contentReady && loadedReaderArticle != null
    ArticleReaderScreen(
        article = loadedReaderArticle ?: article,
        importedTextReader = importedTextReader,
        invalidArticleId = article.articleId.isBlank(),
        onLoadImportedTextChunk = onLoadImportedTextChunk,
        onSaveReadingProgress = onSaveReadingProgress,
        onBack = onBack,
        onOpenImportedArticle = onOpenImportedArticle,
        onOpenOriginal = onOpenOriginal,
        embedded = true,
        embeddedFullscreen = fullscreen,
        onOpenFullscreen = fullscreenControl,
        listState = listState,
        contentReady = articleContentReady,
        contentNodesCache = contentNodesCache,
        positionAlreadyRestored = positionAlreadyRestored,
        onPositionRestored = onPositionRestored
    )
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
    onOpenChannelSettings: () -> Unit,
    onExportBluetoothLog: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when (page) {
        MainPage.DASHBOARD -> "腕上RSS"
        MainPage.RSS -> "内容"
        MainPage.IMPORTS -> "导入"
        MainPage.CHANNEL -> selectedChannel?.title?.takeIf { it.isNotBlank() } ?: "频道"
    }
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (page == MainPage.CHANNEL && selectedChannel?.source != null) {
                    IconButton(
                        onClick = onOpenChannelSettings,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "频道设置",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (page == MainPage.CHANNEL) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("topbar_back")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = {
            when (page) {
                MainPage.DASHBOARD -> {
                    IconButton(
                        onClick = onExportBluetoothLog,
                        modifier = Modifier.testTag("topbar_export_log")
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = "导出蓝牙日志")
                    }
                    IconButton(
                        onClick = onOpenProfile,
                        modifier = Modifier.testTag("topbar_profile")
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "我的"
                        )
                    }
                }
                MainPage.RSS -> IconButton(
                    onClick = onRefreshAllRssSources,
                    enabled = canRefreshRss,
                    modifier = Modifier.testTag("topbar_refresh_all")
                ) {
                    Icon(Icons.Default.Sync, contentDescription = "刷新内容")
                }
                MainPage.CHANNEL -> IconButton(
                    onClick = onRefreshSelectedSource,
                    enabled = canRefreshSource,
                    modifier = Modifier.testTag("topbar_refresh_channel")
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
private fun SplitPaneTopBar(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                content = action
            )
        }
    }
}

private fun MainPage.label(): String = when (this) {
    MainPage.DASHBOARD -> "总览"
    MainPage.RSS -> "内容"
    MainPage.IMPORTS -> "导入"
    MainPage.CHANNEL -> "频道"
}

@Composable
private fun MainPage.IconContent() {
    Icon(
        imageVector = when (this) {
            MainPage.DASHBOARD -> Icons.Default.Home
            MainPage.RSS -> Icons.Default.RssFeed
            MainPage.IMPORTS -> Icons.Default.FileOpen
            MainPage.CHANNEL -> Icons.Default.RssFeed
        },
        contentDescription = null
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
                icon = { destination.IconContent() },
                label = {
                    Text(text = destination.label())
                },
                modifier = Modifier.testTag("nav_${destination.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun MainNavigationRail(
    selectedPage: MainPage,
    contentPadding: PaddingValues,
    onSelectPage: (MainPage) -> Unit
) {
    val topPadding = contentPadding.calculateTopPadding()
    NavigationRail(
        modifier = Modifier
            .zIndex(2f)
            .width(MainNavigationRailWidth)
            .fillMaxHeight()
            .then(if (topPadding == 0.dp) Modifier.statusBarsPadding() else Modifier)
            .padding(
                top = topPadding,
                bottom = contentPadding.calculateBottomPadding()
            ),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        TopLevelMainPages.forEach { destination ->
            NavigationRailItem(
                selected = selectedPage == destination,
                onClick = { onSelectPage(destination) },
                icon = { destination.IconContent() },
                label = { Text(destination.label()) },
                modifier = Modifier.testTag("nav_${destination.name.lowercase()}")
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
                    text = { Text("同步手表") },
                    modifier = Modifier
                        .testTag("fab_sync_watch")
                        .tipAnchor(TipIds.SYNC_MANUAL)
                )
            }
        }
        MainPage.RSS -> ExtendedFloatingActionButton(
            onClick = onAddRssSource,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("添加 RSS") },
            modifier = Modifier
                .testTag("fab_add_rss")
                .tipAnchor(TipIds.RSS_FAB)
        )
        MainPage.CHANNEL -> {
            if (selectedSource != null && canRefreshSelectedSource && !selectedSourceRefreshing && !isBusy) {
                ExtendedFloatingActionButton(
                    onClick = onRefreshSelectedSource,
                    icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                    text = { Text("刷新") },
                    modifier = Modifier.testTag("fab_refresh_channel")
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
    windowInfo: AdaptiveWindowInfo,
    onSyncLibrary: () -> Unit,
    onSyncAccount: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenNotes: () -> Unit,
    showAccountActions: Boolean,
    onExportBluetoothLog: () -> Unit,
    onOpenRss: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenIndependent: () -> Unit,
    onOpenImportedContent: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val useDashboardTopBar = windowInfo.navigationType == AdaptiveNavigationType.Rail
    Column(modifier = modifier.fillMaxSize()) {
        if (useDashboardTopBar) {
            MainTopBar(
                page = MainPage.DASHBOARD,
                selectedChannel = null,
                canRefreshRss = false,
                canRefreshSource = false,
                onBack = {},
                onRefreshAllRssSources = {},
                onRefreshSelectedSource = {},
                onOpenChannelSettings = {},
                onExportBluetoothLog = onExportBluetoothLog,
                onOpenProfile = onOpenProfile,
                modifier = Modifier.fillMaxWidth()
            )
        }
        AdaptiveContentFrame(
            windowInfo = windowInfo,
            modifier = Modifier.weight(1f),
            mediumMaxWidth = 720.dp,
            expandedMaxWidth = 1120.dp
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = mainContentPadding(
                    scaffoldPadding = contentPadding,
                    windowInfo = windowInfo,
                    includeScaffoldTop = !useDashboardTopBar
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (windowInfo.isExpanded) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                SyncStatusCard(
                                    transportLabel = uiState.syncTransportLabel,
                                    message = uiState.syncStatusMessage,
                                    error = uiState.syncStatusError,
                                    syncProgress = uiState.syncProgress,
                                    isBusy = uiState.isBusy,
                                    onSyncLibrary = onSyncLibrary,
                                    onSyncAccount = onSyncAccount,
                                    showAccountAction = showAccountActions,
                                    onExportBluetoothLog = onExportBluetoothLog,
                                    onDismissMessage = onDismissMessage
                                )
                            }
                            Box(modifier = Modifier.weight(1f)) {
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
                                    onOpenImportedContent = onOpenImportedContent,
                                    onOpenNotes = onOpenNotes
                                )
                            }
                        }
                    }
                } else {
                    item {
                        SyncStatusCard(
                            transportLabel = uiState.syncTransportLabel,
                            message = uiState.syncStatusMessage,
                            error = uiState.syncStatusError,
                            syncProgress = uiState.syncProgress,
                            isBusy = uiState.isBusy,
                            onSyncLibrary = onSyncLibrary,
                            onSyncAccount = onSyncAccount,
                            showAccountAction = showAccountActions,
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
                            onOpenImportedContent = onOpenImportedContent,
                            onOpenNotes = onOpenNotes
                        )
                    }
                    item {
                        TokenUsageCard(
                            stats = uiState.llmTokenUsageStats,
                            daily = uiState.llmTokenUsageDaily,
                            modifier = Modifier.tipAnchor(TipIds.TOKEN_USAGE)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenUsageCard(
    stats: PhoneLlmTokenUsageStatisticsPojo?,
    daily: List<PhoneLlmTokenUsageDailyPojo>,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "手表词元消耗概览",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "与手表同步后自动更新",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                }
            }
            Text(
                text = "累计 ${stats?.totalCalls ?: 0} 次 · ${stats?.totalTokens ?: 0} 词元",
                style = MaterialTheme.typography.bodyMedium
            )
            if (daily.size > 1) {
                val primary = MaterialTheme.colorScheme.primary
                val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                ) {
                    val maxVal = daily.maxOf { it.totalTokens ?: 0L }.toFloat().coerceAtLeast(1f)
                    val paddingX = 8.dp.toPx()
                    val width = size.width - 2 * paddingX
                    val height = size.height - 16.dp.toPx()
                    val step = width / (daily.size - 1).coerceAtLeast(1)
                    val points = daily.mapIndexed { index, day ->
                        val x = paddingX + index * step
                        val y = height - ((day.totalTokens ?: 0L).toFloat() / maxVal) * (height * 0.85f)
                        Offset(x, y)
                    }
                    for (i in 0 until points.lastIndex) {
                        drawLine(
                            color = primary,
                            start = points[i],
                            end = points[i + 1],
                            strokeWidth = 3.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                    points.forEach { p ->
                        drawCircle(color = primary, radius = 3.dp.toPx(), center = p)
                    }
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            color = onSurfaceVariant.toArgb()
                            textSize = 10.dp.toPx()
                        }
                        val fmt = java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault())
                        canvas.nativeCanvas.drawText(
                            fmt.format(java.util.Date(daily.first().dayTimestamp)),
                            paddingX,
                            size.height - 4.dp.toPx(),
                            paint
                        )
                        canvas.nativeCanvas.drawText(
                            fmt.format(java.util.Date(daily.last().dayTimestamp)),
                            size.width - paddingX - 40.dp.toPx(),
                            size.height - 4.dp.toPx(),
                            paint
                        )
                    }
                }
            } else {
                Text(
                    text = "暂无近7天数据，请先与手表同步资料库。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SyncStatusCard(
    transportLabel: String,
    message: String?,
    error: String?,
    syncProgress: MainSyncProgressUi?,
    isBusy: Boolean,
    onSyncLibrary: () -> Unit,
    onSyncAccount: () -> Unit,
    showAccountAction: Boolean,
    onExportBluetoothLog: () -> Unit,
    onDismissMessage: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .testTag("dashboard_sync_card")
            .tipAnchor(TipIds.SYNC_STATUS_CARD),
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
                        text = "手表同步",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "已配对手表 · $transportLabel",
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
            message?.takeIf { syncProgress == null && it.isNotBlank() }?.let {
                Text(
                    text = it,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .roundedClickable(
                            shape = RoundedCornerShape(8.dp),
                            onClick = onDismissMessage
                        )
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
                        .roundedClickable(
                            shape = RoundedCornerShape(8.dp),
                            onClick = onDismissMessage
                        )
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSyncLibrary,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_sync_watch")
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("同步")
                }
                if (showAccountAction) {
                    OutlinedButton(
                        onClick = onSyncAccount,
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dashboard_sync_account")
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("账号")
                    }
                }
                OutlinedButton(
                    onClick = onExportBluetoothLog,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_export_log")
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
    onOpenImportedContent: () -> Unit,
    onOpenNotes: () -> Unit
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
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tile_content")
                )
                SummaryTile(
                    title = "收藏",
                    value = "$favoriteCount 篇",
                    supporting = "已保存",
                    icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                    onClick = onOpenFavorites,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tile_favorites")
                        .tipAnchor(TipIds.FAVORITES_VS_WATCH_LATER)
                )
            }
            SummaryRow {
                SummaryTile(
                    title = "稍后",
                    value = "$watchLaterCount 篇",
                    supporting = "待阅读",
                    icon = { Icon(Icons.Default.Bookmark, contentDescription = null) },
                    onClick = onOpenWatchLater,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tile_watch_later")
                )
                SummaryTile(
                    title = "独立文章",
                    value = "$independentCount 篇",
                    supporting = "网页导入",
                    icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                    onClick = onOpenIndependent,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tile_independent")
                )
            }
            SummaryRow {
                SummaryTile(
                    title = "导入内容",
                    value = "$importedContentCount 篇",
                    supporting = "TXT",
                    icon = { Icon(Icons.Default.Description, contentDescription = null) },
                    onClick = onOpenImportedContent,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tile_imported")
                )
                SummaryTile(
                    title = "备忘录",
                    value = "Markdown",
                    supporting = "与手表同步",
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
                    onClick = onOpenNotes,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tile_notes")
                )
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
        dragGroup = MainContentDragGroup.NORMAL,
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
            dragGroup = if (source.isPinned) MainContentDragGroup.PINNED else MainContentDragGroup.NORMAL,
            sortOrder = source.sortOrder
        )
    }
    val hasImportedTextSource = sourceChannels.any { channel ->
        channel.source?.url?.let(ImportedContentIds::isImportedTextSourceUrl) == true
    }
    val importedTextFallback = if (!hasImportedTextSource) {
        val importedTextSortOrder = uiState.importedContentArticles.maxOfOrNull { article ->
            maxOf(article.updatedAt, article.importedAt)
        } ?: 0L
        listOf(
            MainContentChannel(
                key = CONTENT_CHANNEL_IMPORTED_TEXT,
                title = ImportedContentIds.ROOT_SOURCE_TITLE,
                supportingText = "TXT 导入",
                articleCount = uiState.importedContentArticles.size,
                reorderSourceUrl = ImportedContentIds.ROOT_SOURCE_URL,
                articles = uiState.importedContentArticles,
                icon = MainContentChannelIcon.IMPORTED,
                emptyTitle = "暂无导入内容",
                emptyText = "TXT 文件导入后会显示在这里",
                canDrag = uiState.importedContentArticles.isNotEmpty(),
                dragGroup = MainContentDragGroup.NORMAL,
                sortOrder = importedTextSortOrder
            )
        )
    } else {
        emptyList()
    }
    val reorderableChannels = listOf(independentChannel) + importedTextFallback + sourceChannels
    val sortedPinnedChannels = reorderableChannels
        .filter { it.dragGroup == MainContentDragGroup.PINNED }
        .sortedWith(
            compareByDescending<MainContentChannel> { it.sortOrder }
                .thenBy { it.title }
        )
    val sortedNormalChannels = reorderableChannels
        .filter { it.dragGroup == MainContentDragGroup.NORMAL }
        .sortedWith(
            compareByDescending<MainContentChannel> { it.sortOrder }
                .thenBy { it.title }
        )
    return fixedChannels + sortedPinnedChannels + sortedNormalChannels
}

private fun sourceContentChannelKey(sourceUrl: String): String = "$CONTENT_SOURCE_PREFIX$sourceUrl"

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

private fun reorderSourceChannelsForDisplay(
    channels: List<MainContentChannel>,
    dragGroup: MainContentDragGroup
): MainContentReorderRequest? {
    val reorderableChannels = channels.filter { it.canDrag && it.dragGroup == dragGroup }
    if (reorderableChannels.size < 2) return null
    val sourceUrls = reorderableChannels.mapNotNull { it.reorderSourceUrl }
    val independentIndex = if (dragGroup == MainContentDragGroup.NORMAL) {
        reorderableChannels.indexOfFirst { it.key == CONTENT_CHANNEL_INDEPENDENT }
            .takeIf { it >= 0 && reorderableChannels[it].articleCount > 0 }
    } else {
        null
    }
    if (sourceUrls.size < 2 && independentIndex == null) return null
    return MainContentReorderRequest(
        sourceUrlsInDisplayOrder = sourceUrls,
        independentIndex = independentIndex
    )
}

private fun moveContentChannelForReorder(
    channels: List<MainContentChannel>,
    fromIndex: Int,
    toIndex: Int
): Pair<List<MainContentChannel>, MainContentDragGroup>? {
    if (fromIndex == toIndex) return null
    val draggedChannel = channels.getOrNull(fromIndex) ?: return null
    val targetChannel = channels.getOrNull(toIndex) ?: return null
    if (!draggedChannel.canDrag || !targetChannel.canDrag) return null
    if (draggedChannel.dragGroup != targetChannel.dragGroup) return null
    val reorderedChannels = channels.toMutableList()
    val moved = reorderedChannels.removeAt(fromIndex)
    reorderedChannels.add(toIndex, moved)
    return reorderedChannels to draggedChannel.dragGroup
}

private fun List<MainContentChannel>.contentChannelKeys(): List<String> = map { it.key }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ContentPage(
    uiState: MainUiState,
    channels: List<MainContentChannel>,
    contentPadding: PaddingValues,
    windowInfo: AdaptiveWindowInfo,
    listState: LazyListState,
    selectedChannelKey: String? = null,
    onOpenChannel: (MainContentChannel) -> Unit,
    onMoveToTop: (PhoneRssSourceEntity) -> Unit,
    onReorderContentChannels: (List<String>, Int?) -> Unit,
    onTogglePinned: (PhoneRssSourceEntity) -> Unit,
    onDelete: (PhoneRssSourceEntity) -> Unit,
    onRefreshAllRssSources: () -> Unit
) {
    val pullState = rememberPullToRefreshState()
    var displayChannels by remember { mutableStateOf(channels) }
    var movedDragGroup by remember { mutableStateOf<MainContentDragGroup?>(null) }
    var dragMoved by remember { mutableStateOf(false) }
    var pendingReorderKeys by remember { mutableStateOf<List<String>?>(null) }
    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        moveContentChannelForReorder(
            channels = displayChannels,
            fromIndex = from.index,
            toIndex = to.index
        )?.let { (reorderedChannels, dragGroup) ->
            displayChannels = reorderedChannels
            movedDragGroup = dragGroup
            dragMoved = true
        }
    }

    LaunchedEffect(channels) {
        val incomingKeys = channels.contentChannelKeys()
        val pendingKeys = pendingReorderKeys
        if (!dragMoved && (pendingKeys == null || pendingKeys == incomingKeys)) {
            displayChannels = channels
            pendingReorderKeys = null
        }
    }

    fun finishDrag() {
        val reorderRequest = if (dragMoved) {
            movedDragGroup?.let { group ->
                reorderSourceChannelsForDisplay(displayChannels, group)
            }
        } else null
        dragMoved = false
        movedDragGroup = null
        if (reorderRequest != null) {
            pendingReorderKeys = displayChannels.contentChannelKeys()
            onReorderContentChannels(
                reorderRequest.sourceUrlsInDisplayOrder,
                reorderRequest.independentIndex
            )
        }
    }

    AdaptiveContentFrame(
        windowInfo = windowInfo,
        expandedMaxWidth = 760.dp
    ) {
        val usePaneTopBar = windowInfo.isMediumOrExpanded
        Column(modifier = Modifier.fillMaxSize()) {
            if (usePaneTopBar) {
                SplitPaneTopBar(
                    title = "内容",
                    action = {
                        IconButton(
                            onClick = onRefreshAllRssSources,
                            enabled = uiState.rssSources.any { !ImportedContentIds.isImportedContentUrl(it.url) } &&
                                !uiState.isBusy,
                            modifier = Modifier.testTag("topbar_refresh_all")
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = "刷新内容")
                        }
                    }
                )
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
                            .padding(
                                top = if (usePaneTopBar) {
                                    12.dp
                                } else {
                                    contentPadding.calculateTopPadding() + 12.dp
                                }
                            )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = mainContentPadding(
                        scaffoldPadding = contentPadding,
                        windowInfo = windowInfo,
                        includeScaffoldTop = !usePaneTopBar
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                if (displayChannels.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
                            title = "暂无内容",
                            text = "可在导入页添加 RSS、网页文章或本地文件",
                            modifier = Modifier.testTag("content_empty")
                        )
                    }
                } else {
                    items(displayChannels, key = { it.key }) { channel ->
                        val source = channel.source
                        val isReorderTargetEnabled = channel.canDrag &&
                            (movedDragGroup == null || channel.dragGroup == movedDragGroup)
                        ReorderableItem(
                            reorderableLazyListState,
                            key = channel.key,
                            enabled = isReorderTargetEnabled
                        ) { isDragging ->
                            val onDragStarted = {
                                movedDragGroup = channel.dragGroup
                                dragMoved = false
                            }
                            val itemModifier = Modifier
                                .animateItem()
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                }
                                .then(
                                    if (channel.canDrag) {
                                        Modifier.longPressDraggableHandle(
                                            onDragStarted = { _ -> onDragStarted() },
                                            onDragStopped = ::finishDrag
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                            if (source == null) {
                                MainContentChannelRow(
                                    channel = channel,
                                    onClick = { onOpenChannel(channel) },
                                    selected = channel.key == selectedChannelKey,
                                    modifier = itemModifier
                                )
                            } else {
                                MainScreenSourceRow(
                                    source = source,
                                    icon = channel.icon,
                                    articleCount = channel.articleCount,
                                    onClick = { onOpenChannel(channel) },
                                    selected = channel.key == selectedChannelKey,
                                    onMoveToTop = { onMoveToTop(source) },
                                    onTogglePinned = { onTogglePinned(source) },
                                    onDelete = { onDelete(source) },
                                    modifier = itemModifier
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun ActiveReaderReturnTwoPane(
    progress: Float,
    windowInfo: AdaptiveWindowInfo,
    startPane: @Composable () -> Unit,
    movingPane: @Composable (Float) -> Unit,
    readerPane: @Composable (Boolean) -> Unit
) {
    val returnProgress = progress.coerceIn(0f, 1f)
    AdaptiveReaderReturnThreePane(
        windowInfo = windowInfo,
        progress = returnProgress,
        startPane = startPane,
        movingPane = {
            movingPane(returnProgress)
        },
        readerPane = {
            readerPane(false)
        }
    )
}

@Composable
private fun ActiveReaderOpenThreePane(
    progress: Float,
    windowInfo: AdaptiveWindowInfo,
    startPane: @Composable () -> Unit,
    movingPane: @Composable (Float) -> Unit,
    readerPane: @Composable (Boolean) -> Unit
) {
    val openProgress = progress.coerceIn(0f, 1f)
    AdaptiveReaderOpenThreePane(
        windowInfo = windowInfo,
        progress = openProgress,
        startPane = startPane,
        movingPane = {
            movingPane(1f - openProgress)
        },
        readerPane = {
            readerPane(false)
        }
    )
}

@Composable
private fun ReaderReturnTwoPane(
    transitionState: ReaderLeftPaneReturnState?,
    windowInfo: AdaptiveWindowInfo,
    onTransitionFinished: (ReaderLeftPaneReturnState) -> Unit,
    startPane: @Composable () -> Unit,
    endPane: @Composable () -> Unit,
    movingPane: @Composable (Float) -> Unit
) {
    val initialMotionProgress = transitionState
        ?.initialProgress
        ?.coerceIn(0f, 1f)
        ?: 1f
    val motionProgress = remember(transitionState) {
        Animatable(initialMotionProgress)
    }

    LaunchedEffect(transitionState) {
        if (transitionState == null) {
            motionProgress.snapTo(1f)
        } else {
            val initialProgress = transitionState.initialProgress.coerceIn(0f, 1f)
            val remainingDuration = (READER_LEFT_PANE_RETURN_TRANSITION_MS * (1f - initialProgress))
                .toInt()
                .coerceAtLeast(1)
            motionProgress.snapTo(initialProgress)
            motionProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = remainingDuration,
                    easing = FastOutSlowInEasing
                )
            )
            onTransitionFinished(transitionState)
        }
    }

    AdaptiveMovingTwoPane(
        windowInfo = windowInfo,
        transitionProgress = transitionState?.let { motionProgress.value },
        startPane = startPane,
        endPane = endPane,
        movingPane = {
            movingPane(motionProgress.value)
        }
    )
}

@Composable
private fun ReaderReturnMovingArticlePane(
    progress: Float,
    channel: MainContentChannel?,
    selectedArticleId: String?,
    isRefreshing: Boolean,
    contentPadding: PaddingValues,
    windowInfo: AdaptiveWindowInfo,
    listState: LazyListState,
    onRefreshSource: () -> Unit,
    onOpenChannelSettings: (PhoneRssSourceEntity) -> Unit,
    onBackToChannels: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit
) {
    if (channel == null) {
        ChannelArticlePane(
            channel = null,
            isRefreshing = isRefreshing,
            contentPadding = contentPadding,
            windowInfo = windowInfo,
            listState = listState,
            onRefreshSource = onRefreshSource,
            onOpenChannelSettings = onOpenChannelSettings,
            onOpenArticle = onOpenArticle,
            onOpenOriginalLink = onOpenOriginalLink,
            onToggleFavorite = onToggleFavorite,
            onToggleWatchLater = onToggleWatchLater,
            onDeleteArticle = onDeleteArticle
        )
        return
    }
    MorphingChannelArticlePane(
        progress = progress,
        channel = channel,
        selectedArticleId = selectedArticleId,
        isRefreshing = isRefreshing,
        contentPadding = contentPadding,
        windowInfo = windowInfo,
        listState = listState,
        onRefreshSource = onRefreshSource,
        onOpenChannelSettings = onOpenChannelSettings,
        onBackToChannels = onBackToChannels,
        onOpenArticle = onOpenArticle,
        onOpenOriginalLink = onOpenOriginalLink,
        onToggleFavorite = onToggleFavorite,
        onToggleWatchLater = onToggleWatchLater,
        onDeleteArticle = onDeleteArticle
    )
}

@Composable
private fun MorphingChannelArticlePane(
    progress: Float,
    channel: MainContentChannel,
    selectedArticleId: String?,
    isRefreshing: Boolean,
    contentPadding: PaddingValues,
    windowInfo: AdaptiveWindowInfo,
    listState: LazyListState,
    onRefreshSource: () -> Unit,
    onOpenChannelSettings: (PhoneRssSourceEntity) -> Unit,
    onBackToChannels: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit
) {
    val paneProgress = progress.coerceIn(0f, 1f)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                color = lerpMainColor(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.background,
                    paneProgress
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(lerpMainDp(64.dp, 0.dp, paneProgress))
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "内容",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            translationY = -28.dp.toPx() * paneProgress
                            scaleX = 1f - 0.08f * paneProgress
                            scaleY = 1f - 0.08f * paneProgress
                        }
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = PaddingValues(
                    start = readerSplitListHorizontalPadding(windowInfo),
                    top = 12.dp,
                    end = readerSplitListHorizontalPadding(windowInfo),
                    bottom = contentPadding.calculateBottomPadding() + 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(lerpMainDp(10.dp, 12.dp, paneProgress))
            ) {
                item {
                    MorphingChannelArticleHeader(
                        progress = paneProgress,
                        channel = channel,
                        isRefreshing = isRefreshing,
                        onBackToChannels = onBackToChannels,
                        onRefreshSource = onRefreshSource,
                        onOpenChannelSettings = onOpenChannelSettings
                    )
                }
                if (channel.articles.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = { MainContentChannelLeadingIcon(channel.icon) },
                            title = channel.emptyTitle,
                            text = channel.emptyText,
                            modifier = Modifier.testTag("channel_empty")
                        )
                    }
                } else {
                    items(channel.articles, key = { it.articleId }) { article ->
                        MorphingMainScreenArticleRow(
                            progress = paneProgress,
                            article = article,
                            selected = article.articleId == selectedArticleId,
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

@Composable
private fun MorphingChannelArticleHeader(
    progress: Float,
    channel: MainContentChannel,
    isRefreshing: Boolean,
    onBackToChannels: () -> Unit,
    onRefreshSource: () -> Unit,
    onOpenChannelSettings: (PhoneRssSourceEntity) -> Unit
) {
    val paneProgress = progress.coerceIn(0f, 1f)
    val refreshActionWidth = if (channel.canRefresh) {
        lerpMainDp(0.dp, 48.dp, paneProgress)
    } else {
        0.dp
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(lerpMainDp(12.dp, 16.dp, paneProgress)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(lerpMainDp(8.dp, 12.dp, paneProgress))
        ) {
            Box(
                modifier = Modifier
                    .size(lerpMainDp(48.dp, 44.dp, paneProgress))
                    .clipToBounds()
                    .roundedClickable(
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            if (paneProgress < 0.5f) {
                                onBackToChannels()
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(lerpMainDp(48.dp, 0.dp, paneProgress)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回频道列表")
                }
                Surface(
                    modifier = Modifier.size(lerpMainDp(0.dp, 44.dp, paneProgress)),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        MainContentChannelLeadingIcon(channel.icon)
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(lerpMainDp(2.dp, 0.dp, paneProgress)))
                MorphingVerticalSlot(
                    progress = 1f - paneProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = "${channel.articleCount} 篇文章",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(lerpMainDp(0.dp, 3.dp, paneProgress)))
                MorphingVerticalSlot(
                    progress = paneProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = channel.supportingText.takeIf { it.isNotBlank() } ?: "${channel.articleCount} 篇文章",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(lerpMainDp(0.dp, 3.dp, paneProgress)))
                MorphingVerticalSlot(
                    progress = paneProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(
                        text = buildString {
                            append("${channel.articleCount} 篇文章")
                            append(" · ")
                            append(
                                when {
                                    channel.source == null -> "本地集合"
                                    channel.canRefresh -> "RSS 源"
                                    else -> "导入内容"
                                }
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            channel.source?.let { source ->
                IconButton(onClick = { onOpenChannelSettings(source) }) {
                    Icon(Icons.Outlined.Info, contentDescription = "频道设置")
                }
            }
            Box(
                modifier = Modifier
                    .width(refreshActionWidth)
                    .height(48.dp)
                    .clipToBounds(),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (channel.canRefresh && refreshActionWidth > 0.dp) {
                    IconButton(
                        onClick = onRefreshSource,
                        enabled = !isRefreshing
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "刷新频道")
                    }
                }
            }
        }
    }
}

@Composable
private fun MorphingMainScreenArticleRow(
    progress: Float,
    article: PhoneArticleEntity,
    selected: Boolean,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    mainScreenCanDeleteArticle: Boolean
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val paneProgress = progress.coerceIn(0f, 1f)
    val cardShape = MaterialTheme.shapes.medium
    val defaultCardColors = defaultMainElevatedCardColors()
    val cardColors = if (selected) {
        CardDefaults.elevatedCardColors(
            containerColor = lerpMainColor(
                MaterialTheme.colorScheme.secondaryContainer,
                defaultCardColors.containerColor,
                paneProgress
            )
        )
    } else {
        defaultCardColors
    }
    val titleStyle = MaterialTheme.typography.titleSmall.copy(
        fontSize = lerpMainSp(14f, 16f, paneProgress),
        lineHeight = lerpMainSp(20f, 24f, paneProgress)
    )
    val summaryStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = lerpMainSp(12f, 14f, paneProgress),
        lineHeight = lerpMainSp(16f, 20f, paneProgress)
    )
    val menuActionWidth = if (mainScreenCanDeleteArticle) {
        lerpMainDp(0.dp, 48.dp, paneProgress)
    } else {
        0.dp
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("article_row"),
        shape = cardShape,
        colors = cardColors
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .roundedCombinedClickable(
                    shape = cardShape,
                    onClick = { onOpenArticle(article) },
                    onLongClick = {
                        if (mainScreenCanDeleteArticle) menuExpanded = true
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(lerpMainDp(14.dp, 16.dp, paneProgress)),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = article.title.ifBlank { article.url },
                        style = titleStyle,
                        fontWeight = FontWeight.Bold,
                        minLines = ARTICLE_CARD_TITLE_LINES,
                        maxLines = ARTICLE_CARD_TITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(menuActionWidth)
                            .height(menuActionWidth)
                            .clipToBounds(),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        if (mainScreenCanDeleteArticle && menuActionWidth > 0.dp) {
                            Box {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier.testTag("article_more")
                                ) {
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
                        style = summaryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(lerpMainDp(8.dp, 4.dp, paneProgress)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MorphingArticleActionIcon(
                        progress = paneProgress,
                        startVisible = article.favoriteSaved,
                        endVisible = true,
                        onClick = { onToggleFavorite(article) },
                        modifier = Modifier.testTag("article_favorite")
                    ) {
                        Icon(
                            imageVector = if (article.favoriteSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (article.favoriteSaved) "取消收藏" else "收藏",
                            modifier = Modifier.size(lerpMainDp(18.dp, 24.dp, paneProgress))
                        )
                    }
                    MorphingArticleActionIcon(
                        progress = paneProgress,
                        startVisible = article.watchLaterSaved,
                        endVisible = true,
                        onClick = { onToggleWatchLater(article) },
                        modifier = Modifier.testTag("article_watch_later")
                    ) {
                        Icon(
                            imageVector = if (article.watchLaterSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (article.watchLaterSaved) "移出稍后再看" else "稍后再看",
                            modifier = Modifier.size(lerpMainDp(18.dp, 24.dp, paneProgress))
                        )
                    }
                    MorphingArticleActionIcon(
                        progress = paneProgress,
                        startVisible = false,
                        endVisible = article.url.isNotBlank() && !ImportedContentIds.isImportedContentUrl(article.url),
                        onClick = { onOpenOriginalLink(article.url) },
                        modifier = Modifier.testTag("article_open_original")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "打开原网页",
                            modifier = Modifier.size(lerpMainDp(18.dp, 24.dp, paneProgress))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MorphingArticleActionIcon(
    progress: Float,
    startVisible: Boolean,
    endVisible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    val paneProgress = progress.coerceIn(0f, 1f)
    val startSize = if (startVisible) 18.dp else 0.dp
    val endSize = if (endVisible) 48.dp else 0.dp
    val boxSize = lerpMainDp(startSize, endSize, paneProgress)
    if (boxSize <= 0.dp) return
    Box(
        modifier = modifier
            .size(boxSize)
            .clipToBounds()
            .roundedClickable(
                shape = RoundedCornerShape(12.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelArticleListPane(
    channel: MainContentChannel,
    selectedArticleId: String,
    contentPadding: PaddingValues,
    windowInfo: AdaptiveWindowInfo,
    listState: LazyListState,
    onBackToChannels: () -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "内容",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = readerSplitListHorizontalPadding(windowInfo),
                    top = 12.dp,
                    end = readerSplitListHorizontalPadding(windowInfo),
                    bottom = contentPadding.calculateBottomPadding() + 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(onClick = onBackToChannels) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回频道列表")
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = channel.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    minLines = 2,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${channel.articleCount} 篇文章",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (channel.articles.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = { MainContentChannelLeadingIcon(channel.icon) },
                            title = channel.emptyTitle,
                            text = channel.emptyText,
                            modifier = Modifier.testTag("channel_empty")
                        )
                    }
                } else {
                    items(channel.articles, key = { it.articleId }) { article ->
                        MainScreenArticleListRow(
                            article = article,
                            selected = article.articleId == selectedArticleId,
                            onClick = { onOpenArticle(article) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreenArticleListRow(
    article: PhoneArticleEntity,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cardColors = if (selected) {
        CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    } else {
        defaultMainElevatedCardColors()
    }
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("article_row"),
        colors = cardColors,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = article.title.ifBlank { article.url },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                minLines = ARTICLE_CARD_TITLE_LINES,
                maxLines = ARTICLE_CARD_TITLE_LINES,
                overflow = TextOverflow.Ellipsis
            )
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (article.favoriteSaved || article.watchLaterSaved) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (article.favoriteSaved) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "已收藏",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (article.watchLaterSaved) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = "稍后再看",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelArticlePane(
    channel: MainContentChannel?,
    isRefreshing: Boolean,
    contentPadding: PaddingValues,
    windowInfo: AdaptiveWindowInfo,
    listState: LazyListState,
    onRefreshSource: () -> Unit,
    onOpenChannelSettings: (PhoneRssSourceEntity) -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                if (channel?.canRefresh == true) {
                    onRefreshSource()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .then(if (windowInfo.isMediumOrExpanded) Modifier.statusBarsPadding() else Modifier)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = articlePaneContentPadding(
                    scaffoldPadding = contentPadding,
                    windowInfo = windowInfo,
                    includeScaffoldTop = !windowInfo.isMediumOrExpanded
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    if (channel == null) {
                        ChannelArticlePaneHeader(
                            channel = null,
                            isRefreshing = isRefreshing,
                            onRefreshSource = onRefreshSource
                        )
                    } else {
                        MorphingChannelArticleHeader(
                            progress = 1f,
                            channel = channel,
                            isRefreshing = isRefreshing,
                            onBackToChannels = {},
                            onRefreshSource = onRefreshSource,
                            onOpenChannelSettings = onOpenChannelSettings
                        )
                    }
                }
                when {
                    channel == null -> item {
                        EmptyStateCard(
                            icon = { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null) },
                            title = "选择左侧内容",
                            text = "频道、收藏、稍后再看或导入内容会在这里显示文章",
                            modifier = Modifier.testTag("reader_select_channel")
                        )
                    }
                    channel.articles.isEmpty() -> item {
                        EmptyStateCard(
                            icon = { MainContentChannelLeadingIcon(channel.icon) },
                            title = channel.emptyTitle,
                            text = channel.emptyText,
                            modifier = Modifier.testTag("channel_empty")
                        )
                    }
                    else -> items(channel.articles, key = { it.articleId }) { article ->
                        MorphingMainScreenArticleRow(
                            progress = 1f,
                            article = article,
                            selected = false,
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

@Composable
private fun ChannelArticlePaneHeader(
    channel: MainContentChannel?,
    isRefreshing: Boolean,
    onRefreshSource: () -> Unit
) {
    val title = channel?.title?.takeIf { it.isNotBlank() } ?: "文章区"
    val supportingText = channel?.supportingText?.takeIf { it.isNotBlank() } ?: "从左侧选择频道或内容集合"
    val detailText = channel?.let { selectedChannel ->
        buildString {
            append("${selectedChannel.articleCount} 篇文章")
            append(" · ")
            append(
                when {
                    selectedChannel.source == null -> "本地集合"
                    selectedChannel.canRefresh -> "RSS 源"
                    else -> "导入内容"
                }
            )
        }
    } ?: "等待选择"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MainContentChannelLeadingIcon(channel?.icon ?: MainContentChannelIcon.ARTICLE)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (channel?.canRefresh == true) {
                IconButton(
                    onClick = onRefreshSource,
                    enabled = !isRefreshing
                ) {
                    Icon(Icons.Default.Sync, contentDescription = "刷新频道")
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
    windowInfo: AdaptiveWindowInfo,
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
        AdaptiveContentFrame(
            windowInfo = windowInfo,
            expandedMaxWidth = 760.dp
        ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefreshSource,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = mainContentPadding(contentPadding, windowInfo),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (channel == null || channel.articles.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = { MainContentChannelLeadingIcon(channel?.icon ?: MainContentChannelIcon.ARTICLE) },
                                title = channel?.emptyTitle ?: "暂无文章",
                                text = channel?.emptyText ?: "频道不存在",
                                modifier = Modifier.testTag("channel_empty")
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
    windowInfo: AdaptiveWindowInfo,
    listState: LazyListState,
    selectedChannelKey: String? = null,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onExportBackup: () -> Unit,
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
    AdaptiveContentFrame(
        windowInfo = windowInfo,
        expandedMaxWidth = 760.dp
    ) {
        val usePaneTopBar = windowInfo.isMediumOrExpanded
        Column(modifier = Modifier.fillMaxSize()) {
            if (usePaneTopBar) {
                SplitPaneTopBar(title = "导入")
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                contentPadding = mainContentPadding(
                    scaffoldPadding = contentPadding,
                    windowInfo = windowInfo,
                    includeScaffoldTop = !usePaneTopBar
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            item {
                ImportActionsCard(
                    urlInput = uiState.urlInput,
                    enabled = !uiState.isBusy,
                    onUrlChange = onUrlChange,
                    onImportArticle = onImportArticle,
                    onImportFile = onImportFile,
                    onAddRssSource = onAddRssSource,
                    onExportBackup = onExportBackup,
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
                        text = "添加网页文章，或导入 TXT / EPUB / WRSS 文件后会显示在这里",
                        modifier = Modifier.testTag("imports_empty")
                    )
                }
            } else {
                items(recentEntries, key = { it.key }) { entry ->
                    Box(modifier = Modifier.testTag("recent_import_item")) {
                        when (entry) {
                            is RecentImportEntry.Channel -> MainContentChannelRow(
                                channel = entry.channel,
                                onClick = { onOpenChannel(entry.channel) },
                                selected = entry.channel.key == selectedChannelKey
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
    }
}
}

@Composable
private fun ImportActionsCard(
    urlInput: String,
    enabled: Boolean,
    onUrlChange: (String) -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onAddRssSource: () -> Unit,
    onExportBackup: () -> Unit,
    message: String?,
    error: String?,
    onDismissMessage: () -> Unit
) {
    var pendingOnlineNovelImport by remember(urlInput) {
        mutableStateOf<UrlDialogMode?>(null)
    }
    val requestUrlImport: (UrlDialogMode) -> Unit = { mode ->
        if (OnlineNovelLinkDetector.findOnlineNovelUrl(urlInput) != null) {
            pendingOnlineNovelImport = mode
        } else {
            when (mode) {
                UrlDialogMode.ARTICLE -> onImportArticle()
                UrlDialogMode.RSS -> onAddRssSource()
            }
        }
    }

    ElevatedCard(
        modifier = Modifier.tipAnchor(TipIds.IMPORTS_THREE_WAYS),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("imports_url_input"),
                singleLine = true,
                enabled = enabled,
                trailingIcon = {
                    if (urlInput.isNotEmpty()) {
                        IconButton(
                            onClick = { onUrlChange("") },
                            enabled = enabled,
                            modifier = Modifier.testTag("imports_clear_url")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "清空输入")
                        }
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { requestUrlImport(UrlDialogMode.RSS) },
                    enabled = enabled && urlInput.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("imports_rss")
                ) {
                    Icon(Icons.Default.RssFeed, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("RSS")
                }
                Button(
                    onClick = { requestUrlImport(UrlDialogMode.ARTICLE) },
                    enabled = enabled && urlInput.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("imports_article")
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
                    modifier = Modifier
                        .weight(1f)
                        .testTag("imports_file")
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("文件")
                }
                OutlinedButton(
                    onClick = onExportBackup,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("imports_export_backup")
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出")
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
    pendingOnlineNovelImport?.let { mode ->
        OnlineNovelImportWarningDialog(
            onConfirm = {
                pendingOnlineNovelImport = null
                OnlineNovelLinkDetector.findOnlineNovelUrl(urlInput)
                    ?.takeIf { it != urlInput.trim() }
                    ?.let(onUrlChange)
                when (mode) {
                    UrlDialogMode.ARTICLE -> onImportArticle()
                    UrlDialogMode.RSS -> onAddRssSource()
                }
            },
            onDismiss = { pendingOnlineNovelImport = null }
        )
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
                    .roundedClickable(
                        shape = RoundedCornerShape(8.dp),
                        onClick = onDismissMessage
                    )
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
                    .roundedClickable(
                        shape = RoundedCornerShape(8.dp),
                        onClick = onDismissMessage
                    )
            )
        }
    }
}

@Composable
private fun MainContentChannelRow(
    channel: MainContentChannel,
    onClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val defaultCardColors = defaultMainElevatedCardColors()
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        channel.source?.isPinned == true -> pinnedMainContentChannelContainerColor()
        else -> defaultCardColors.containerColor
    }
    val cardColors = if (selected || channel.source?.isPinned == true) {
        CardDefaults.elevatedCardColors(
            containerColor = containerColor
        )
    } else {
        defaultCardColors
    }

    ElevatedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("channel_row"),
        colors = cardColors
    ) {
        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = containerColor
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
    selected: Boolean = false,
    onMoveToTop: () -> Unit,
    onTogglePinned: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val defaultCardColors = defaultMainElevatedCardColors()
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        source.isPinned -> pinnedMainContentChannelContainerColor()
        else -> defaultCardColors.containerColor
    }
    val cardColors = if (selected || source.isPinned) {
        CardDefaults.elevatedCardColors(
            containerColor = containerColor
        )
    } else {
        defaultCardColors
    }
    val cardShape = MaterialTheme.shapes.medium

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("channel_row"),
        shape = cardShape,
        colors = cardColors
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .roundedClickable(
                    shape = cardShape,
                    onClick = onClick
                )
        ) {
            ListItem(
                colors = ListItemDefaults.colors(
                    containerColor = containerColor
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.testTag("source_menu")
                        ) {
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
    val cardShape = MaterialTheme.shapes.medium
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("article_row"),
        shape = cardShape
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .roundedCombinedClickable(
                    shape = cardShape,
                    onClick = { onOpenArticle(article) },
                    onLongClick = {
                        if (mainScreenCanDeleteArticle) menuExpanded = true
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = article.title.ifBlank { article.url },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        minLines = ARTICLE_CARD_TITLE_LINES,
                        maxLines = ARTICLE_CARD_TITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (mainScreenCanDeleteArticle) {
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.testTag("article_more")
                            ) {
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
                    IconButton(
                        onClick = { onToggleFavorite(article) },
                        modifier = Modifier.testTag("article_favorite")
                    ) {
                        Icon(
                            imageVector = if (article.favoriteSaved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (article.favoriteSaved) "取消收藏" else "收藏"
                        )
                    }
                    IconButton(
                        onClick = { onToggleWatchLater(article) },
                        modifier = Modifier.testTag("article_watch_later")
                    ) {
                        Icon(
                            imageVector = if (article.watchLaterSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (article.watchLaterSaved) "移出稍后再看" else "稍后再看"
                        )
                    }
                    if (article.url.isNotBlank() && !ImportedContentIds.isImportedContentUrl(article.url)) {
                        IconButton(
                            onClick = { onOpenOriginalLink(article.url) },
                            modifier = Modifier.testTag("article_open_original")
                        ) {
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
    text: String,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier) {
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
    var showOnlineNovelWarning by remember(mode, urlInput) { mutableStateOf(false) }
    if (showOnlineNovelWarning) {
        OnlineNovelImportWarningDialog(
            onConfirm = {
                showOnlineNovelWarning = false
                OnlineNovelLinkDetector.findOnlineNovelUrl(urlInput)
                    ?.takeIf { it != urlInput.trim() }
                    ?.let(onUrlChange)
                onConfirm()
            },
            onDismiss = { showOnlineNovelWarning = false }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_url_entry"),
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
                onClick = {
                    if (OnlineNovelLinkDetector.findOnlineNovelUrl(urlInput) != null) {
                        showOnlineNovelWarning = true
                    } else {
                        onConfirm()
                    }
                },
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
private fun OnlineNovelImportWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var showContactQr by remember { mutableStateOf(false) }
    if (showContactQr) {
        QqGroupQrDialog(onDismiss = { showContactQr = false })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_online_novel_warning"),
        title = { Text("在线小说链接") },
        text = { Text(ONLINE_NOVEL_IMPORT_WARNING) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("取消")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onConfirm) {
                    Text("仍然导入")
                }
                TextButton(onClick = { showContactQr = true }) {
                    Text("联系我们")
                }
            }
        }
    )
}

@Composable
private fun QqGroupQrDialog(onDismiss: () -> Unit) {
    val qrBitmap = remember {
        generateQRCode(WATCH_RSS_QQ_GROUP_URL, 512).asImageBitmap()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_qq_group_qr"),
        title = { Text("联系我们") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("扫描二维码加入 QQ 群")
                Surface(
                    modifier = Modifier.size(220.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White
                ) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "QQ群二维码",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    )
                }
                Text(
                    text = "群号：$WATCH_RSS_QQ_GROUP_NUMBER",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("返回")
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
        modifier = Modifier.testTag("dialog_delete_conflict"),
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
        modifier = Modifier.testTag("dialog_bluetooth_device"),
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
    var pendingOnlineNovelImport by remember(prompt.kind, prompt.url) {
        mutableStateOf<UrlDialogMode?>(null)
    }
    pendingOnlineNovelImport?.let { mode ->
        OnlineNovelImportWarningDialog(
            onConfirm = {
                pendingOnlineNovelImport = null
                val url = OnlineNovelLinkDetector.findOnlineNovelUrl(prompt.url) ?: prompt.url
                when (mode) {
                    UrlDialogMode.ARTICLE -> onImportLinkAsArticle(url)
                    UrlDialogMode.RSS -> onImportLinkAsRss(url)
                }
            },
            onDismiss = { pendingOnlineNovelImport = null }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_shared_import"),
        title = {
            Text(
                text = when (prompt.kind) {
                    SharedImportPromptKind.LINK -> "导入链接"
                    SharedImportPromptKind.FILE -> "导入文件"
                    SharedImportPromptKind.MARKDOWN_FILE -> "导入 Markdown"
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
                SharedImportPromptKind.FILE,
                SharedImportPromptKind.MARKDOWN_FILE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            onClick = {
                                if (OnlineNovelLinkDetector.findOnlineNovelUrl(prompt.url) != null) {
                                    pendingOnlineNovelImport = UrlDialogMode.RSS
                                } else {
                                    onImportLinkAsRss(prompt.url)
                                }
                            }
                        ) {
                            Icon(Icons.Default.RssFeed, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("RSS 源")
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (OnlineNovelLinkDetector.findOnlineNovelUrl(prompt.url) != null) {
                                    pendingOnlineNovelImport = UrlDialogMode.ARTICLE
                                } else {
                                    onImportLinkAsArticle(prompt.url)
                                }
                            }
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
                    SharedImportPromptKind.MARKDOWN_FILE -> {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onConfirmFileImport(prompt) }
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("导入到备忘录")
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

private fun mainContentPadding(
    scaffoldPadding: PaddingValues,
    windowInfo: AdaptiveWindowInfo,
    includeScaffoldTop: Boolean = true
): PaddingValues {
    val horizontal = when (windowInfo.widthClass) {
        AdaptiveWidthClass.Compact -> 16.dp
        AdaptiveWidthClass.Medium -> 20.dp
        AdaptiveWidthClass.Expanded -> 24.dp
    }
    val bottomExtra = if (windowInfo.navigationType == AdaptiveNavigationType.BottomBar) {
        88.dp
    } else {
        96.dp
    }
    return PaddingValues(
        start = horizontal,
        top = (if (includeScaffoldTop) scaffoldPadding.calculateTopPadding() else 0.dp) + 12.dp,
        end = horizontal,
        bottom = scaffoldPadding.calculateBottomPadding() + bottomExtra
    )
}

private fun articlePaneContentPadding(
    scaffoldPadding: PaddingValues,
    windowInfo: AdaptiveWindowInfo,
    includeScaffoldTop: Boolean = true
): PaddingValues {
    val horizontal = when (windowInfo.widthClass) {
        AdaptiveWidthClass.Compact -> 16.dp
        AdaptiveWidthClass.Medium -> 12.dp
        AdaptiveWidthClass.Expanded -> 16.dp
    }
    return PaddingValues(
        start = horizontal,
        top = (if (includeScaffoldTop) scaffoldPadding.calculateTopPadding() else 0.dp) + 12.dp,
        end = horizontal,
        bottom = scaffoldPadding.calculateBottomPadding() + 96.dp
    )
}

private fun readerSplitListHorizontalPadding(windowInfo: AdaptiveWindowInfo) = when (windowInfo.widthClass) {
    AdaptiveWidthClass.Compact -> 16.dp
    AdaptiveWidthClass.Medium -> 12.dp
    AdaptiveWidthClass.Expanded -> 16.dp
}

private fun lerpMainDp(start: androidx.compose.ui.unit.Dp, stop: androidx.compose.ui.unit.Dp, progress: Float) =
    (start.value + (stop.value - start.value) * progress.coerceIn(0f, 1f)).dp

private fun lerpMainSp(start: Float, stop: Float, progress: Float) =
    (start + (stop - start) * progress.coerceIn(0f, 1f)).sp

private fun lerpMainColor(
    start: androidx.compose.ui.graphics.Color,
    stop: androidx.compose.ui.graphics.Color,
    progress: Float
): androidx.compose.ui.graphics.Color {
    val fraction = progress.coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.Color(
        red = start.red + (stop.red - start.red) * fraction,
        green = start.green + (stop.green - start.green) * fraction,
        blue = start.blue + (stop.blue - start.blue) * fraction,
        alpha = start.alpha + (stop.alpha - start.alpha) * fraction
    )
}

@Composable
private fun MorphingVerticalSlot(
    progress: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val slotProgress = progress.coerceIn(0f, 1f)
    when {
        slotProgress <= 0f -> Spacer(modifier = modifier.height(0.dp))
        slotProgress >= 1f -> Box(modifier = modifier) {
            content()
        }
        else -> Layout(
            modifier = modifier.clipToBounds(),
            content = content
        ) { measurables, constraints ->
            val looseConstraints = constraints.copy(minHeight = 0)
            val placeables = measurables.map { it.measure(looseConstraints) }
            val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
            val fullHeight = placeables.maxOfOrNull { it.height } ?: 0
            val animatedHeight = (fullHeight * slotProgress)
                .roundToInt()
                .coerceAtLeast(0)
            layout(width, animatedHeight) {
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, 0)
                }
            }
        }
    }
}

private fun mainScreenCanDeleteArticle(article: PhoneArticleEntity): Boolean {
    return article.independentSaved ||
        ImportedContentIds.isImportedContentUrl(article.url) ||
        ImportedContentIds.isImportedContentUrl(article.rssSourceUrl)
}
