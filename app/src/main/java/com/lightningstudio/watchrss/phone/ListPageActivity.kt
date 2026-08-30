package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.importer.LocalFileImportTarget
import com.lightningstudio.watchrss.phone.data.importer.classifyLocalFileImport
import com.lightningstudio.watchrss.phone.data.note.NoteImportExportService
import com.lightningstudio.watchrss.phone.ui.DeleteConflictDialog
import com.lightningstudio.watchrss.phone.ui.SharedImportDialog
import com.lightningstudio.watchrss.phone.ui.SupportContactBlockingAlert
import com.lightningstudio.watchrss.phone.ui.SupportContactInlineFooter
import com.lightningstudio.watchrss.phone.ui.TxtUpdateDialog
import com.lightningstudio.watchrss.phone.ui.TxtChapterImportDialog
import com.lightningstudio.watchrss.phone.ui.BluetoothDeviceChooserDialog
import com.lightningstudio.watchrss.phone.viewmodel.MainBluetoothDeviceUi
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.platform.PlatformLinkRouter
import com.lightningstudio.watchrss.phone.ui.AddArticleDialog
import com.lightningstudio.watchrss.phone.ui.AddRssSourceDialog
import com.lightningstudio.watchrss.phone.ui.ArticleRow
import com.lightningstudio.watchrss.phone.ui.CapsuleFloatingButton
import com.lightningstudio.watchrss.phone.ui.ChannelsPage
import com.lightningstudio.watchrss.phone.ui.GlassTabBar
import com.lightningstudio.watchrss.phone.ui.MainTab
import com.lightningstudio.watchrss.phone.ui.GlassTopBar
import com.lightningstudio.watchrss.phone.ui.RefreshablePageColumn
import com.lightningstudio.watchrss.phone.ui.TAB_BAR_HEIGHT
import com.lightningstudio.watchrss.phone.ui.canDeleteArticle
import com.lightningstudio.watchrss.phone.ui.theme.AppCard
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.ui.theme.roundedClickable
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModel
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PageType { CHANNELS, SOURCE_ARTICLES, INDEPENDENT, IMPORTED }

class ListPageActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PAGE_TYPE = "page_type"
        const val EXTRA_SOURCE_URL = "source_url"
        private const val TAG = "腕上RSS_ListPage"

        fun createIntent(context: Context, pageType: PageType, sourceUrl: String? = null): Intent {
            return Intent(context, ListPageActivity::class.java)
                .putExtra(EXTRA_PAGE_TYPE, pageType.name)
                .apply { sourceUrl?.let { putExtra(EXTRA_SOURCE_URL, it) } }
        }
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            (application as PhoneCompanionApplication).container.repository,
            (application as PhoneCompanionApplication).container.bluetoothSyncManager,
            (application as PhoneCompanionApplication).container.llmTokenUsageRepository,
            (application as PhoneCompanionApplication).container.usageTelemetry,
            (application as PhoneCompanionApplication).container.backupService,
            (application as PhoneCompanionApplication).container.tipManager
        )
    }
    private val noteImportService by lazy {
        val container = (application as PhoneCompanionApplication).container
        NoteImportExportService(this, container.noteRepository)
    }

    private val importLocalContentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                viewModel.showMessage("已取消导入文件")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                runCatching {
                    val file = readSelectedLocalContent(uri)
                    importSelectedFile(file)
                }.onFailure { throwable ->
                    Log.e(TAG, "Failed to read local content", throwable)
                    viewModel.showError("文件导入失败：${throwable.message ?: "未知错误"}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pageType = intent.getStringExtra(EXTRA_PAGE_TYPE)?.let { PageType.valueOf(it) } ?: return finish()
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL)

        setContent {
            WatchRssPhoneTheme {
                val uiState by viewModel.uiState.collectAsState()

                LaunchedEffect(Unit) {
                    viewModel.toastEvent.collect { msg ->
                        Toast.makeText(this@ListPageActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                val uriHandler = LocalUriHandler.current

                var showAddRssDialog by remember { mutableStateOf(false) }
                var showAddArticleDialog by remember { mutableStateOf(false) }

                ListPageScreen(
                    pageType = pageType,
                    sourceUrl = sourceUrl,
                    uiState = uiState,
                    onBack = { finish() },
                    onNavigateToTab = { tab ->
                        when (tab) {
                            MainTab.HOME -> {
                                startActivity(HomeActivity.createIntent(this))
                                overridePendingTransition(0, 0)
                            }
                            MainTab.RSS -> {
                                startActivity(RssActivity.createIntent(this))
                                overridePendingTransition(0, 0)
                            }
                            MainTab.NOVEL -> {
                                if (pageType != PageType.IMPORTED) {
                                    startActivity(createIntent(this, PageType.IMPORTED))
                                    overridePendingTransition(0, 0)
                                    finish()
                                }
                            }
                        }
                    },
                    onOpenSource = { source ->
                        startActivity(createIntent(this, PageType.SOURCE_ARTICLES, source.url))
                    },
                    onOpenArticle = { article ->
                        val platform = PlatformLinkRouter.detect(article.url)
                        if (platform != null) {
                            startActivity(PlatformWebViewActivity.createIntent(
                                context = this,
                                title = article.title.ifBlank { article.url },
                                url = article.url
                            ))
                        } else {
                            startActivity(ArticleReaderActivity.createIntent(this, article.articleId))
                        }
                    },
                    onOpenOriginalLink = { url -> uriHandler.openUri(url) },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onToggleWatchLater = viewModel::toggleWatchLater,
                    onDeleteArticle = viewModel::deleteArticle,
                    onMoveRssSourceToTop = viewModel::moveRssSourceToTop,
                    onToggleRssSourcePinned = viewModel::toggleRssSourcePinned,
                    onDeleteRssSource = viewModel::deleteRssSource,
                    onRefreshAllRssSources = viewModel::refreshAllRssSources,
                    onRefreshRssSource = viewModel::refreshRssSource,
                    onClearImportedContent = viewModel::clearImportedContent,
                    onAddRssSource = { showAddRssDialog = true },
                    onImportArticle = { showAddArticleDialog = true },
                    onImportFile = {
                        importLocalContentLauncher.launch(
                            arrayOf(
                                "text/plain",
                                "text/*",
                                "application/epub+zip",
                                "text/x-opml",
                                "application/x-opml+xml",
                                "application/xml",
                                "text/xml",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onDismissMessage = viewModel::clearMessage,
                    onDismissSupportAlert = viewModel::dismissSupportAlert,
                )

                // 全局对话框（与 MainActivity 共享同一套状态）
                uiState.conflictPrompt?.let { prompt ->
                    DeleteConflictDialog(
                        prompt = prompt,
                        onChooseResolution = viewModel::chooseConflictResolution,
                        onShowManualOptions = viewModel::showManualConflictOptions
                    )
                }
                uiState.sharedImportPrompt?.let { prompt ->
                    SharedImportDialog(
                        prompt = prompt,
                        onImportLinkAsArticle = viewModel::importSharedLinkAsIndependent,
                        onImportLinkAsRss = viewModel::importSharedLinkAsRss,
                        onConfirmFileImport = { filePrompt ->
                            viewModel.dismissSharedImportPrompt()
                            viewModel.showMessage("正在读取文件…")
                            lifecycleScope.launch {
                                runCatching {
                                    val file = readSelectedLocalContent(
                                        Uri.parse(filePrompt.uriString),
                                        filePrompt.mimeType
                                    )
                                    importSelectedFile(file)
                                }.onFailure { throwable ->
                                    Log.e(TAG, "Failed to read shared local content", throwable)
                                    viewModel.showContentError("文件导入失败：${throwable.message ?: "未知错误"}")
                                }
                            }
                        },
                        onDismiss = viewModel::dismissSharedImportPrompt
                    )
                }
                uiState.txtChapterPrompt?.let { prompt ->
                    TxtChapterImportDialog(
                        prompt = prompt,
                        onChooseChapterImport = viewModel::chooseTxtChapterImport,
                        onDismiss = viewModel::dismissTxtChapterPrompt
                    )
                }
                uiState.txtUpdatePrompt?.let { prompt ->
                    TxtUpdateDialog(
                        prompt = prompt,
                        onConfirmReplace = viewModel::confirmTxtUpdate,
                        onImportAsNew = viewModel::importPendingTxtAsNew,
                        onDismiss = viewModel::dismissTxtUpdatePrompt
                    )
                }
                uiState.bluetoothDevicePrompt?.let { prompt ->
                    BluetoothDeviceChooserDialog(
                        prompt = prompt,
                        onChooseDevice = viewModel::chooseBluetoothDeviceForSync,
                        onDismiss = viewModel::dismissBluetoothDevicePrompt
                    )
                }

                // 页面内对话框
                if (showAddRssDialog) {
                    AddRssSourceDialog(
                        urlInput = uiState.urlInput,
                        onUrlChange = viewModel::updateUrlInput,
                        onAdd = {
                            showAddRssDialog = false
                            viewModel.addRssSource()
                        },
                        onDismiss = { showAddRssDialog = false }
                    )
                }

                if (showAddArticleDialog) {
                    AddArticleDialog(
                        urlInput = uiState.urlInput,
                        onUrlChange = viewModel::updateUrlInput,
                        onImport = {
                            showAddArticleDialog = false
                            viewModel.importIndependentArticle()
                        },
                        onDismiss = { showAddArticleDialog = false }
                    )
                }
            }
        }
    }

    private suspend fun readSelectedLocalContent(
        uri: Uri,
        fallbackMimeType: String? = null
    ): SelectedLocalContent =
        withContext(Dispatchers.IO) {
            val fileName = queryDisplayName(uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "未命名文件"
            val mimeType = contentResolver.getType(uri) ?: fallbackMimeType
            val bytes = contentResolver.openInputStream(uri)
                ?.use { input -> input.readBytes() }
                ?: error("无法读取文件")
            SelectedLocalContent(fileName, mimeType, bytes)
        }

    private suspend fun importSelectedFile(file: SelectedLocalContent) {
        when (classifyLocalFileImport(file.fileName, file.mimeType)) {
            LocalFileImportTarget.MARKDOWN_NOTE -> {
                val note = noteImportService.importMarkdown(file.fileName, file.mimeType, file.bytes)
                runCatching { (application as PhoneCompanionApplication).container.cloudSyncService.syncNow() }
                viewModel.showContentMessage("已导入备忘录：${note.title}")
            }
            LocalFileImportTarget.LOCAL_CONTENT -> viewModel.importLocalContent(
                fileName = file.fileName,
                mimeType = file.mimeType,
                bytes = file.bytes
            )
            LocalFileImportTarget.OPML_SUBSCRIPTIONS -> viewModel.importOpml(file.bytes)
            LocalFileImportTarget.UNSUPPORTED -> error("只支持 OPML、Markdown（.md）、TXT 和 EPUB 文件")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun SkeletonLoadingScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            repeat(6) {
                SkeletonArticleRow()
            }
        }
        CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
private fun SkeletonArticleRow() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing)
        ),
        label = "alpha"
    )

    val shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 标题占位条
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerColor)
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 副标题占位条
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerColor)
        )
    }
}

private fun PageType.toMainTab(): MainTab {
    return when (this) {
        PageType.IMPORTED -> MainTab.NOVEL
        else -> MainTab.RSS
    }
}

@Composable
fun ListPageScreen(
    pageType: PageType,
    sourceUrl: String?,
    uiState: MainUiState,
    onBack: () -> Unit,
    onNavigateToTab: (MainTab) -> Unit,
    onOpenSource: (PhoneRssSourceEntity) -> Unit,
    onOpenArticle: (PhoneArticleEntity) -> Unit,
    onOpenOriginalLink: (String) -> Unit,
    onToggleFavorite: (PhoneArticleEntity) -> Unit,
    onToggleWatchLater: (PhoneArticleEntity) -> Unit,
    onDeleteArticle: (PhoneArticleEntity) -> Unit,
    onMoveRssSourceToTop: (PhoneRssSourceEntity) -> Unit,
    onToggleRssSourcePinned: (PhoneRssSourceEntity) -> Unit,
    onDeleteRssSource: (PhoneRssSourceEntity) -> Unit,
    onRefreshAllRssSources: () -> Unit,
    onRefreshRssSource: (PhoneRssSourceEntity) -> Unit,
    onClearImportedContent: () -> Unit,
    onAddRssSource: () -> Unit,
    onImportArticle: () -> Unit,
    onImportFile: () -> Unit,
    onDismissMessage: () -> Unit,
    onDismissSupportAlert: () -> Unit
) {
    val currentTab = pageType.toMainTab()

    val source = if (sourceUrl != null) {
        uiState.rssSources.firstOrNull { it.url == sourceUrl }
    } else null

    val isRefreshing = when (pageType) {
        PageType.CHANNELS -> uiState.refreshingRssSourceUrls.isNotEmpty()
        PageType.SOURCE_ARTICLES -> sourceUrl in uiState.refreshingRssSourceUrls
        else -> false
    }

    val articles = when (pageType) {
        PageType.SOURCE_ARTICLES -> uiState.rssArticles.filter { it.rssSourceUrl == sourceUrl }
        PageType.INDEPENDENT -> uiState.independentArticles
        PageType.IMPORTED -> uiState.importedContentArticles
        else -> emptyList()
    }

    val title = when (pageType) {
        PageType.CHANNELS -> "频道"
        PageType.SOURCE_ARTICLES -> source?.title ?: "频道"
        PageType.INDEPENDENT -> "独立文章"
        PageType.IMPORTED -> "小说"
    }

    val isTopLevel = pageType == PageType.IMPORTED
    val contentTopPadding = if (isTopLevel) 72.dp else 56.dp

    val emptyText = when (pageType) {
        PageType.CHANNELS -> "暂无频道"
        PageType.SOURCE_ARTICLES -> "此频道暂无文章"
        PageType.INDEPENDENT -> "暂无独立文章"
        PageType.IMPORTED -> "暂无导入内容"
    }

    val backgroundColor = MaterialTheme.colorScheme.background

    Box(modifier = Modifier.fillMaxSize()) {
        val backdrop = rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }

        // 内容区域
        Box(
            modifier = Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
        ) {
            // 全局消息/错误提示条
            if (uiState.message != null || uiState.error != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = contentTopPadding, start = 16.dp, end = 16.dp)
                ) {
                    uiState.message?.takeIf { it.isNotBlank() }?.let {
                        AppCard(
                            interactionModifier = Modifier.roundedClickable(
                                shape = RoundedCornerShape(16.dp),
                                onClick = onDismissMessage
                            )
                        ) {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    uiState.error?.takeIf { it.isNotBlank() }?.let {
                        AppCard(
                            interactionModifier = Modifier.roundedClickable(
                                shape = RoundedCornerShape(16.dp),
                                onClick = onDismissMessage
                            )
                        ) {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    if (uiState.error != null || uiState.message?.contains("失败") == true) {
                        SupportContactInlineFooter(
                            hint = "操作遇到问题？联系客服并提供上方提示"
                        )
                    }
                }
            }

            // 阻断式客服 Alert
            uiState.supportAlert?.let { alert ->
                SupportContactBlockingAlert(
                    title = alert.title,
                    message = alert.message,
                    errorDetails = alert.errorDetails,
                    onDismiss = onDismissSupportAlert
                )
            }

            when (pageType) {
                PageType.CHANNELS -> {
                    val articlesBySource = remember(uiState.rssArticles) {
                        uiState.rssArticles.groupBy { it.rssSourceUrl.orEmpty() }
                    }
                    RefreshablePageColumn(
                        isRefreshing = isRefreshing,
                        onRefresh = onRefreshAllRssSources,
                        topSpacing = contentTopPadding,
                        bottomSpacing = TAB_BAR_HEIGHT
                    ) {
                        ChannelsPage(
                            sources = uiState.rssSources,
                            articlesBySource = articlesBySource,
                            onOpenSource = onOpenSource,
                            onMoveToTop = onMoveRssSourceToTop,
                            onTogglePinned = onToggleRssSourcePinned,
                            onDelete = onDeleteRssSource
                        )
                    }
                }
                else -> {
                    RefreshablePageColumn(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            if (pageType == PageType.SOURCE_ARTICLES && source != null) {
                                onRefreshRssSource(source)
                            }
                        },
                        topSpacing = contentTopPadding,
                        bottomSpacing = TAB_BAR_HEIGHT
                    ) {
                        if (articles.isEmpty()) {
                            if (isRefreshing) {
                                SkeletonLoadingScreen()
                            } else {
                                Text(
                                    text = emptyText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
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
                    }
                }
            }
        }

        // 顶部导航栏
        GlassTopBar(
            backdrop = backdrop,
            title = title,
            onBack = if (isTopLevel) null else onBack,
            modifier = Modifier.align(Alignment.TopCenter),
            actions = {
                when (pageType) {
                    PageType.IMPORTED -> {
                        if (uiState.importedContentArticles.isNotEmpty()) {
                            IconButton(onClick = onClearImportedContent) {
                                Icon(Icons.Default.Delete, contentDescription = "清空")
                            }
                        }
                    }
                    else -> {}
                }
            }
        )

        // 右下角胶囊操作按钮
        when (pageType) {
            PageType.SOURCE_ARTICLES -> {
                val refreshing = sourceUrl in uiState.refreshingRssSourceUrls
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = TAB_BAR_HEIGHT + 16.dp)
                ) {
                    CapsuleFloatingButton(
                        backdrop = backdrop,
                        onClick = { source?.let(onRefreshRssSource) },
                        enabled = !refreshing
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Text("刷新")
                    }
                }
            }
            PageType.CHANNELS -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = TAB_BAR_HEIGHT + 16.dp)
                ) {
                    CapsuleFloatingButton(
                        backdrop = backdrop,
                        onClick = onAddRssSource
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加 RSS")
                    }
                }
            }
            PageType.INDEPENDENT -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = TAB_BAR_HEIGHT + 16.dp)
                ) {
                    CapsuleFloatingButton(
                        backdrop = backdrop,
                        onClick = onImportArticle
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加文章")
                    }
                }
            }
            PageType.IMPORTED -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = TAB_BAR_HEIGHT + 16.dp)
                ) {
                    CapsuleFloatingButton(
                        backdrop = backdrop,
                        onClick = onImportFile
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Text("导入文件")
                    }
                }
            }
        }

        // 底部 TabBar
        GlassTabBar(
            backdrop = backdrop,
            selectedTab = currentTab,
            onTabSelected = onNavigateToTab,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
