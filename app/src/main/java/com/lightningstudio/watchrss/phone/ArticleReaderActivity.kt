package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import coil.compose.AsyncImage
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.local.ARTICLE_TEXT_CHUNK_BYTES
import com.lightningstudio.watchrss.phone.data.local.isArticleContentMarker
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.repo.PhoneImportedTextReader
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.ui.theme.AppCard
import com.lightningstudio.watchrss.phone.ui.theme.AppPrimaryCard
import com.lightningstudio.watchrss.phone.ui.theme.PrimaryRed
import com.kyant.backdrop.*
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.effects.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

class ArticleReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val articleId = intent.getStringExtra(EXTRA_ARTICLE_ID).orEmpty()
        val repository = (application as PhoneCompanionApplication).container.repository

        setContent {
            WatchRssPhoneTheme {
                val article by remember(articleId) {
                    repository.observeArticle(articleId)
                }.collectAsState(initial = null)
                val importedTextReader by produceState<PhoneImportedTextReader?>(
                    initialValue = null,
                    articleId
                ) {
                    value = repository.getImportedTextReader(articleId)
                }
                ArticleReaderScreen(
                    article = article,
                    importedTextReader = importedTextReader,
                    invalidArticleId = articleId.isBlank(),
                    onLoadImportedTextChunk = repository::loadImportedTextChunk,
                    onSaveReadingProgress = { progress ->
                        repository.updateArticleReadingProgress(articleId, progress)
                    },
                    onBack = { finish() },
                    onOpenImportedArticle = { url ->
                        val targetId = runCatching {
                            WebArticleImporter.stableArticleId(url)
                        }.getOrNull()
                        if (targetId != null) {
                            startActivity(createIntent(this, targetId))
                        }
                    },
                    onOpenOriginal = { url ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ARTICLE_ID = "article_id"

        fun createIntent(context: Context, articleId: String): Intent {
            return Intent(context, ArticleReaderActivity::class.java).apply {
                putExtra(EXTRA_ARTICLE_ID, articleId)
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ArticleReaderScreen(
    article: PhoneArticleEntity?,
    importedTextReader: PhoneImportedTextReader?,
    invalidArticleId: Boolean,
    onLoadImportedTextChunk: suspend (String, Int) -> String?,
    onSaveReadingProgress: suspend (Float) -> Unit,
    onBack: () -> Unit,
    onOpenImportedArticle: (String) -> Unit,
    onOpenOriginal: (String) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        if (invalidArticleId) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "文章不存在", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
            return@Surface
        }
        val safeArticle = article
        if (safeArticle == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "正在加载文章…", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
            return@Surface
        }

        val hasFileBackedImportedText = ImportedContentIds.isImportedTextArticleUrl(safeArticle.url) &&
            isArticleContentMarker(safeArticle.contentText)
        val waitingForImportedTextReader = hasFileBackedImportedText && importedTextReader == null
        val useImportedTextChunks = importedTextReader != null
        val contentNodes = remember(
            safeArticle.articleId,
            safeArticle.contentHash,
            safeArticle.contentHtml,
            safeArticle.contentText,
            safeArticle.excerpt,
            safeArticle.url,
            waitingForImportedTextReader,
            useImportedTextChunks
        ) {
            if (useImportedTextChunks || waitingForImportedTextReader) {
                emptyList()
            } else {
                if (!safeArticle.contentHtml.isNullOrBlank()) {
                    parseArticleContent(safeArticle.contentHtml ?: "")
                } else {
                    buildPlainArticleNodes(
                        safeArticle.contentText
                            .ifBlank { safeArticle.excerpt }
                            .ifBlank { safeArticle.url }
                    )
                }
            }
        }
        val listState = rememberLazyListState()
        val textLayouts = remember(safeArticle.articleId, contentNodes) {
            mutableStateMapOf<Int, TextLayoutResult>()
        }
        val importedTextChunkLayouts = remember(safeArticle.articleId, importedTextReader?.marker) {
            mutableStateMapOf<Int, TextLayoutResult>()
        }
        val importedTextChunkTexts = remember(safeArticle.articleId, importedTextReader?.marker) {
            mutableStateMapOf<Int, String>()
        }
        var topBarHeight by remember { mutableStateOf(0.dp) }
        var bottomBarHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        val topBarHeightPx = with(density) { topBarHeight.roundToPx() }
        val bottomBarHeightPx = with(density) { bottomBarHeight.roundToPx() }
        val chromeHideRangePx = maxOf(topBarHeightPx, bottomBarHeightPx)
        var readerChromeOffsetPx by remember { mutableStateOf(0f) }
        var lastReaderChromeDirection by remember { mutableStateOf(0) }
        val visibleTopBarHeightPx = (topBarHeightPx - readerChromeOffsetPx
            .coerceAtMost(topBarHeightPx.toFloat()))
            .roundToInt()
            .coerceAtLeast(0)
        var hasRestoredPosition by remember(safeArticle.articleId) { mutableStateOf(false) }
        var pendingRestoreProgress by remember(safeArticle.articleId) {
            mutableStateOf<Float?>(safeArticle.readingProgress.coerceIn(0f, 1f))
        }
        var pendingTextRestore by remember(safeArticle.articleId) {
            mutableStateOf<ArticleTextRestoreTarget?>(null)
        }
        var pendingImportedTextRestore by remember(safeArticle.articleId, importedTextReader?.marker) {
            mutableStateOf<ImportedTextByteRestoreTarget?>(null)
        }
        var lastSavedProgress by remember(safeArticle.articleId) { mutableStateOf(-1f) }
        var lastProgressSavedAt by remember(safeArticle.articleId) { mutableStateOf(0L) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val onSaveReadingProgressState = rememberUpdatedState(onSaveReadingProgress)
        val onBackState = rememberUpdatedState(onBack)

        fun updateReaderChromeOffset(deltaPx: Float) {
            if (chromeHideRangePx <= 0 || deltaPx == 0f) return
            readerChromeOffsetPx = (readerChromeOffsetPx + deltaPx)
                .coerceIn(0f, chromeHideRangePx.toFloat())
            if (kotlin.math.abs(deltaPx) > 0.5f) {
                lastReaderChromeDirection = if (deltaPx > 0f) 1 else -1
            }
        }

        val readerChromeNestedScrollConnection = remember(chromeHideRangePx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0f) {
                        updateReaderChromeOffset(deltaPx = -available.y)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    if (consumed.y < 0f) {
                        updateReaderChromeOffset(deltaPx = -consumed.y)
                    }
                    return Offset.Zero
                }
            }
        }
        val isReaderScrollInProgress = listState.isScrollInProgress
        LaunchedEffect(isReaderScrollInProgress, chromeHideRangePx) {
            if (chromeHideRangePx <= 0) return@LaunchedEffect
            readerChromeOffsetPx = readerChromeOffsetPx.coerceIn(0f, chromeHideRangePx.toFloat())
            if (!isReaderScrollInProgress) {
                val targetOffsetPx = when {
                    lastReaderChromeDirection > 0 -> chromeHideRangePx.toFloat()
                    lastReaderChromeDirection < 0 -> 0f
                    readerChromeOffsetPx > chromeHideRangePx * 0.5f -> chromeHideRangePx.toFloat()
                    else -> 0f
                }
                if (kotlin.math.abs(readerChromeOffsetPx - targetOffsetPx) > 0.5f) {
                    animate(
                        initialValue = readerChromeOffsetPx,
                        targetValue = targetOffsetPx,
                        animationSpec = tween(durationMillis = READER_CHROME_SNAP_ANIMATION_MS)
                    ) { value, _ ->
                        readerChromeOffsetPx = value.coerceIn(0f, chromeHideRangePx.toFloat())
                    }
                } else {
                    readerChromeOffsetPx = targetOffsetPx
                }
            }
        }

        fun freshReadingProgress(): Float? {
            return importedTextReader?.let { reader ->
                calculateImportedTextByteReadingProgressFromLayout(
                    listState = listState,
                    marker = reader.marker,
                    byteLength = reader.byteLength,
                    chunkCount = reader.chunkCount,
                    chunkTexts = importedTextChunkTexts,
                    chunkLayouts = importedTextChunkLayouts,
                    anchorOffsetPx = visibleTopBarHeightPx
                )
            } ?: calculateArticleTextReadingProgressFromLayout(
                listState = listState,
                nodes = contentNodes,
                textLayouts = textLayouts,
                anchorOffsetPx = visibleTopBarHeightPx
            )
        }

        suspend fun awaitReadingProgress(): Float? {
            freshReadingProgress()?.let { return it }
            return withTimeoutOrNull(ARTICLE_READING_PROGRESS_LAYOUT_TIMEOUT_MS) {
                snapshotFlow { freshReadingProgress() }
                    .filterNotNull()
                    .first()
            }
        }

        suspend fun saveCurrentReadingProgress(force: Boolean): Boolean {
            if (!hasRestoredPosition && !force) return false
            val progress = awaitReadingProgress() ?: return false
            val clamped = progress.coerceIn(0f, 1f)
            val now = SystemClock.elapsedRealtime()
            if (!force && lastSavedProgress >= 0f) {
                val diff = kotlin.math.abs(clamped - lastSavedProgress)
                if (diff < 0.02f && now - lastProgressSavedAt < 1500L) return false
            }
            lastSavedProgress = clamped
            lastProgressSavedAt = now
            onSaveReadingProgressState.value(clamped)
            return true
        }

        LaunchedEffect(pendingRestoreProgress, contentNodes, topBarHeight, importedTextReader) {
            val progress = pendingRestoreProgress ?: return@LaunchedEffect
            if (topBarHeight == 0.dp) return@LaunchedEffect
            if (waitingForImportedTextReader) return@LaunchedEffect
            importedTextReader?.let { reader ->
                if (reader.chunkCount <= 0 || reader.byteLength <= 0L) {
                    pendingRestoreProgress = null
                    hasRestoredPosition = true
                    return@LaunchedEffect
                }
                val restoreTarget = importedTextByteRestoreTarget(
                    progress = progress,
                    byteLength = reader.byteLength,
                    chunkCount = reader.chunkCount,
                    chunkBytes = ARTICLE_TEXT_CHUNK_BYTES
                )
                val targetIndex = restoreTarget.chunkIndex.coerceIn(0, reader.chunkCount - 1)
                listState.scrollToItem(targetIndex)
                pendingImportedTextRestore = restoreTarget.copy(itemIndex = targetIndex)
                pendingRestoreProgress = null
                return@LaunchedEffect
            }
            if (contentNodes.isEmpty()) {
                pendingRestoreProgress = null
                hasRestoredPosition = true
                return@LaunchedEffect
            }
            val restoreTarget = articleTextRestoreTarget(
                progress = progress,
                nodes = contentNodes
            )
            if (restoreTarget == null) {
                listState.scrollToItem(
                    ((contentNodes.size - 1) * progress)
                        .roundToInt()
                        .coerceIn(0, contentNodes.lastIndex)
                )
                pendingRestoreProgress = null
                hasRestoredPosition = true
                return@LaunchedEffect
            }
            listState.scrollToItem(restoreTarget.itemIndex.coerceIn(0, contentNodes.lastIndex))
            pendingTextRestore = restoreTarget.copy(
                itemIndex = restoreTarget.itemIndex.coerceIn(0, contentNodes.lastIndex)
            )
            pendingRestoreProgress = null
        }

        LaunchedEffect(pendingImportedTextRestore) {
            val restoreTarget = pendingImportedTextRestore ?: return@LaunchedEffect
            val offsetPx = withTimeoutOrNull(ARTICLE_RESTORE_OFFSET_TIMEOUT_MS) {
                snapshotFlow {
                    val text = importedTextChunkTexts[restoreTarget.chunkIndex]
                    val layout = importedTextChunkLayouts[restoreTarget.chunkIndex]
                    val itemInfo = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == restoreTarget.itemIndex }
                    if (text == null || layout == null || itemInfo == null) {
                        null
                    } else {
                        importedTextRestoreVisualOffsetPx(
                            restoreTarget = restoreTarget,
                            text = text,
                            layout = layout,
                            itemInfo = itemInfo,
                            anchorOffsetPx = visibleTopBarHeightPx
                        )
                    }
                }
                    .filterNotNull()
                    .first()
            }
            if (offsetPx != null) {
                listState.scrollToItem(restoreTarget.itemIndex, offsetPx)
            }
            pendingImportedTextRestore = null
            hasRestoredPosition = true
        }

        LaunchedEffect(pendingTextRestore) {
            val restoreTarget = pendingTextRestore ?: return@LaunchedEffect
            val offsetPx = withTimeoutOrNull(ARTICLE_RESTORE_OFFSET_TIMEOUT_MS) {
                snapshotFlow {
                    val text = articleNodeText(contentNodes.getOrNull(restoreTarget.nodeIndex))
                    val layout = textLayouts[restoreTarget.nodeIndex]
                    val itemInfo = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == restoreTarget.itemIndex }
                    if (text == null || layout == null || itemInfo == null) {
                        null
                    } else {
                        articleTextRestoreVisualOffsetPx(
                            restoreTarget = restoreTarget,
                            text = text,
                            layout = layout,
                            itemInfo = itemInfo,
                            anchorOffsetPx = visibleTopBarHeightPx
                        )
                    }
                }
                    .filterNotNull()
                    .first()
            }
            if (offsetPx != null) {
                listState.scrollToItem(restoreTarget.itemIndex, offsetPx)
            }
            pendingTextRestore = null
            hasRestoredPosition = true
        }

        LaunchedEffect(listState, contentNodes) {
            snapshotFlow { freshReadingProgress() }
                .filterNotNull()
                .distinctUntilChanged()
                .sample(ARTICLE_READING_PROGRESS_SAMPLE_MS)
                .collect { progress ->
                    if (hasRestoredPosition) {
                        saveCurrentReadingProgress(force = false)
                    }
                }
        }

        DisposableEffect(lifecycleOwner, safeArticle.articleId) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    runBlocking {
                        saveCurrentReadingProgress(force = true)
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        fun handleBack() {
            runBlocking {
                saveCurrentReadingProgress(force = true)
            }
            onBackState.value()
        }

        BackHandler(onBack = ::handleBack)

        Box(modifier = Modifier.fillMaxSize()) {
            val backgroundColor = MaterialTheme.colorScheme.background
            val surfaceColorArgb = MaterialTheme.colorScheme.surface.toArgb()
            val backdrop = rememberLayerBackdrop {
                drawRect(backgroundColor)
                drawContent()
            }

            // 内容区域 - 使用原生 Compose 渲染
            Box(
                modifier = Modifier
                    .layerBackdrop(backdrop)
                    .fillMaxSize()
                    .nestedScroll(readerChromeNestedScrollConnection)
                    .clipToBounds()
            ) {
                val readerContentPadding = PaddingValues(
                    top = topBarHeight + 20.dp,
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 20.dp
                )
                val reader = importedTextReader
                when {
                    reader != null -> {
                        ImportedTextChunkContentView(
                            reader = reader,
                            listState = listState,
                            chunkTexts = importedTextChunkTexts,
                            chunkLayouts = importedTextChunkLayouts,
                            onLoadImportedTextChunk = onLoadImportedTextChunk,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = readerContentPadding
                        )
                    }
                    waitingForImportedTextReader -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(readerContentPadding),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "正在加载正文…",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                    else -> {
                        ArticleReaderContentView(
                            nodes = contentNodes,
                            listState = listState,
                            textLayouts = textLayouts,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = readerContentPadding
                        )
                    }
                }
            }

            val topBarOffsetPx = readerChromeOffsetPx
                .coerceAtMost(topBarHeightPx.toFloat())
                .roundToInt()
            val bottomBarOffsetPx = readerChromeOffsetPx
                .coerceAtMost(bottomBarHeightPx.toFloat())
                .roundToInt()

            // 顶部无圆角高斯模糊
            Box(
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        topBarHeight = with(density) { coordinates.size.height.toDp() }
                    }
                    .fillMaxWidth()
                    .offset { IntOffset(0, -topBarOffsetPx) }
                    .gaussianBlurBackdrop(
                        backdrop = backdrop
                    )
                    .padding(bottom = 12.dp)
            ) {
                ReaderTopContent(
                    article = safeArticle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // 底部无圆角高斯模糊按钮栏
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { coordinates ->
                        bottomBarHeight = with(density) { coordinates.size.height.toDp() }
                    }
                    .fillMaxWidth()
                    .offset { IntOffset(0, bottomBarOffsetPx) }
                    .gaussianBlurBackdrop(
                        backdrop = backdrop
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassButton(onClick = ::handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text("返回")
                    }
                    if (safeArticle.url.isNotBlank() && !ImportedContentIds.isImportedContentUrl(safeArticle.url)) {
                        GlassButton(onClick = { onOpenOriginal(safeArticle.url) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Text("原网页")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTopContent(
    article: PhoneArticleEntity,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = article.title.ifBlank { article.url },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        article.siteName.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GlassButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

// 阅读页统一的无圆角高斯模糊效果
private fun Modifier.gaussianBlurBackdrop(
    backdrop: LayerBackdrop
) = drawBackdrop(
    backdrop = backdrop,
    shape = { RectangleShape },
    highlight = null,
    shadow = null,
    effects = {
        blur(18f.dp.toPx())
    }
)

/**
 * 原生文章渲染器 - 将 HTML 解析为 Compose 组件
 */
@Composable
private fun ImportedTextChunkContentView(
    reader: PhoneImportedTextReader,
    listState: LazyListState,
    chunkTexts: MutableMap<Int, String>,
    chunkLayouts: MutableMap<Int, TextLayoutResult>,
    onLoadImportedTextChunk: suspend (String, Int) -> String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding
    ) {
        items(
            count = reader.chunkCount,
            key = { index -> importedTextChunkKey(reader.marker, index) },
            contentType = { "imported_text_chunk" }
        ) { index ->
            val marker = reader.marker
            val chunk by produceState<String?>(initialValue = null, marker, index) {
                value = onLoadImportedTextChunk(marker, index)
            }
            LaunchedEffect(marker, index, chunk) {
                if (chunk == null) {
                    chunkTexts.remove(index)
                    chunkLayouts.remove(index)
                } else {
                    chunkTexts[index] = chunk.orEmpty()
                }
            }
            val text = chunk
            if (text == null) {
                Spacer(modifier = Modifier.height(1.dp))
            } else if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    onTextLayout = { chunkLayouts[index] = it },
                    modifier = Modifier.padding(vertical = 8.dp),
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4f
                )
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun ArticleReaderContentView(
    nodes: List<ArticleNode>,
    listState: LazyListState,
    textLayouts: MutableMap<Int, TextLayoutResult>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding
    ) {
        itemsIndexed(nodes) { index, node ->
            when (node) {
                is ArticleNode.Heading -> {
                    Text(
                        text = node.text,
                        style = when (node.level) {
                            1 -> MaterialTheme.typography.headlineLarge
                            2 -> MaterialTheme.typography.headlineMedium
                            3 -> MaterialTheme.typography.headlineSmall
                            else -> MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.Bold,
                        onTextLayout = { textLayouts[index] = it },
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                is ArticleNode.Paragraph -> {
                    Text(
                        text = node.text,
                        style = MaterialTheme.typography.bodyLarge,
                        onTextLayout = { textLayouts[index] = it },
                        modifier = Modifier.padding(vertical = 8.dp),
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4f
                    )
                }
                is ArticleNode.Image -> {
                    ArticleImage(
                        url = node.url,
                        alt = node.alt,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                is ArticleNode.BlockQuote -> {
                    ArticleBlockQuote(
                        text = node.text,
                        onTextLayout = { textLayouts[index] = it }
                    )
                }
                is ArticleNode.CodeBlock -> {
                    ArticleCodeBlock(
                        text = node.text,
                        onTextLayout = { textLayouts[index] = it }
                    )
                }
                is ArticleNode.ListItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = PrimaryRed
                        )
                        Text(
                            text = node.text,
                            style = MaterialTheme.typography.bodyLarge,
                            onTextLayout = { textLayouts[index] = it },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is ArticleNode.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )
                }
                is ArticleNode.Spacer -> {
                    Spacer(modifier = Modifier.height(node.height))
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun NativeArticleView(
    article: PhoneArticleEntity,
    onOpenImportedArticle: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val parsedContent = remember(article.articleId, article.contentHash) {
        parseArticleContent(article.contentHtml ?: "")
    }
    
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        items(parsedContent) { node ->
            when (node) {
                is ArticleNode.Heading -> {
                    Text(
                        text = node.text,
                        style = when (node.level) {
                            1 -> MaterialTheme.typography.headlineLarge
                            2 -> MaterialTheme.typography.headlineMedium
                            3 -> MaterialTheme.typography.headlineSmall
                            else -> MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                is ArticleNode.Paragraph -> {
                    Text(
                        text = node.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4f
                    )
                }
                is ArticleNode.Image -> {
                    ArticleImage(
                        url = node.url,
                        alt = node.alt,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                is ArticleNode.BlockQuote -> {
                    ArticleBlockQuote(text = node.text)
                }
                is ArticleNode.CodeBlock -> {
                    ArticleCodeBlock(text = node.text)
                }
                is ArticleNode.ListItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = PrimaryRed
                        )
                        Text(
                            text = node.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is ArticleNode.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )
                }
                is ArticleNode.Spacer -> {
                    Spacer(modifier = Modifier.height(node.height))
                }
            }
        }
        
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun ArticleBlockQuote(
    text: String,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val lineWidth = 4.dp
    Text(
        text = text,
        onTextLayout = onTextLayout,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .drawBehind {
                val strokeWidth = lineWidth.toPx()
                drawLine(
                    color = lineColor,
                    start = Offset(strokeWidth / 2f, 0f),
                    end = Offset(strokeWidth / 2f, size.height),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
            .padding(start = 18.dp, end = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ArticleCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    val horizontalScrollState = rememberScrollState()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
        )
    ) {
        SelectionContainer {
            Text(
                text = text,
                onTextLayout = onTextLayout,
                modifier = Modifier
                    .horizontalScroll(horizontalScrollState)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.sp
                ),
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.28f,
                softWrap = false
            )
        }
    }
}

@Composable
private fun ArticleImage(
    url: String,
    alt: String,
    modifier: Modifier = Modifier
) {
    AppCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        AsyncImage(
            model = url,
            contentDescription = alt.takeIf { it.isNotBlank() },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth
        )
    }
}

@Composable
private fun PlainArticleView(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val paragraphs = remember(text) {
        text.split("\n").filter { it.isNotBlank() }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        items(paragraphs) { paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

// ==================== HTML 解析 ====================

private sealed class ArticleNode {
    data class Heading(val text: String, val level: Int) : ArticleNode()
    data class Paragraph(val text: String) : ArticleNode()
    data class Image(val url: String, val alt: String) : ArticleNode()
    data class BlockQuote(val text: String) : ArticleNode()
    data class CodeBlock(val text: String) : ArticleNode()
    data class ListItem(val text: String) : ArticleNode()
    object HorizontalRule : ArticleNode()
    data class Spacer(val height: Dp) : ArticleNode()
}

private data class VisibleArticleTextNodeWithLayout(
    val itemInfo: LazyListItemInfo,
    val nodeIndex: Int,
    val text: String,
    val layout: TextLayoutResult
)

private data class VisibleImportedTextChunkWithLayout(
    val itemInfo: LazyListItemInfo,
    val chunkIndex: Int,
    val text: String,
    val layout: TextLayoutResult
)

private data class ArticleTextRestoreTarget(
    val itemIndex: Int,
    val nodeIndex: Int,
    val byteOffsetInNode: Int
)

private data class ImportedTextByteRestoreTarget(
    val itemIndex: Int,
    val chunkIndex: Int,
    val byteOffsetInChunk: Int
)

private fun buildPlainArticleNodes(text: String): List<ArticleNode> {
    val nodes = mutableListOf<ArticleNode>()
    text.lineSequence().forEach { line ->
        appendSplitTextNodes(
            target = nodes,
            text = line.trim(),
            factory = ArticleNode::Paragraph
        )
    }
    return nodes.ifEmpty { listOf(ArticleNode.Paragraph(text.ifBlank { "暂无正文" })) }
}

private fun appendSplitTextNodes(
    target: MutableList<ArticleNode>,
    text: String,
    factory: (String) -> ArticleNode
) {
    if (text.isBlank()) return
    if (text.length <= MAX_ARTICLE_TEXT_NODE_CHARS) {
        target += factory(text)
        return
    }
    var start = 0
    while (start < text.length) {
        val end = (start + MAX_ARTICLE_TEXT_NODE_CHARS).coerceAtMost(text.length)
        val slice = text.substring(start, end).trim()
        if (slice.isNotEmpty()) {
            target += factory(slice)
        }
        start = end
    }
}

private fun articleNodeText(node: ArticleNode?): String? {
    return when (node) {
        is ArticleNode.Heading -> node.text
        is ArticleNode.Paragraph -> node.text
        is ArticleNode.BlockQuote -> node.text
        is ArticleNode.CodeBlock -> node.text
        is ArticleNode.ListItem -> node.text
        else -> null
    }?.takeIf { it.isNotBlank() }
}

private fun importedTextChunkKey(marker: String, chunkIndex: Int): String {
    return "$IMPORTED_TEXT_CHUNK_KEY_PREFIX$marker:$chunkIndex"
}

private fun importedTextChunkIndexFromKey(key: Any?, marker: String): Int? {
    val keyText = key as? String ?: return null
    val prefix = "$IMPORTED_TEXT_CHUNK_KEY_PREFIX$marker:"
    if (!keyText.startsWith(prefix)) return null
    return keyText.substring(prefix.length).toIntOrNull()
}

private fun calculateImportedTextByteReadingProgressFromLayout(
    listState: LazyListState,
    marker: String,
    byteLength: Long,
    chunkCount: Int,
    chunkTexts: Map<Int, String>,
    chunkLayouts: Map<Int, TextLayoutResult>,
    anchorOffsetPx: Int
): Float? {
    if (chunkCount <= 0 || byteLength <= 0L) return null
    val layoutInfo = listState.layoutInfo
    if (layoutInfo.visibleItemsInfo.isNotEmpty() && !listState.canScrollForward) return 1f
    val normalizedAnchorOffsetPx = anchorOffsetPx.coerceAtLeast(0)
    val visibleChunk = layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { itemInfo ->
        if (itemInfo.offset + itemInfo.size <= normalizedAnchorOffsetPx) {
            return@firstNotNullOfOrNull null
        }
        val chunkIndex = importedTextChunkIndexFromKey(itemInfo.key, marker)
            ?: return@firstNotNullOfOrNull null
        if (chunkIndex !in 0 until chunkCount) return@firstNotNullOfOrNull null
        val text = chunkTexts[chunkIndex] ?: return@firstNotNullOfOrNull null
        val layout = chunkLayouts[chunkIndex] ?: return@firstNotNullOfOrNull null
        if (layout.lineCount <= 0) return@firstNotNullOfOrNull null
        VisibleImportedTextChunkWithLayout(
            itemInfo = itemInfo,
            chunkIndex = chunkIndex,
            text = text,
            layout = layout
        )
    } ?: return null
    val scrolledInItemPx = (normalizedAnchorOffsetPx - visibleChunk.itemInfo.offset)
        .coerceIn(0, visibleChunk.itemInfo.size.coerceAtLeast(0))
    val textTopPaddingPx = articleTextTopInsetPx(
        itemInfo = visibleChunk.itemInfo,
        layout = visibleChunk.layout
    )
    val textY = (scrolledInItemPx - textTopPaddingPx)
        .coerceAtLeast(0)
        .toFloat()
    val lineIndex = visibleChunk.layout
        .getLineForVerticalPosition(textY)
        .coerceIn(0, visibleChunk.layout.lineCount - 1)
    val charOffset = visibleChunk.layout
        .getLineStart(lineIndex)
        .coerceIn(0, visibleChunk.text.length)
    val byteOffsetInChunk = utf8ByteCountBeforeCharOffset(
        text = visibleChunk.text,
        charOffset = charOffset
    )
    val absoluteByte = (visibleChunk.chunkIndex.toLong() * ARTICLE_TEXT_CHUNK_BYTES.toLong() + byteOffsetInChunk)
        .coerceIn(0L, byteLength)
    return (absoluteByte.toDouble() / byteLength.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

private fun calculateArticleTextReadingProgressFromLayout(
    listState: LazyListState,
    nodes: List<ArticleNode>,
    textLayouts: Map<Int, TextLayoutResult>,
    anchorOffsetPx: Int
): Float? {
    val totalTextBytes = nodes.sumOf { node -> articleNodeText(node)?.let(::utf8ByteCount) ?: 0 }
    if (totalTextBytes <= 0) return null
    val layoutInfo = listState.layoutInfo
    if (layoutInfo.visibleItemsInfo.isNotEmpty() && !listState.canScrollForward) return 1f
    val normalizedAnchorOffsetPx = anchorOffsetPx.coerceAtLeast(0)
    val visibleNode = layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { itemInfo ->
        if (itemInfo.offset + itemInfo.size <= normalizedAnchorOffsetPx) {
            return@firstNotNullOfOrNull null
        }
        val text = articleNodeText(nodes.getOrNull(itemInfo.index))
            ?: return@firstNotNullOfOrNull null
        val layout = textLayouts[itemInfo.index] ?: return@firstNotNullOfOrNull null
        if (layout.lineCount <= 0) return@firstNotNullOfOrNull null
        VisibleArticleTextNodeWithLayout(
            itemInfo = itemInfo,
            nodeIndex = itemInfo.index,
            text = text,
            layout = layout
        )
    } ?: return null
    val bytesBeforeNode = nodes.asSequence()
        .take(visibleNode.nodeIndex)
        .sumOf { node -> articleNodeText(node)?.let(::utf8ByteCount) ?: 0 }
    val scrolledInItemPx = (normalizedAnchorOffsetPx - visibleNode.itemInfo.offset)
        .coerceIn(0, visibleNode.itemInfo.size.coerceAtLeast(0))
    val textTopPaddingPx = articleTextTopInsetPx(
        itemInfo = visibleNode.itemInfo,
        layout = visibleNode.layout
    )
    val textY = (scrolledInItemPx - textTopPaddingPx)
        .coerceAtLeast(0)
        .toFloat()
    val lineIndex = visibleNode.layout
        .getLineForVerticalPosition(textY)
        .coerceIn(0, visibleNode.layout.lineCount - 1)
    val charOffset = visibleNode.layout
        .getLineStart(lineIndex)
        .coerceIn(0, visibleNode.text.length)
    val byteOffsetInNode = utf8ByteCountBeforeCharOffset(visibleNode.text, charOffset)
    val absoluteByte = (bytesBeforeNode + byteOffsetInNode)
        .coerceIn(0, totalTextBytes)
    return (absoluteByte.toDouble() / totalTextBytes.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

private fun articleTextRestoreTarget(
    progress: Float,
    nodes: List<ArticleNode>
): ArticleTextRestoreTarget? {
    val textByteCounts = nodes.map { node -> articleNodeText(node)?.let(::utf8ByteCount) ?: 0 }
    val totalTextBytes = textByteCounts.sum()
    if (totalTextBytes <= 0) return null
    val targetByte = (totalTextBytes.toDouble() * progress.coerceIn(0f, 1f).toDouble())
        .roundToInt()
        .coerceIn(0, (totalTextBytes - 1).coerceAtLeast(0))
    var consumed = 0
    textByteCounts.forEachIndexed { index, byteCount ->
        if (byteCount <= 0) return@forEachIndexed
        val next = consumed + byteCount
        if (targetByte < next) {
            return ArticleTextRestoreTarget(
                itemIndex = index,
                nodeIndex = index,
                byteOffsetInNode = (targetByte - consumed).coerceAtLeast(0)
            )
        }
        consumed = next
    }
    val lastTextIndex = textByteCounts.indexOfLast { it > 0 }
    if (lastTextIndex < 0) return null
    return ArticleTextRestoreTarget(
        itemIndex = lastTextIndex,
        nodeIndex = lastTextIndex,
        byteOffsetInNode = (textByteCounts[lastTextIndex] - 1).coerceAtLeast(0)
    )
}

private fun importedTextByteRestoreTarget(
    progress: Float,
    byteLength: Long,
    chunkCount: Int,
    chunkBytes: Int
): ImportedTextByteRestoreTarget {
    if (byteLength <= 0L || chunkCount <= 0 || chunkBytes <= 0) {
        return ImportedTextByteRestoreTarget(
            itemIndex = 0,
            chunkIndex = 0,
            byteOffsetInChunk = 0
        )
    }
    val maxByte = (byteLength - 1L).coerceAtLeast(0L)
    val absoluteByte = (byteLength.toDouble() * progress.coerceIn(0f, 1f).toDouble())
        .roundToLong()
        .coerceIn(0L, maxByte)
    val chunkIndex = (absoluteByte / chunkBytes.toLong())
        .toInt()
        .coerceIn(0, chunkCount - 1)
    val byteOffsetInChunk = (absoluteByte - chunkIndex.toLong() * chunkBytes.toLong())
        .toInt()
        .coerceAtLeast(0)
    return ImportedTextByteRestoreTarget(
        itemIndex = chunkIndex,
        chunkIndex = chunkIndex,
        byteOffsetInChunk = byteOffsetInChunk
    )
}

private fun importedTextRestoreVisualOffsetPx(
    restoreTarget: ImportedTextByteRestoreTarget,
    text: String,
    layout: TextLayoutResult,
    itemInfo: LazyListItemInfo,
    anchorOffsetPx: Int
): Int {
    if (layout.lineCount <= 0) return 0
    val charOffset = utf8CharOffsetForByteOffset(
        text = text,
        byteOffset = restoreTarget.byteOffsetInChunk
    ).coerceIn(0, text.length)
    val lineIndex = layout
        .getLineForOffset(charOffset)
        .coerceIn(0, layout.lineCount - 1)
    val textTopPaddingPx = articleTextTopInsetPx(
        itemInfo = itemInfo,
        layout = layout
    )
    return (textTopPaddingPx + layout.getLineTop(lineIndex))
        .roundToInt()
        .coerceAtLeast(0) - anchorOffsetPx.coerceAtLeast(0)
}

private fun articleTextRestoreVisualOffsetPx(
    restoreTarget: ArticleTextRestoreTarget,
    text: String,
    layout: TextLayoutResult,
    itemInfo: LazyListItemInfo,
    anchorOffsetPx: Int
): Int {
    if (layout.lineCount <= 0) return 0
    val charOffset = utf8CharOffsetForByteOffset(
        text = text,
        byteOffset = restoreTarget.byteOffsetInNode
    ).coerceIn(0, text.length)
    val lineIndex = layout
        .getLineForOffset(charOffset)
        .coerceIn(0, layout.lineCount - 1)
    val textTopPaddingPx = articleTextTopInsetPx(
        itemInfo = itemInfo,
        layout = layout
    )
    return (textTopPaddingPx + layout.getLineTop(lineIndex))
        .roundToInt()
        .coerceAtLeast(0) - anchorOffsetPx.coerceAtLeast(0)
}

private fun articleTextTopInsetPx(
    itemInfo: LazyListItemInfo,
    layout: TextLayoutResult
): Int {
    val nonTextVerticalSpacePx = (itemInfo.size - layout.size.height)
        .coerceAtLeast(0)
    return (nonTextVerticalSpacePx / 2f).roundToInt()
}

private fun utf8ByteCount(text: String): Int {
    return utf8ByteCountBeforeCharOffset(text, text.length)
}

private fun utf8ByteCountBeforeCharOffset(text: String, charOffset: Int): Int {
    val targetCharOffset = charOffset.coerceIn(0, text.length)
    var byteCount = 0
    var index = 0
    while (index < targetCharOffset) {
        val codePoint = Character.codePointAt(text, index)
        byteCount += utf8ByteCountForCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
    return byteCount
}

private fun utf8CharOffsetForByteOffset(text: String, byteOffset: Int): Int {
    if (byteOffset <= 0) return 0
    var byteCount = 0
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        val codePointByteCount = utf8ByteCountForCodePoint(codePoint)
        if (byteCount + codePointByteCount > byteOffset) return index
        byteCount += codePointByteCount
        index += Character.charCount(codePoint)
    }
    return text.length
}

private fun utf8ByteCountForCodePoint(codePoint: Int): Int {
    return when {
        codePoint <= 0x7F -> 1
        codePoint <= 0x7FF -> 2
        codePoint <= 0xFFFF -> 3
        else -> 4
    }
}

private fun parseArticleContent(html: String): List<ArticleNode> {
    val result = mutableListOf<ArticleNode>()
    if (html.isBlank()) return result
    
    val doc = Jsoup.parseBodyFragment(html)
    doc.outputSettings().prettyPrint(false)
    
    val body = doc.body()
    
    val children = body.children()
    if (children.isEmpty()) {
        // 如果没有结构化内容，把整个文本作为一个段落
        val text = body.text().trim()
        if (text.isNotBlank()) {
            result.add(ArticleNode.Paragraph(text))
        }
        return result
    }
    
    for (element in children) {
        when (element.tagName()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.Heading(text, element.tagName()[1].digitToInt()))
                }
            }
            "p" -> {
                val text = extractTextWithInlineFormatting(element)
                if (text.isNotBlank()) {
                    result.add(ArticleNode.Paragraph(text))
                }
            }
            "blockquote" -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.BlockQuote(text))
                }
            }
            "pre" -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.CodeBlock(text))
                }
            }
            "code" -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.CodeBlock(text))
                }
            }
            "img" -> {
                val src = element.attr("src").trim()
                if (src.isNotBlank()) {
                    result.add(ArticleNode.Image(src, element.attr("alt")))
                }
            }
            "figure" -> {
                val img = element.selectFirst("img")
                if (img != null) {
                    val src = img.attr("src").trim()
                    if (src.isNotBlank()) {
                        result.add(ArticleNode.Image(src, img.attr("alt")))
                    }
                }
                val caption = element.selectFirst("figcaption")
                if (caption != null) {
                    val text = caption.text().trim()
                    if (text.isNotBlank()) {
                        result.add(ArticleNode.Paragraph(text))
                    }
                }
            }
            "ul", "ol" -> {
                element.select("li").forEach { li ->
                    val text = li.text().trim()
                    if (text.isNotBlank()) {
                        result.add(ArticleNode.ListItem(text))
                    }
                }
            }
            "hr", "br" -> {
                result.add(ArticleNode.HorizontalRule)
            }
            "div" -> {
                // 递归处理 div 的内容
                val divChildren = element.children()
                if (divChildren.isEmpty()) {
                    val text = element.text().trim()
                    if (text.isNotBlank()) {
                        result.add(ArticleNode.Paragraph(text))
                    }
                } else {
                    for (child in divChildren) {
                        parseElement(child, result)
                    }
                }
            }
            "article", "section", "main" -> {
                for (child in element.children()) {
                    parseElement(child, result)
                }
            }
            else -> {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.Paragraph(text))
                }
            }
        }
    }
    
    return result
}

private fun parseElement(element: Element, result: MutableList<ArticleNode>) {
    when (element.tagName()) {
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val text = element.text().trim()
            if (text.isNotBlank()) {
                result.add(ArticleNode.Heading(text, element.tagName()[1].digitToInt()))
            }
        }
        "p" -> {
            val text = extractTextWithInlineFormatting(element)
            if (text.isNotBlank()) {
                result.add(ArticleNode.Paragraph(text))
            }
        }
        "blockquote" -> {
            val text = element.text().trim()
            if (text.isNotBlank()) {
                result.add(ArticleNode.BlockQuote(text))
            }
        }
        "pre", "code" -> {
            val text = element.text().trim()
            if (text.isNotBlank()) {
                result.add(ArticleNode.CodeBlock(text))
            }
        }
        "img" -> {
            val src = element.attr("src").trim()
            if (src.isNotBlank()) {
                result.add(ArticleNode.Image(src, element.attr("alt")))
            }
        }
        "figure" -> {
            val img = element.selectFirst("img")
            if (img != null) {
                val src = img.attr("src").trim()
                if (src.isNotBlank()) {
                    result.add(ArticleNode.Image(src, img.attr("alt")))
                }
            }
            val caption = element.selectFirst("figcaption")
            if (caption != null) {
                val text = caption.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.Paragraph(text))
                }
            }
        }
        "ul", "ol" -> {
            element.select("li").forEach { li ->
                val text = li.text().trim()
                if (text.isNotBlank()) {
                    result.add(ArticleNode.ListItem(text))
                }
            }
        }
        "hr" -> {
            result.add(ArticleNode.HorizontalRule)
        }
        "div", "article", "section" -> {
            for (child in element.children()) {
                parseElement(child, result)
            }
        }
        else -> {
            val text = element.text().trim()
            if (text.isNotBlank()) {
                result.add(ArticleNode.Paragraph(text))
            }
        }
    }
}

/**
 * 提取包含内联格式（如 <strong>, <em>, <a>）的文本
 */
private fun extractTextWithInlineFormatting(element: Element): String {
    val builder = StringBuilder()
    for (node in element.childNodes()) {
        when (node) {
            is TextNode -> builder.append(node.text())
            is Element -> {
                when (node.tagName()) {
                    "br" -> builder.append("\n")
                    "strong", "b" -> builder.append(node.text())
                    "em", "i" -> builder.append(node.text())
                    "a" -> builder.append(node.text())
                    "span" -> builder.append(node.text())
                    "code" -> builder.append(node.text())
                    else -> builder.append(node.text())
                }
            }
        }
    }
    return builder.toString().trim()
}

private const val ARTICLE_READING_PROGRESS_LAYOUT_TIMEOUT_MS = 800L
private const val ARTICLE_RESTORE_OFFSET_TIMEOUT_MS = 3_000L
private const val ARTICLE_READING_PROGRESS_SAMPLE_MS = 500L
private const val MAX_ARTICLE_TEXT_NODE_CHARS = 2_000
private const val IMPORTED_TEXT_CHUNK_KEY_PREFIX = "importedText:"
private const val READER_CHROME_SNAP_ANIMATION_MS = 140
