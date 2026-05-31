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
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.platform.PlatformLinkRouter
import com.lightningstudio.watchrss.phone.ui.AddArticleDialog
import com.lightningstudio.watchrss.phone.ui.AddRssSourceDialog
import com.lightningstudio.watchrss.phone.ui.ArticleRow
import com.lightningstudio.watchrss.phone.ui.CapsuleFloatingButton
import com.lightningstudio.watchrss.phone.ui.ChannelsPage
import com.lightningstudio.watchrss.phone.ui.GlassTabBar
import com.lightningstudio.watchrss.phone.ui.LiquidGlassSegment
import com.lightningstudio.watchrss.phone.ui.MainTab
import com.lightningstudio.watchrss.phone.ui.GlassTopBar
import com.lightningstudio.watchrss.phone.ui.RefreshablePageColumn
import com.lightningstudio.watchrss.phone.ui.TAB_BAR_HEIGHT
import com.lightningstudio.watchrss.phone.ui.canDeleteArticle
import com.lightningstudio.watchrss.phone.ui.theme.AppCard
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModel
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RssActivity : ComponentActivity() {
    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, RssActivity::class.java)
        }
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            (application as PhoneCompanionApplication).container.repository,
            (application as PhoneCompanionApplication).container.bluetoothSyncManager
        )
    }

    private val importLocalContentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                viewModel.showMessage("已取消导入文件")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                runCatching {
                    readSelectedLocalContent(uri)
                }.onSuccess { file ->
                    viewModel.importLocalContent(
                        fileName = file.fileName,
                        mimeType = file.mimeType,
                        bytes = file.bytes
                    )
                }.onFailure { throwable ->
                    Log.e("RssActivity", "Failed to read local content", throwable)
                    viewModel.showError("文件导入失败：${throwable.message ?: "未知错误"}")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        setContent {
            WatchRssPhoneTheme {
                val uiState by viewModel.uiState.collectAsState()

                LaunchedEffect(Unit) {
                    viewModel.toastEvent.collect { msg ->
                        Toast.makeText(this@RssActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                val uriHandler = LocalUriHandler.current

                var showAddRssDialog by remember { mutableStateOf(false) }
                var showAddArticleDialog by remember { mutableStateOf(false) }
                var selectedSegment by remember { mutableStateOf(0) }

                val segments = listOf("频道", "收藏", "稍后", "独立")

                RssScreen(
                    uiState = uiState,
                    segments = segments,
                    selectedSegment = selectedSegment,
                    onSegmentChange = { selectedSegment = it },
                    onNavigateToTab = { tab ->
                        when (tab) {
                            MainTab.HOME -> {
                                startActivity(HomeActivity.createIntent(this))
                                overridePendingTransition(0, 0)
                            }
                            MainTab.NOVEL -> {
                                startActivity(ListPageActivity.createIntent(this, PageType.IMPORTED))
                                overridePendingTransition(0, 0)
                            }
                            else -> {}
                        }
                    },
                    onOpenSource = { source ->
                        // 在频道 Segment 中点击源，进入源文章列表
                        startActivity(ListPageActivity.createIntent(this, PageType.SOURCE_ARTICLES, source.url))
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
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onDismissMessage = viewModel::clearMessage
                )

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
fun RssScreen(
    uiState: MainUiState,
    segments: List<String>,
    selectedSegment: Int,
    onSegmentChange: (Int) -> Unit,
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
    onDismissMessage: () -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }

    val articlesBySource = remember(uiState.rssArticles) {
        uiState.rssArticles.groupBy { it.rssSourceUrl.orEmpty() }
    }

    val isRefreshing = uiState.refreshingRssSourceUrls.isNotEmpty()

    val currentArticles = when (selectedSegment) {
        0 -> emptyList() // 频道是 SourceRow，不是 ArticleRow
        1 -> uiState.favorites
        2 -> uiState.watchLater
        3 -> uiState.independentArticles
        else -> emptyList()
    }

    val emptyText = when (selectedSegment) {
        0 -> "暂无频道"
        1 -> "暂无收藏"
        2 -> "暂无稍后再看"
        3 -> "暂无独立文章"
        else -> ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 顶部栏高度（导航栏 + Segment）
        var topBarHeight by remember { mutableStateOf(0.dp) }

        // 内容区域
        Box(
            modifier = Modifier
                .layerBackdrop(backdrop)
                .fillMaxSize()
        ) {
            // 消息提示
            if (uiState.message != null || uiState.error != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(topBarHeight))
                    uiState.message?.takeIf { it.isNotBlank() }?.let {
                        AppCard {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .clickable(onClick = onDismissMessage)
                            )
                        }
                    }
                    uiState.error?.takeIf { it.isNotBlank() }?.let {
                        AppCard {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .clickable(onClick = onDismissMessage)
                            )
                        }
                    }
                }
            }

            // 内容列表
            when (selectedSegment) {
                0 -> {
                    val sourcesRefreshing = uiState.refreshingRssSourceUrls.isNotEmpty()
                    RefreshablePageColumn(
                        isRefreshing = sourcesRefreshing,
                        onRefresh = onRefreshAllRssSources,
                        topSpacing = topBarHeight,
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
                        isRefreshing = false,
                        onRefresh = {},
                        topSpacing = topBarHeight,
                        bottomSpacing = TAB_BAR_HEIGHT
                    ) {
                        if (currentArticles.isEmpty()) {
                            Text(
                                text = emptyText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            currentArticles.forEach { article ->
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

        // 顶部导航栏 + Segment
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onGloballyPositioned { coordinates ->
                    topBarHeight = with(density) { coordinates.size.height.toDp() }
                }
        ) {
            Column {
                GlassTopBar(
                    backdrop = backdrop,
                    title = "RSS",
                    onBack = null,
                    modifier = Modifier.fillMaxWidth()
                )
                LiquidGlassSegment(
                    backdrop = backdrop,
                    segments = segments,
                    selectedIndex = selectedSegment,
                    onSelect = onSegmentChange
                )
            }
        }

        // 右下角胶囊操作按钮
        when (selectedSegment) {
            0 -> {
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
            1 -> {
                if (uiState.favorites.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = TAB_BAR_HEIGHT + 16.dp)
                    ) {
                        CapsuleFloatingButton(
                            backdrop = backdrop,
                            onClick = onClearImportedContent
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Text("清空")
                        }
                    }
                }
            }
            2 -> {
                if (uiState.watchLater.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = TAB_BAR_HEIGHT + 16.dp)
                    ) {
                        CapsuleFloatingButton(
                            backdrop = backdrop,
                            onClick = onClearImportedContent
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Text("清空")
                        }
                    }
                }
            }
            3 -> {
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
        }

        // 底部 TabBar
        GlassTabBar(
            backdrop = backdrop,
            selectedTab = MainTab.RSS,
            onTabSelected = onNavigateToTab,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
