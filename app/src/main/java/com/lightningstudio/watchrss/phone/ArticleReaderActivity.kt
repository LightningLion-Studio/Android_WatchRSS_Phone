package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.ai.PhoneAiSummaryResult
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.local.ARTICLE_TEXT_CHUNK_BYTES
import com.lightningstudio.watchrss.phone.data.local.isArticleContentMarker
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.data.repo.PhoneImportedTextReader
import com.lightningstudio.watchrss.phone.data.reader.ReaderTypographyRole
import com.lightningstudio.watchrss.phone.ui.reader.ProvideReaderPreset
import com.lightningstudio.watchrss.phone.ui.reader.LocalReaderPresetRuntime
import com.lightningstudio.watchrss.phone.ui.reader.ReaderBackgroundSurface
import com.lightningstudio.watchrss.phone.ui.reader.ReaderTextRole
import com.lightningstudio.watchrss.phone.ui.reader.readerTextStyle
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.AdaptiveWidthClass
import com.lightningstudio.watchrss.phone.ui.PredictiveBackSurface
import com.lightningstudio.watchrss.phone.ui.adaptiveContentWidth
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.ui.theme.AppPrimaryCard
import com.lightningstudio.watchrss.phone.ui.theme.PrimaryRed
import com.lightningstudio.watchrss.phone.ui.theme.roundedClickable
import com.kyant.backdrop.*
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.effects.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
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
        val readerPresetRepository =
            (application as PhoneCompanionApplication).container.readerPresetRepository

        setContent {
            WatchRssPhoneTheme {
                ProvideReaderPreset(readerPresetRepository) {
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
internal fun ArticleReaderScreen(
    article: PhoneArticleEntity?,
    importedTextReader: PhoneImportedTextReader?,
    invalidArticleId: Boolean,
    onLoadImportedTextChunk: suspend (String, Int) -> String?,
    onSaveReadingProgress: suspend (Float) -> Unit,
    onBack: () -> Unit,
    onOpenImportedArticle: (String) -> Unit,
    onOpenOriginal: (String) -> Unit,
    embedded: Boolean = false,
    embeddedFullscreen: Boolean = false,
    onOpenFullscreen: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    contentReady: Boolean = true,
    contentNodesCache: MutableMap<ArticleContentNodesKey, ArticleContentNodesSnapshot>? = null,
    positionAlreadyRestored: Boolean = false,
    onPositionRestored: (String) -> Unit = {}
) {
    if (invalidArticleId) {
        ReaderBackSurface(
            enabled = !embedded,
            onBeforeBack = {},
            onBack = onBack
        ) {
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
        }
        return
    }

    val safeArticle = article
    if (safeArticle == null) {
        ReaderBackSurface(
            enabled = !embedded,
            onBeforeBack = {},
            onBack = onBack
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ReaderContentLoadingPlaceholder(modifier = Modifier.fillMaxWidth())
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        }
        return
    }

    val hasFileBackedImportedText = ImportedContentIds.isImportedTextArticleUrl(safeArticle.url) &&
        isArticleContentMarker(safeArticle.contentText)
    val waitingForImportedTextReader = hasFileBackedImportedText && importedTextReader == null
    val useImportedTextChunks = importedTextReader != null
    val shouldBuildContentNodes = contentReady && !useImportedTextChunks && !waitingForImportedTextReader
    val contentNodesKey = ArticleContentNodesKey(
        articleId = safeArticle.articleId,
        contentHash = safeArticle.contentHash,
        contentHtml = safeArticle.contentHtml,
        contentText = safeArticle.contentText,
        excerpt = safeArticle.excerpt,
        url = safeArticle.url
    )
    val cachedContentNodesSnapshot = if (shouldBuildContentNodes) {
        contentNodesCache?.get(contentNodesKey)?.takeIf { it.key == contentNodesKey }
    } else {
        null
    }
    val contentNodesSnapshot by produceState<ArticleContentNodesSnapshot?>(
        initialValue = cachedContentNodesSnapshot,
        contentNodesKey,
        contentReady,
        shouldBuildContentNodes,
        contentNodesCache
    ) {
        if (!contentReady) {
            value = null
            return@produceState
        }
        if (!shouldBuildContentNodes) {
            value = ArticleContentNodesSnapshot(
                key = contentNodesKey,
                nodes = emptyList()
            )
            return@produceState
        }
        contentNodesCache?.get(contentNodesKey)?.takeIf { it.key == contentNodesKey }?.let { snapshot ->
            value = snapshot
            return@produceState
        }
        value = null
        val nodes = withContext(Dispatchers.Default) {
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
        val snapshot = ArticleContentNodesSnapshot(
            key = contentNodesKey,
            nodes = nodes
        )
        contentNodesCache?.set(contentNodesKey, snapshot)
        value = snapshot
    }
    val currentContentNodes = if (shouldBuildContentNodes) {
        contentNodesSnapshot
            ?.takeIf { it.key == contentNodesKey }
            ?.nodes
    } else {
        emptyList()
    }
    val contentNodesReady = !shouldBuildContentNodes || currentContentNodes != null
    val contentNodes = currentContentNodes.orEmpty()
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
        val readerProgressAnchorOffsetPx = topBarHeightPx.coerceAtLeast(0)
        var hasRestoredPosition by remember(safeArticle.articleId) {
            mutableStateOf(positionAlreadyRestored)
        }
        var pendingRestoreProgress by remember(safeArticle.articleId) {
            mutableStateOf<Float?>(
                if (positionAlreadyRestored) {
                    null
                } else {
                    safeArticle.readingProgress.coerceIn(0f, 1f)
                }
            )
        }
        var pendingArticleRestore by remember(safeArticle.articleId) {
            mutableStateOf<ArticleRestoreTarget?>(null)
        }
        var pendingImportedTextRestore by remember(safeArticle.articleId, importedTextReader?.marker) {
            mutableStateOf<ImportedTextByteRestoreTarget?>(null)
        }
        var previewImage by remember(safeArticle.articleId) {
            mutableStateOf<ArticlePreviewImage?>(null)
        }
        var previewDismissRequests by remember(safeArticle.articleId) {
            mutableStateOf(0)
        }
        var lastSavedProgress by remember(safeArticle.articleId) { mutableStateOf(-1f) }
        var lastProgressSavedAt by remember(safeArticle.articleId) { mutableStateOf(0L) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val onSaveReadingProgressState = rememberUpdatedState(onSaveReadingProgress)
        val onBackState = rememberUpdatedState(onBack)
        val onPositionRestoredState = rememberUpdatedState(onPositionRestored)
        val context = androidx.compose.ui.platform.LocalContext.current
        val appContainer = (context.applicationContext as PhoneCompanionApplication).container
        val autoScrollPreferences = remember {
            context.getSharedPreferences(AUTO_SCROLL_PREFERENCES, Context.MODE_PRIVATE)
        }
        var autoScrollEnabled by remember {
            mutableStateOf(autoScrollPreferences.getBoolean(AUTO_SCROLL_ENABLED_KEY, false))
        }
        var autoScrollLinesPerSecond by remember {
            mutableStateOf(
                autoScrollPreferences
                    .getFloat(AUTO_SCROLL_LINES_PER_SECOND_KEY, AUTO_SCROLL_DEFAULT_LINES_PER_SECOND)
                    .coerceIn(AUTO_SCROLL_MIN_LINES_PER_SECOND, AUTO_SCROLL_MAX_LINES_PER_SECOND)
            )
        }
        var autoScrollPaused by remember(safeArticle.articleId) { mutableStateOf(false) }
        var showAutoScrollSettings by remember { mutableStateOf(false) }
        var autoScrollFeedbackPlaying by remember { mutableStateOf<Boolean?>(null) }
        val bodyLineHeightPx = with(density) {
            (
                LocalReaderPresetRuntime.current.preset.body.fontSizeSp.sp *
                    LocalReaderPresetRuntime.current.preset.body.lineHeightEm
                ).toPx()
        }.coerceAtLeast(1f)

        fun saveAutoScrollSettings() {
            autoScrollPreferences.edit()
                .putBoolean(AUTO_SCROLL_ENABLED_KEY, autoScrollEnabled)
                .putFloat(AUTO_SCROLL_LINES_PER_SECOND_KEY, autoScrollLinesPerSecond)
                .apply()
        }

        fun setAutoScrollPaused(paused: Boolean, showFeedback: Boolean = true) {
            autoScrollPaused = paused
            if (showFeedback) autoScrollFeedbackPlaying = !paused
        }

        LaunchedEffect(autoScrollFeedbackPlaying) {
            if (autoScrollFeedbackPlaying != null) {
                delay(AUTO_SCROLL_FEEDBACK_DURATION_MS)
                autoScrollFeedbackPlaying = null
            }
        }

        LaunchedEffect(
            autoScrollEnabled,
            autoScrollPaused,
            hasRestoredPosition,
            contentReady,
            bodyLineHeightPx,
            autoScrollLinesPerSecond
        ) {
            if (!autoScrollEnabled || autoScrollPaused || !hasRestoredPosition || !contentReady) {
                return@LaunchedEffect
            }
            var previousFrameNanos = 0L
            while (isActive) {
                val frameNanos = withFrameNanos { it }
                if (previousFrameNanos != 0L) {
                    val elapsedSeconds = (frameNanos - previousFrameNanos) / 1_000_000_000f
                    val deltaPx = autoScrollLinesPerSecond * bodyLineHeightPx * elapsedSeconds
                    if (deltaPx > 0f && listState.scrollBy(deltaPx) == 0f) {
                        setAutoScrollPaused(paused = true, showFeedback = false)
                    }
                }
                previousFrameNanos = frameNanos
            }
        }
        val aiConfig = remember(safeArticle.articleId) { appContainer.aiSettingsStore.config() }
        val aiScope = rememberCoroutineScope()
        var aiJob by remember(safeArticle.articleId) { mutableStateOf<Job?>(null) }
        var aiResult by remember(safeArticle.articleId) {
            mutableStateOf<PhoneAiSummaryResult?>(null)
        }
        var aiError by remember(safeArticle.articleId) { mutableStateOf<String?>(null) }
        var aiLoading by remember(safeArticle.articleId) { mutableStateOf(false) }
        var showAiSummary by remember(safeArticle.articleId) { mutableStateOf(false) }

        suspend fun summaryArticle(): PhoneArticleEntity {
            val reader = importedTextReader ?: return safeArticle
            val text = buildString {
                for (index in 0 until reader.chunkCount) {
                    if (length >= 160_000) break
                    append(onLoadImportedTextChunk(reader.marker, index).orEmpty())
                    append('\n')
                }
            }
            return safeArticle.copy(contentHtml = null, contentText = text)
        }

        fun startAiSummary() {
            showAiSummary = true
            aiJob?.cancel()
            aiJob = aiScope.launch {
                aiLoading = true
                aiError = null
                aiResult = runCatching {
                    appContainer.aiSummaryService.summarize(summaryArticle())
                }.fold(
                    onSuccess = { it },
                    onFailure = {
                        if (it is CancellationException) return@launch
                        aiError = it.message ?: "总结失败"
                        null
                    }
                )
                aiLoading = false
            }
        }

        LaunchedEffect(safeArticle.articleId, aiConfig.autoSummarize, aiConfig.enabled) {
            aiJob?.cancel()
            aiResult = null
            aiError = null
            if (aiConfig.enabled && aiConfig.autoSummarize) startAiSummary()
        }
        DisposableEffect(safeArticle.articleId) {
            onDispose { aiJob?.cancel() }
        }

        LaunchedEffect(safeArticle.articleId, hasRestoredPosition) {
            if (hasRestoredPosition) {
                onPositionRestoredState.value(safeArticle.articleId)
            }
        }

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
                    anchorOffsetPx = readerProgressAnchorOffsetPx
                )
            } ?: calculateArticleReadingProgressFromLayout(
                listState = listState,
                nodes = contentNodes,
                textLayouts = textLayouts,
                anchorOffsetPx = readerProgressAnchorOffsetPx
            )
        }

        suspend fun awaitReadingProgress(waitForLayout: Boolean): Float? {
            freshReadingProgress()?.let { return it }
            if (!waitForLayout) return null
            return withTimeoutOrNull(ARTICLE_READING_PROGRESS_LAYOUT_TIMEOUT_MS) {
                snapshotFlow { freshReadingProgress() }
                    .filterNotNull()
                    .first()
            }
        }

        suspend fun saveCurrentReadingProgress(
            force: Boolean,
            waitForLayout: Boolean = true
        ): Boolean {
            if (!hasRestoredPosition) return false
            val progress = awaitReadingProgress(waitForLayout) ?: return false
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

        LaunchedEffect(
            pendingRestoreProgress,
            contentNodes,
            contentNodesReady,
            topBarHeight,
            importedTextReader
        ) {
            val progress = pendingRestoreProgress ?: return@LaunchedEffect
            if (topBarHeight == 0.dp) return@LaunchedEffect
            if (!contentReady) return@LaunchedEffect
            if (waitingForImportedTextReader) return@LaunchedEffect
            if (!useImportedTextChunks && !contentNodesReady) return@LaunchedEffect
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
            val restoreTarget = articleRestoreTarget(
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
            val targetIndex = restoreTarget.itemIndex.coerceIn(0, contentNodes.lastIndex)
            listState.scrollToItem(targetIndex)
            pendingArticleRestore = when (restoreTarget) {
                is ArticleRestoreTarget.Text -> restoreTarget.copy(itemIndex = targetIndex)
                is ArticleRestoreTarget.Image -> restoreTarget.copy(itemIndex = targetIndex)
            }
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
                            anchorOffsetPx = readerProgressAnchorOffsetPx
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

        LaunchedEffect(pendingArticleRestore) {
            val restoreTarget = pendingArticleRestore ?: return@LaunchedEffect
            val offsetPx = withTimeoutOrNull(ARTICLE_RESTORE_OFFSET_TIMEOUT_MS) {
                snapshotFlow {
                    val itemInfo = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == restoreTarget.itemIndex }
                    when (restoreTarget) {
                        is ArticleRestoreTarget.Text -> {
                            val text = articleNodeText(contentNodes.getOrNull(restoreTarget.nodeIndex))
                            val layout = textLayouts[restoreTarget.nodeIndex]
                            if (text == null || layout == null || itemInfo == null) {
                                null
                            } else {
                                articleTextRestoreVisualOffsetPx(
                                    restoreTarget = restoreTarget,
                                    text = text,
                                    layout = layout,
                                    itemInfo = itemInfo,
                                    anchorOffsetPx = readerProgressAnchorOffsetPx
                                )
                            }
                        }
                        is ArticleRestoreTarget.Image -> {
                            if (itemInfo == null) {
                                null
                            } else {
                                articleImageRestoreVisualOffsetPx(
                                    restoreTarget = restoreTarget,
                                    itemInfo = itemInfo,
                                    anchorOffsetPx = readerProgressAnchorOffsetPx
                                )
                            }
                        }
                    }
                }
                    .filterNotNull()
                    .first()
            }
            if (offsetPx != null) {
                listState.scrollToItem(restoreTarget.itemIndex, offsetPx)
            }
            pendingArticleRestore = null
            hasRestoredPosition = true
        }

        LaunchedEffect(listState, contentNodes, contentNodesReady) {
            snapshotFlow { freshReadingProgress() }
                .filterNotNull()
                .distinctUntilChanged()
                .sample(ARTICLE_READING_PROGRESS_SAMPLE_MS)
                .collect { progress ->
                    if (hasRestoredPosition) {
                        saveCurrentReadingProgress(force = false, waitForLayout = false)
                    }
                }
        }

        DisposableEffect(lifecycleOwner, safeArticle.articleId) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    lifecycleOwner.lifecycleScope.launch {
                        saveCurrentReadingProgress(force = true, waitForLayout = false)
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycleScope.launch {
                    saveCurrentReadingProgress(force = true, waitForLayout = false)
                }
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        fun handleBack() {
            if (previewImage != null) {
                previewDismissRequests += 1
                return
            }
            lifecycleOwner.lifecycleScope.launch {
                saveCurrentReadingProgress(force = true, waitForLayout = false)
            }
            onBackState.value()
        }

        ReaderBackSurface(
            enabled = previewImage == null && !embedded,
            onBeforeBack = {
                saveCurrentReadingProgress(force = true, waitForLayout = false)
            },
            onBack = onBackState.value
        ) {
            val readerPreset = LocalReaderPresetRuntime.current.preset
            // The chrome is part of the reader, not the app shell.  Derive both
            // its tint and its controls from the active reader preset so a light
            // book stays light (and a dark one stays dark) independently of the
            // phone's Material theme.
            val readerBackgroundColor = Color(readerPreset.background.colorArgb)
            val readerControlColor = Color(readerPreset.body.colorArgb)
            val readerChromeTint = if (readerControlColor.luminance() > 0.5f) {
                Color.White
            } else {
                Color.Black
            }
            val readerChromeSurfaceAlpha = if (readerControlColor.luminance() > 0.5f) {
                0.18f
            } else {
                0.12f
            }
            val backdrop = rememberLayerBackdrop {
                // The reader background lives outside this layer when it is an
                // image/video.  Seed the capture with the preset base color so
                // the bar never falls back to the app theme while that asset is
                // loading or unavailable.
                drawRect(readerBackgroundColor)
                drawContent()
            }

            AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
            // 内容区域 - 使用原生 Compose 渲染
            Box(
                modifier = Modifier
                    .layerBackdrop(backdrop)
                    .fillMaxSize()
                    .nestedScroll(readerChromeNestedScrollConnection)
                    .clipToBounds()
            ) {
                val readerHorizontalPadding = when (windowInfo.widthClass) {
                    AdaptiveWidthClass.Compact -> 20.dp
                    AdaptiveWidthClass.Medium -> 24.dp
                    AdaptiveWidthClass.Expanded -> 28.dp
                }
                val readerContentPadding = PaddingValues(
                    top = topBarHeight + 20.dp,
                    start = readerHorizontalPadding,
                    end = readerHorizontalPadding,
                    bottom = 20.dp
                )
                val autoScrollTapModifier = if (autoScrollEnabled) {
                    Modifier.pointerInput(autoScrollPaused) {
                        detectTapGestures {
                            setAutoScrollPaused(paused = !autoScrollPaused)
                        }
                    }
                } else {
                    Modifier
                }
                val reader = importedTextReader
                val hideContentUntilRestore = safeArticle.readingProgress > ARTICLE_RESTORE_HIDE_PROGRESS_EPSILON &&
                    !hasRestoredPosition
                fun Modifier.restoreVisibility(): Modifier {
                    return if (hideContentUntilRestore) {
                        graphicsLayer { alpha = 0f }
                    } else {
                        this
                    }
                }
                @Composable
                fun RestoreLoadingOverlay() {
                    ReaderContentLoadingPlaceholder(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(readerContentPadding)
                    )
                }
                when {
                    !contentReady -> {
                        AdaptiveContentFrame(
                            windowInfo = windowInfo,
                            mediumMaxWidth = 720.dp,
                            expandedMaxWidth = 760.dp
                        ) {
                            ReaderContentLoadingPlaceholder(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(readerContentPadding)
                            )
                        }
                    }
                    reader != null -> {
                        AdaptiveContentFrame(
                            windowInfo = windowInfo,
                            mediumMaxWidth = 720.dp,
                            expandedMaxWidth = 760.dp
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                ImportedTextChunkContentView(
                                    reader = reader,
                                    listState = listState,
                                    chunkTexts = importedTextChunkTexts,
                                    chunkLayouts = importedTextChunkLayouts,
                                    onLoadImportedTextChunk = onLoadImportedTextChunk,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .restoreVisibility()
                                        .then(autoScrollTapModifier),
                                    contentPadding = readerContentPadding
                                )
                                if (hideContentUntilRestore) {
                                    RestoreLoadingOverlay()
                                }
                            }
                        }
                    }
                    waitingForImportedTextReader -> {
                        AdaptiveContentFrame(
                            windowInfo = windowInfo,
                            mediumMaxWidth = 720.dp,
                            expandedMaxWidth = 760.dp
                        ) {
                            ReaderContentLoadingPlaceholder(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(readerContentPadding)
                            )
                        }
                    }
                    !contentNodesReady -> {
                        AdaptiveContentFrame(
                            windowInfo = windowInfo,
                            mediumMaxWidth = 720.dp,
                            expandedMaxWidth = 760.dp
                        ) {
                            ReaderContentLoadingPlaceholder(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(readerContentPadding)
                            )
                        }
                    }
                    else -> {
                        AdaptiveContentFrame(
                            windowInfo = windowInfo,
                            mediumMaxWidth = 720.dp,
                            expandedMaxWidth = 760.dp
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                ArticleReaderContentView(
                                    nodes = contentNodes,
                                    listState = listState,
                                    textLayouts = textLayouts,
                                    onPreviewImage = { previewImage = it },
                                    previewSourceNodeIndex = previewImage?.sourceNodeIndex,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .restoreVisibility()
                                        .then(autoScrollTapModifier),
                                    contentPadding = readerContentPadding
                                )
                                if (hideContentUntilRestore) {
                                    RestoreLoadingOverlay()
                                }
                            }
                        }
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
                        backdrop = backdrop,
                        tint = readerChromeTint,
                        surfaceAlpha = readerChromeSurfaceAlpha
                    )
                    .padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    ReaderTopContent(
                        article = safeArticle,
                        modifier = Modifier
                            .statusBarsPadding()
                            .adaptiveContentWidth(
                                windowInfo = windowInfo,
                                mediumMaxWidth = 720.dp,
                                expandedMaxWidth = 760.dp
                            )
                            .padding(horizontal = 16.dp)
                            .padding(top = 12.dp, bottom = 12.dp)
                    )
                }
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
                        backdrop = backdrop,
                        tint = readerChromeTint,
                        surfaceAlpha = readerChromeSurfaceAlpha
                    )
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .adaptiveContentWidth(
                                windowInfo = windowInfo,
                                mediumMaxWidth = 720.dp,
                                expandedMaxWidth = 760.dp
                            )
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompositionLocalProvider(LocalContentColor provides readerControlColor) {
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
                            if (aiConfig.enabled) {
                                GlassButton(onClick = {
                                    if (aiResult == null && !aiLoading) startAiSummary()
                                    else showAiSummary = true
                                }) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                    Text("AI总结")
                                }
                            }
                            GlassButton(onClick = { showAutoScrollSettings = true }) {
                                Icon(
                                    if (autoScrollEnabled && !autoScrollPaused) {
                                        Icons.Default.Pause
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    contentDescription = null
                                )
                                Text("自动滚动")
                            }
                            if (embedded && onOpenFullscreen != null) {
                                GlassButton(onClick = onOpenFullscreen) {
                                    Icon(
                                        if (embeddedFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                        contentDescription = null
                                    )
                                    Text(if (embeddedFullscreen) "缩小" else "全屏")
                                }
                            }
                        }
                    }
                }
            }

            if (showAutoScrollSettings) {
                AutoScrollSettingsCard(
                    enabled = autoScrollEnabled,
                    linesPerSecond = autoScrollLinesPerSecond,
                    onEnabledChange = { enabled ->
                        autoScrollEnabled = enabled
                        autoScrollPaused = false
                        saveAutoScrollSettings()
                    },
                    onLinesPerSecondChange = { linesPerSecond ->
                        autoScrollLinesPerSecond = linesPerSecond
                        saveAutoScrollSettings()
                    },
                    onDismiss = { showAutoScrollSettings = false },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(AUTO_SCROLL_SETTINGS_Z_INDEX)
                )
            }

            AnimatedVisibility(
                visible = autoScrollFeedbackPlaying != null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(AUTO_SCROLL_FEEDBACK_Z_INDEX),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f)
                ) {
                    Icon(
                        imageVector = if (autoScrollFeedbackPlaying == true) {
                            Icons.Default.PlayArrow
                        } else {
                            Icons.Default.Pause
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(24.dp).size(42.dp)
                    )
                }
            }

            if (showAiSummary) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = bottomBarHeight + 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("AI 总结", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            GlassButton(onClick = { showAiSummary = false }) { Text("关闭") }
                        }
                        when {
                            aiLoading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Text("正在处理长正文…")
                            }
                            aiError != null -> {
                                Text(aiError.orEmpty(), color = MaterialTheme.colorScheme.error)
                                GlassButton(onClick = ::startAiSummary) { Text("重试") }
                            }
                            aiResult != null -> {
                                SelectionContainer {
                                    Text(aiResult!!.text, style = readerTextStyle(ReaderTextRole.BODY))
                                }
                                if (aiConfig.showTokenUsage) {
                                    Text(
                                        "词元：输入 ${aiResult!!.promptTokens ?: 0} · 输出 ${aiResult!!.completionTokens ?: 0} · 合计 ${aiResult!!.totalTokens ?: 0}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            else -> GlassButton(onClick = ::startAiSummary) { Text("开始总结") }
                        }
                    }
                }
            }

            previewImage?.let { image ->
                ArticleImagePreviewOverlay(
                    preview = image,
                    dismissRequests = previewDismissRequests,
                    onExit = {
                        previewImage = null
                        previewDismissRequests = 0
                    },
                    modifier = Modifier
                        .matchParentSize()
                        .zIndex(PREVIEW_OVERLAY_Z_INDEX)
                )
            }
            }
        }
}

@Composable
private fun ReaderBackSurface(
    enabled: Boolean = true,
    onBeforeBack: suspend () -> Unit,
    onBack: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    PredictiveBackSurface(
        enabled = enabled,
        onBeforeBack = onBeforeBack,
        onBack = onBack
    ) {
        ReaderBackgroundSurface(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun androidx.compose.ui.text.TextStyle.withReaderIndent(): androidx.compose.ui.text.TextStyle {
    val indent = LocalReaderPresetRuntime.current.preset.body.firstLineIndentEm
    return if (indent <= 0f) this else copy(textIndent = TextIndent(firstLine = indent.em))
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
            style = readerTextStyle(ReaderTextRole.TITLE)
        )
        article.siteName.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = readerTextStyle(ReaderTextRole.SUBTITLE)
            )
        }
    }
}

@Composable
private fun ReaderContentLoadingPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .width(180.dp)
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        )
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
            .roundedClickable(
                shape = RoundedCornerShape(percent = 50),
                enabled = enabled,
                onClick = onClick
            )
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

@Composable
private fun AutoScrollSettingsCard(
    enabled: Boolean,
    linesPerSecond: Float,
    onEnabledChange: (Boolean) -> Unit,
    onLinesPerSecondChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 380.dp)
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动滚动", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "打开阅读器后自动开始",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自动滚动速率（行/秒）", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        enabled = linesPerSecond > AUTO_SCROLL_MIN_LINES_PER_SECOND,
                        onClick = {
                            onLinesPerSecondChange(
                                (linesPerSecond - AUTO_SCROLL_STEP_LINES_PER_SECOND)
                                    .coerceAtLeast(AUTO_SCROLL_MIN_LINES_PER_SECOND)
                            )
                        }
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "降低速率")
                    }
                    Text(
                        String.format(java.util.Locale.US, "%.1f", linesPerSecond),
                        style = MaterialTheme.typography.displaySmall
                    )
                    IconButton(
                        enabled = linesPerSecond < AUTO_SCROLL_MAX_LINES_PER_SECOND,
                        onClick = {
                            onLinesPerSecondChange(
                                (linesPerSecond + AUTO_SCROLL_STEP_LINES_PER_SECOND)
                                    .coerceAtMost(AUTO_SCROLL_MAX_LINES_PER_SECOND)
                            )
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "提高速率")
                    }
                }
            }
            GlassButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("完成")
            }
        }
    }
}

// 阅读页统一的无圆角高斯模糊效果
private fun Modifier.gaussianBlurBackdrop(
    backdrop: LayerBackdrop,
    tint: Color,
    surfaceAlpha: Float
) = drawBackdrop(
    backdrop = backdrop,
    shape = { RectangleShape },
    highlight = null,
    shadow = null,
    effects = {
        blur(18f.dp.toPx())
        vibrancy()
    },
    onDrawSurface = {
        drawRect(tint.copy(alpha = surfaceAlpha))
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
            DisposableEffect(marker, index) {
                onDispose {
                    chunkTexts.remove(index)
                    chunkLayouts.remove(index)
                }
            }
            val text = chunk
            if (text == null) {
                Spacer(modifier = Modifier.height(1.dp))
            } else if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = readerTextStyle(ReaderTextRole.BODY),
                    onTextLayout = { chunkLayouts[index] = it },
                    modifier = Modifier.padding(
                        vertical = LocalReaderPresetRuntime.current.preset.body.paragraphSpacingDp.dp / 2
                    )
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
    onPreviewImage: (ArticlePreviewImage) -> Unit,
    previewSourceNodeIndex: Int?,
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
                    val titleStyle = readerTextStyle(ReaderTextRole.TITLE)
                    Text(
                        text = node.text,
                        style = titleStyle.copy(
                            fontSize = titleStyle.fontSize * when (node.level) {
                                1 -> 1f
                                2 -> 0.88f
                                3 -> 0.78f
                                else -> 0.72f
                            }
                        ),
                        onTextLayout = { textLayouts[index] = it },
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
                is ArticleNode.Paragraph -> {
                    Text(
                        text = node.text,
                        style = readerTextStyle(ReaderTextRole.BODY).withReaderIndent(),
                        onTextLayout = { textLayouts[index] = it },
                        modifier = Modifier.padding(
                            vertical = LocalReaderPresetRuntime.current.preset.body.paragraphSpacingDp.dp / 2
                        )
                    )
                }
                is ArticleNode.Image -> {
                    ArticleImage(
                        url = node.url,
                        alt = node.alt,
                        aspectRatio = node.aspectRatio,
                        onClick = { sourceBounds ->
                            onPreviewImage(
                                ArticlePreviewImage(
                                    url = node.url,
                                    alt = node.alt.takeIf { it.isNotBlank() },
                                    aspectRatio = node.aspectRatio,
                                    sourceNodeIndex = index,
                                    sourceBounds = sourceBounds
                                )
                            )
                        },
                        hidden = previewSourceNodeIndex == index,
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
                            style = readerTextStyle(ReaderTextRole.LINK)
                        )
                        Text(
                            text = node.text,
                            style = readerTextStyle(ReaderTextRole.BODY),
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
                        aspectRatio = node.aspectRatio,
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
    val lineColor = Color(LocalReaderPresetRuntime.current.preset.accentColorArgb)
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
        style = readerTextStyle(ReaderTextRole.QUOTE)
    )
}

@Composable
private fun ArticleCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    val horizontalScrollState = rememberScrollState()
    val preset = LocalReaderPresetRuntime.current.preset
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = Color(preset.codeBackgroundColorArgb),
        contentColor = Color(preset.resolvedStyle(ReaderTypographyRole.CODE).colorArgb),
        tonalElevation = 0.dp
    ) {
        SelectionContainer {
            Text(
                text = text,
                onTextLayout = onTextLayout,
                modifier = Modifier
                    .horizontalScroll(horizontalScrollState)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                style = readerTextStyle(ReaderTextRole.CODE),
                softWrap = false
            )
        }
    }
}

@Composable
private fun ArticleImage(
    url: String,
    alt: String,
    aspectRatio: Float?,
    onClick: ((Rect?) -> Unit)? = null,
    hidden: Boolean = false,
    modifier: Modifier = Modifier
) {
    var loadedAspectRatio by remember(url) {
        mutableStateOf<Float?>(null)
    }
    var imageBounds by remember(url) {
        mutableStateOf<Rect?>(null)
    }
    val intrinsicAspectRatio = aspectRatio ?: loadedAspectRatio
    val sizeModifier = if (intrinsicAspectRatio != null) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(intrinsicAspectRatio)
    } else {
        Modifier.fillMaxWidth()
    }

    val imageModifier = modifier
        .then(sizeModifier)
        .onGloballyPositioned { coordinates ->
            imageBounds = coordinates.boundsInRoot()
        }
        .let { baseModifier ->
            if (onClick != null) {
                baseModifier.roundedClickable(
                    shape = RectangleShape,
                    onClick = { onClick(imageBounds) }
                )
            } else {
                baseModifier
            }
        }

    if (hidden) {
        Box(modifier = imageModifier)
        return
    }

    AsyncImage(
        model = url,
        contentDescription = alt.takeIf { it.isNotBlank() },
        modifier = imageModifier,
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
            val drawable = state.result.drawable
            val intrinsicWidth = drawable.intrinsicWidth
            val intrinsicHeight = drawable.intrinsicHeight
            if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                loadedAspectRatio = intrinsicWidth.toFloat() / intrinsicHeight.toFloat()
            }
        }
    )
}

@Composable
private fun ArticleImagePreviewOverlay(
    preview: ArticlePreviewImage,
    dismissRequests: Int,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var scale by remember(preview.url) { mutableStateOf(1f) }
    var offset by remember(preview.url) { mutableStateOf(Offset.Zero) }
    var lastScaleAt by remember(preview.url) { mutableStateOf(0L) }
    val scaleAnimator = remember(preview.url) { Animatable(1f) }
    val offsetXAnimator = remember(preview.url) { Animatable(0f) }
    val offsetYAnimator = remember(preview.url) { Animatable(0f) }
    val openProgress = remember(preview.url) { Animatable(0f) }
    var scaleAnimJob by remember(preview.url) { mutableStateOf<Job?>(null) }
    var offsetAnimJob by remember(preview.url) { mutableStateOf<Job?>(null) }
    var dismissJob by remember(preview.url) { mutableStateOf<Job?>(null) }
    var lastDismissRequest by remember(preview.url) { mutableStateOf(dismissRequests) }
    var dragDismissOffsetY by remember(preview.url) { mutableStateOf(0f) }
    var predictiveBackProgress by remember(preview.url) { mutableStateOf(0f) }
    var isClosingPreview by remember(preview.url) { mutableStateOf(false) }
    var overlayBoundsInRoot by remember(preview.url) { mutableStateOf<Rect?>(null) }
    val springSpec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    }
    val springOffsetSpec = remember {
        spring<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    }
    val swipeReturnSpringSpec = remember {
        spring<Float>(
            dampingRatio = 0.74f,
            stiffness = Spring.StiffnessMediumLow
        )
    }
    val swipeExitSpringSpec = remember {
        spring<Float>(
            dampingRatio = 0.86f,
            stiffness = Spring.StiffnessMediumLow
        )
    }
    val panDecay = remember { exponentialDecay<Float>(frictionMultiplier = 0.9f) }
    val scaleDecay = remember { exponentialDecay<Float>(frictionMultiplier = 13.6f) }
    val painter = rememberAsyncImagePainter(
        model = preview.url,
        contentScale = ContentScale.Fit
    )
    val painterState = painter.state

    fun stopPreviewTransformAnimations() {
        scaleAnimJob?.cancel()
        offsetAnimJob?.cancel()
    }

    suspend fun springDragDismissOffsetToRest(initialVelocity: Float = 0f) {
        if (dragDismissOffsetY == 0f && initialVelocity == 0f) return
        Animatable(dragDismissOffsetY).animateTo(
            targetValue = 0f,
            animationSpec = swipeReturnSpringSpec,
            initialVelocity = initialVelocity
        ) {
            dragDismissOffsetY = value
        }
    }

    fun closePreviewToSource() {
        if (isClosingPreview) return
        dismissJob?.cancel()
        dismissJob = scope.launch {
            isClosingPreview = true
            stopPreviewTransformAnimations()
            if (dragDismissOffsetY != 0f) {
                animate(
                    initialValue = dragDismissOffsetY,
                    targetValue = 0f,
                    animationSpec = tween(PREVIEW_SWIPE_SETTLE_ANIMATION_MS)
                ) { value, _ ->
                    dragDismissOffsetY = value
                }
            }
            if (predictiveBackProgress != 0f) {
                animate(
                    initialValue = predictiveBackProgress,
                    targetValue = 0f,
                    animationSpec = tween(PREVIEW_SWIPE_SETTLE_ANIMATION_MS)
                ) { value, _ ->
                    predictiveBackProgress = value
                }
            }
            openProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(PREVIEW_SOURCE_EXIT_ANIMATION_MS)
            )
            onExit()
        }
    }

    fun closePreviewBySwipe(initialVelocityY: Float) {
        if (isClosingPreview) return
        dismissJob?.cancel()
        dismissJob = scope.launch {
            isClosingPreview = true
            stopPreviewTransformAnimations()
            val dragJob = launch {
                springDragDismissOffsetToRest(initialVelocityY)
            }
            val frameJob = launch {
                openProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = swipeExitSpringSpec
                )
            }
            dragJob.join()
            frameJob.join()
            onExit()
        }
    }

    LaunchedEffect(preview.url) {
        openProgress.snapTo(0f)
        openProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.86f,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    LaunchedEffect(dismissRequests) {
        if (dismissRequests != lastDismissRequest) {
            lastDismissRequest = dismissRequests
            if (dismissRequests > 0) {
                closePreviewToSource()
            }
        }
    }

    BackHandler(enabled = true) {
        closePreviewToSource()
    }

    PredictiveBackHandler(enabled = true) { backEvents ->
        try {
            stopPreviewTransformAnimations()
            backEvents.collect { backEvent ->
                predictiveBackProgress = backEvent.progress.coerceIn(0f, 1f)
            }
            if (!isClosingPreview) {
                isClosingPreview = true
                animate(
                    initialValue = predictiveBackProgress,
                    targetValue = 1f,
                    animationSpec = tween(PREVIEW_SOURCE_EXIT_ANIMATION_MS)
                ) { value, _ ->
                    predictiveBackProgress = value
                }
                onExit()
            }
        } catch (exception: CancellationException) {
            animate(
                initialValue = predictiveBackProgress,
                targetValue = 0f,
                animationSpec = tween(PREVIEW_SWIPE_SETTLE_ANIMATION_MS)
            ) { value, _ ->
                predictiveBackProgress = value
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayBoundsInRoot = coordinates.boundsInRoot()
            },
        contentAlignment = Alignment.Center
    ) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val containerSize = IntSize(
            containerWidthPx.roundToInt().coerceAtLeast(1),
            containerHeightPx.roundToInt().coerceAtLeast(1)
        )
        val imageSize = remember(painterState, preview.aspectRatio, containerSize) {
            val drawableSize = (painterState as? AsyncImagePainter.State.Success)
                ?.result
                ?.drawable
                ?.let { drawable ->
                    IntSize(
                        drawable.intrinsicWidth.coerceAtLeast(0),
                        drawable.intrinsicHeight.coerceAtLeast(0)
                    )
                }
                ?: IntSize.Zero
            if (drawableSize.width > 0 && drawableSize.height > 0) {
                drawableSize
            } else {
                fallbackPreviewImageSize(preview.aspectRatio, containerSize)
            }
        }
        val baseSize = remember(containerSize, imageSize) {
            calculatePreviewBaseSize(containerSize, imageSize)
        }
        val maxScale = remember(containerSize, imageSize, baseSize) {
            calculatePreviewMaxScale(containerSize, imageSize, baseSize)
        }
        val minScale = 0.5f

        LaunchedEffect(containerSize, imageSize, minScale) {
            scale = max(1f, minScale)
            offset = Offset.Zero
            scaleAnimator.snapTo(scale)
            offsetXAnimator.snapTo(offset.x)
            offsetYAnimator.snapTo(offset.y)
        }
        LaunchedEffect(maxScale) {
            if (scale > maxScale) {
                scale = maxScale
                scaleAnimator.snapTo(scale)
            }
            offset = clampPreviewOffset(offset, scale, containerSize, baseSize)
            offsetXAnimator.snapTo(offset.x)
            offsetYAnimator.snapTo(offset.y)
        }

        val isLoading = painterState is AsyncImagePainter.State.Loading ||
            painterState is AsyncImagePainter.State.Empty
        val isError = painterState is AsyncImagePainter.State.Error
        val targetFrame = calculatePreviewTargetFrame(containerSize, baseSize)
        val measuredSourceFrame = preview.sourceBounds
            ?.let { bounds ->
                rootBoundsToPreviewBounds(bounds, overlayBoundsInRoot) ?: bounds
            }
            ?.takeIf { it.width > 1f && it.height > 1f }
        val sourceFrame = measuredSourceFrame ?: calculatePreviewFallbackSourceFrame(targetFrame)
        val predictiveAdjustedProgress = (openProgress.value * (1f - predictiveBackProgress))
            .coerceIn(0f, 1f)
        val frameProgress = previewEaseOutCubic(predictiveAdjustedProgress)
        val swipeDismissProgress = calculatePreviewSwipeDismissProgress(
            offsetY = dragDismissOffsetY,
            containerHeightPx = containerSize.height.toFloat()
        )
        val backgroundAlpha = PREVIEW_BACKGROUND_ALPHA *
            frameProgress *
            (1f - PREVIEW_BACKGROUND_DISMISS_ALPHA_LOSS * swipeDismissProgress)
        val imageAlpha = (if (measuredSourceFrame != null) 1f else frameProgress) *
            (1f - PREVIEW_IMAGE_DISMISS_ALPHA_LOSS * swipeDismissProgress)
        val zoomProgress = frameProgress * (1f - swipeDismissProgress)
        val sourceToTargetScaleX = if (targetFrame.width > 0f) {
            sourceFrame.width / targetFrame.width
        } else {
            1f
        }
        val sourceToTargetScaleY = if (targetFrame.height > 0f) {
            sourceFrame.height / targetFrame.height
        } else {
            1f
        }
        val sourceToTargetTranslation = sourceFrame.center - targetFrame.center
        val swipeScale = 1f - PREVIEW_SWIPE_MIN_SCALE_LOSS * swipeDismissProgress
        val frameScaleX = lerpPreviewFloat(sourceToTargetScaleX, 1f, frameProgress)
        val frameScaleY = lerpPreviewFloat(sourceToTargetScaleY, 1f, frameProgress)
        val frameTranslationX = lerpPreviewFloat(sourceToTargetTranslation.x, 0f, frameProgress)
        val frameTranslationY = lerpPreviewFloat(sourceToTargetTranslation.y, 0f, frameProgress) +
            dragDismissOffsetY

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    previewBackgroundBrush(
                        alpha = backgroundAlpha,
                        offsetY = dragDismissOffsetY,
                        dismissProgress = swipeDismissProgress
                    )
                )
        )

        val renderScale = sanitizePreviewScale(scale, minScale, maxScale)
        if (renderScale != scale) {
            scale = renderScale
        }
        val renderOffset = sanitizePreviewOffset(offset)
        if (renderOffset != offset) {
            offset = renderOffset
        }

        val imageModifier = Modifier
            .size(
                with(density) { targetFrame.width.toDp() },
                with(density) { targetFrame.height.toDp() }
            )
            .graphicsLayer(
                translationX = frameTranslationX + renderOffset.x * zoomProgress,
                translationY = frameTranslationY + renderOffset.y * zoomProgress,
                scaleX = frameScaleX * swipeScale * (1f + (renderScale - 1f) * zoomProgress),
                scaleY = frameScaleY * swipeScale * (1f + (renderScale - 1f) * zoomProgress),
                transformOrigin = TransformOrigin.Center,
                alpha = imageAlpha
            )
        val gestureModifier = Modifier
            .fillMaxSize()
            .pointerInput(preview.url, containerSize, imageSize, maxScale) {
                detectTapGestures(
                    onTap = { closePreviewToSource() },
                    onDoubleTap = { tap ->
                        val nextScale = nextPreviewDoubleTapScale(scale, maxScale)
                        scope.launch {
                            scaleAnimJob?.cancel()
                            offsetAnimJob?.cancel()
                            val center = Offset(
                                containerSize.width / 2f,
                                containerSize.height / 2f
                            )
                            val tapFromCenter = tap - center
                            val content = (tapFromCenter - offset) / scale
                            val targetOffset = tapFromCenter - content * nextScale
                            val clampedOffset = clampPreviewOffset(
                                targetOffset,
                                nextScale,
                                containerSize,
                                baseSize
                            )
                            scaleAnimJob = launch {
                                scaleAnimator.snapTo(scale)
                                scaleAnimator.animateTo(nextScale, springSpec) {
                                    scale = value
                                    offset = clampPreviewOffset(offset, scale, containerSize, baseSize)
                                }
                            }
                            offsetAnimJob = launch {
                                offsetXAnimator.snapTo(offset.x)
                                offsetYAnimator.snapTo(offset.y)
                                launch {
                                    offsetXAnimator.animateTo(clampedOffset.x, springOffsetSpec) {
                                        offset = clampPreviewOffset(
                                            Offset(value, offsetYAnimator.value),
                                            scale,
                                            containerSize,
                                            baseSize
                                        )
                                    }
                                }
                                launch {
                                    offsetYAnimator.animateTo(clampedOffset.y, springOffsetSpec) {
                                        offset = clampPreviewOffset(
                                            Offset(offsetXAnimator.value, value),
                                            scale,
                                            containerSize,
                                            baseSize
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
            .pointerInput(preview.url, containerSize, imageSize, maxScale) {
                if (containerSize.width <= 0 || containerSize.height <= 0 || imageSize.width <= 0) {
                    return@pointerInput
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scaleAnimJob?.cancel()
                    offsetAnimJob?.cancel()
                    val panVelocityTracker = VelocityTracker().apply {
                        addPosition(down.uptimeMillis, down.position)
                    }
                    var lastScale = scale
                    var lastTime = down.uptimeMillis
                    var scaleVelocity = 0f
                    var lastScaleDelta = 0f
                    var panVelocity = Offset.Zero
                    var lastPanDelta = Offset.Zero
                    var totalPanDistance = 0f
                    var accumulatedPan = Offset.Zero
                    var isVerticalDismiss = false
                    var hadMultiPointer = false
                    var hadScaleGesture = false
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Main)
                        if (event.changes.none { it.pressed }) {
                            break
                        }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid()
                        val activePointerCount = event.changes.count { it.pressed }
                        val rawTime = event.changes.firstOrNull()?.uptimeMillis ?: 0L
                        val time = if (rawTime != 0L) rawTime else SystemClock.uptimeMillis()

                        if (!centroid.isFinite() || !panChange.isFinite() || !zoomChange.isFinite()) {
                            continue
                        }
                        if (activePointerCount > 1) {
                            hadMultiPointer = true
                        }
                        if (activePointerCount > 1 &&
                            abs(zoomChange - 1f) > PREVIEW_SCALE_GESTURE_ZOOM_EPSILON
                        ) {
                            hadScaleGesture = true
                        }
                        accumulatedPan += panChange
                        val recentlyScaled = lastScaleAt != 0L &&
                            time - lastScaleAt < PREVIEW_SWIPE_AFTER_SCALE_BLOCK_MS
                        if (!isVerticalDismiss &&
                            activePointerCount == 1 &&
                            !hadMultiPointer &&
                            !hadScaleGesture &&
                            !recentlyScaled &&
                            abs(accumulatedPan.y) > PREVIEW_SWIPE_START_THRESHOLD_PX &&
                            abs(accumulatedPan.y) > abs(accumulatedPan.x) * PREVIEW_SWIPE_AXIS_DOMINANCE
                        ) {
                            isVerticalDismiss = true
                            stopPreviewTransformAnimations()
                            dragDismissOffsetY += panChange.y
                            panVelocityTracker.addPosition(time, centroid)
                            event.changes.forEach { change ->
                                if (change.positionChanged()) {
                                    change.consume()
                                }
                            }
                            continue
                        }
                        if (isVerticalDismiss) {
                            dragDismissOffsetY += panChange.y
                            panVelocityTracker.addPosition(time, centroid)
                            event.changes.forEach { change ->
                                if (change.positionChanged()) {
                                    change.consume()
                                }
                            }
                            continue
                        }
                        val currentScale = sanitizePreviewScale(scale, minScale, maxScale)
                        val newScale = (currentScale * zoomChange).coerceIn(minScale, maxScale)
                        val scaleChange = if (currentScale == 0f) 1f else newScale / currentScale
                        val center = Offset(
                            containerSize.width / 2f,
                            containerSize.height / 2f
                        )
                        val currentOffset = sanitizePreviewOffset(offset)
                        val scaleDelta = newScale - lastScale
                        val isScaling = abs(scaleDelta) > 0.000018f
                        if (isScaling) {
                            lastScaleAt = time
                        }
                        val ignorePan = !isScaling && lastScaleAt != 0L && time - lastScaleAt < 100L
                        val appliedPan = if (ignorePan) Offset.Zero else panChange
                        if (!ignorePan) {
                            panVelocityTracker.addPosition(time, centroid)
                        }
                        val newOffset = currentOffset + appliedPan +
                            (centroid - center - currentOffset) * (1 - scaleChange)

                        scale = newScale
                        offset = clampPreviewOffset(newOffset, newScale, containerSize, baseSize)
                        if (!ignorePan && (appliedPan.x != 0f || appliedPan.y != 0f)) {
                            totalPanDistance += appliedPan.getDistance()
                            lastPanDelta = appliedPan
                        }
                        if (lastTime != 0L) {
                            val dt = (time - lastTime).coerceAtLeast(1)
                            if (abs(scaleDelta) > 0.000018f) {
                                val scaleVelocityCandidate = (scaleDelta / dt) * 1000f
                                scaleVelocity = scaleVelocity * 0.2f + scaleVelocityCandidate * 0.8f
                                lastScaleDelta = scaleDelta
                            }
                            if (!ignorePan && (appliedPan.x != 0f || appliedPan.y != 0f)) {
                                val panVelocityCandidate = appliedPan * (1000f / dt)
                                panVelocity = panVelocity * 0.2f + panVelocityCandidate * 0.8f
                            }
                        }
                        lastScale = newScale
                        lastTime = time

                        event.changes.forEach { change ->
                            if (change.positionChanged()) {
                                change.consume()
                            }
                        }
                        if (event.changes.all { !it.pressed }) break
                    }

                    if (isVerticalDismiss) {
                        val velocityY = panVelocityTracker.calculateVelocity().y
                        val shouldDismiss = abs(dragDismissOffsetY) >
                            containerSize.height * PREVIEW_SWIPE_DISMISS_DISTANCE_FRACTION ||
                            abs(velocityY) > PREVIEW_SWIPE_DISMISS_VELOCITY_PX
                        if (shouldDismiss) {
                            closePreviewBySwipe(
                                initialVelocityY = velocityY.takeIf { it.isFinite() } ?: 0f
                            )
                        } else {
                            scope.launch {
                                springDragDismissOffsetToRest(
                                    initialVelocity = velocityY.takeIf { it.isFinite() } ?: 0f
                                )
                            }
                        }
                        return@awaitEachGesture
                    }

                    val scaleFlingVelocity = when {
                        abs(scaleVelocity) > 0.0009f -> scaleVelocity
                        abs(lastScaleDelta) > 0.000018f -> lastScaleDelta * 120f
                        else -> 0f
                    }
                    if (abs(scaleFlingVelocity) > 0.0009f) {
                        scaleAnimJob = scope.launch {
                            scaleAnimator.snapTo(sanitizePreviewScale(scale, minScale, maxScale))
                            scaleAnimator.animateDecay(scaleFlingVelocity, scaleDecay) {
                                val clamped = value.coerceIn(minScale, maxScale)
                                if (clamped != scale) {
                                    scale = clamped
                                    offset = clampPreviewOffset(offset, scale, containerSize, baseSize)
                                }
                            }
                        }
                    }

                    val trackerVelocity = panVelocityTracker.calculateVelocity()
                    val trackerOffset = Offset(trackerVelocity.x, trackerVelocity.y)
                    val panVelocityDistance = panVelocity.getDistance()
                    val panFlingVelocity = when {
                        totalPanDistance <= 1f -> Offset.Zero
                        trackerOffset.getDistance() > 5f -> trackerOffset
                        panVelocityDistance > 5f -> panVelocity
                        lastPanDelta.getDistance() > 0.5f -> lastPanDelta * 80f
                        else -> Offset.Zero
                    }
                    val panFlingDistance = panFlingVelocity.getDistance()
                    if (panFlingDistance > 0.1f) {
                        offsetAnimJob = scope.launch {
                            offsetXAnimator.snapTo(offset.x)
                            offsetYAnimator.snapTo(offset.y)
                            launch {
                                offsetXAnimator.animateDecay(panFlingVelocity.x, panDecay) {
                                    val clamped = clampPreviewOffset(
                                        Offset(value, offsetYAnimator.value),
                                        scale,
                                        containerSize,
                                        baseSize
                                    )
                                    if (clamped != offset) {
                                        offset = clamped
                                    }
                                }
                            }
                            launch {
                                offsetYAnimator.animateDecay(panFlingVelocity.y, panDecay) {
                                    val clamped = clampPreviewOffset(
                                        Offset(offsetXAnimator.value, value),
                                        scale,
                                        containerSize,
                                        baseSize
                                    )
                                    if (clamped != offset) {
                                        offset = clamped
                                    }
                                }
                            }
                        }
                    } else {
                        offset = clampPreviewOffset(offset, scale, containerSize, baseSize)
                    }
                }
            }

        Box(
            modifier = gestureModifier,
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painter,
                contentDescription = preview.alt,
                contentScale = ContentScale.Fit,
                modifier = imageModifier
            )
        }

        if (isLoading) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        if (isError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .roundedClickable(
                        shape = RectangleShape,
                        onClick = ::closePreviewToSource
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "图片加载失败",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

private fun calculatePreviewTargetFrame(container: IntSize, baseSize: Size): Rect {
    if (container.width <= 0 || container.height <= 0 || baseSize.width <= 0f || baseSize.height <= 0f) {
        return Rect.Zero
    }
    val left = (container.width.toFloat() - baseSize.width) / 2f
    val top = (container.height.toFloat() - baseSize.height) / 2f
    return Rect(
        left = left,
        top = top,
        right = left + baseSize.width,
        bottom = top + baseSize.height
    )
}

private fun rootBoundsToPreviewBounds(
    rootBounds: Rect,
    previewRootBounds: Rect?
): Rect? {
    val root = previewRootBounds ?: return null
    if (root.width <= 0f || root.height <= 0f) return null
    return Rect(
        left = rootBounds.left - root.left,
        top = rootBounds.top - root.top,
        right = rootBounds.right - root.left,
        bottom = rootBounds.bottom - root.top
    )
}

private fun calculatePreviewFallbackSourceFrame(targetFrame: Rect): Rect {
    if (targetFrame.width <= 0f || targetFrame.height <= 0f) return targetFrame
    val scale = 0.32f
    return scalePreviewFrame(targetFrame, scale)
}

private fun lerpPreviewFrame(start: Rect, stop: Rect, fraction: Float): Rect {
    val progress = fraction.coerceIn(0f, 1f)
    return Rect(
        left = lerpPreviewFloat(start.left, stop.left, progress),
        top = lerpPreviewFloat(start.top, stop.top, progress),
        right = lerpPreviewFloat(start.right, stop.right, progress),
        bottom = lerpPreviewFloat(start.bottom, stop.bottom, progress)
    )
}

private fun translatePreviewFrame(frame: Rect, y: Float): Rect {
    if (y == 0f) return frame
    return Rect(
        left = frame.left,
        top = frame.top + y,
        right = frame.right,
        bottom = frame.bottom + y
    )
}

private fun scalePreviewFrame(frame: Rect, scale: Float): Rect {
    val safeScale = scale.coerceAtLeast(0.01f)
    val center = frame.center
    val width = frame.width * safeScale
    val height = frame.height * safeScale
    return Rect(
        left = center.x - width / 2f,
        top = center.y - height / 2f,
        right = center.x + width / 2f,
        bottom = center.y + height / 2f
    )
}

private fun calculatePreviewSwipeDismissProgress(offsetY: Float, containerHeightPx: Float): Float {
    val range = (containerHeightPx * PREVIEW_SWIPE_BACKGROUND_FADE_DISTANCE_FRACTION)
        .coerceAtLeast(1f)
    return (abs(offsetY) / range).coerceIn(0f, 1f)
}

private fun previewBackgroundBrush(
    alpha: Float,
    offsetY: Float,
    dismissProgress: Float
): Brush {
    val baseAlpha = alpha.coerceIn(0f, PREVIEW_BACKGROUND_ALPHA)
    if (baseAlpha <= 0f) {
        return Brush.verticalGradient(
            listOf(Color.Transparent, Color.Transparent)
        )
    }
    val edgeAlpha = baseAlpha * (1f - dismissProgress.coerceIn(0f, 1f))
    val topAlpha = if (offsetY < 0f) edgeAlpha else baseAlpha
    val bottomAlpha = if (offsetY > 0f) edgeAlpha else baseAlpha
    return Brush.verticalGradient(
        colors = listOf(
            Color.Black.copy(alpha = topAlpha),
            Color.Black.copy(alpha = baseAlpha),
            Color.Black.copy(alpha = bottomAlpha)
        )
    )
}

private fun previewEaseOutCubic(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    val inverse = 1f - clamped
    return 1f - inverse * inverse * inverse
}

private fun lerpPreviewFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

private fun fallbackPreviewImageSize(
    aspectRatio: Float?,
    containerSize: IntSize
): IntSize {
    val ratio = aspectRatio
        ?.takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(0.05f, 20f)
    return if (ratio != null) {
        IntSize(
            width = (1_000f * ratio).roundToInt().coerceAtLeast(1),
            height = 1_000
        )
    } else {
        containerSize
    }
}

private fun calculatePreviewBaseSize(container: IntSize, image: IntSize): Size {
    if (container.width <= 0 || container.height <= 0 || image.width <= 0 || image.height <= 0) {
        return Size.Zero
    }
    val containerRatio = container.width.toFloat() / container.height.toFloat()
    val imageRatio = image.width.toFloat() / image.height.toFloat()
    return if (imageRatio >= containerRatio) {
        val width = container.width.toFloat()
        Size(width, width / imageRatio)
    } else {
        val height = container.height.toFloat()
        Size(height * imageRatio, height)
    }
}

private fun calculatePreviewMaxScale(container: IntSize, image: IntSize, baseSize: Size): Float {
    if (baseSize.width <= 0f || baseSize.height <= 0f) return 1f
    val screenScale = max(
        container.width * 4f / baseSize.width,
        container.height * 4f / baseSize.height
    )
    val imageScale = max(
        image.width * 4f / baseSize.width,
        image.height * 4f / baseSize.height
    )
    return max(screenScale, imageScale).coerceAtLeast(1f)
}

private fun clampPreviewOffset(
    rawOffset: Offset,
    scale: Float,
    container: IntSize,
    baseSize: Size
): Offset {
    if (baseSize.width <= 0f || baseSize.height <= 0f) return Offset.Zero
    if (!rawOffset.isFinite()) return Offset.Zero
    val scaledWidth = baseSize.width * scale
    val scaledHeight = baseSize.height * scale
    val maxX = ((scaledWidth - container.width) / 2f).coerceAtLeast(0f)
    val maxY = ((scaledHeight - container.height) / 2f).coerceAtLeast(0f)
    return Offset(
        rawOffset.x.coerceIn(-maxX, maxX),
        rawOffset.y.coerceIn(-maxY, maxY)
    )
}

private fun nextPreviewDoubleTapScale(current: Float, maxScale: Float): Float {
    val first = minOf(2f, maxScale)
    val second = minOf(4f, maxScale)
    return when {
        current < first - 0.05f -> first
        current < second - 0.05f -> second
        else -> 1f
    }
}

private operator fun Offset.times(factor: Float): Offset {
    return Offset(x * factor, y * factor)
}

private operator fun Offset.div(factor: Float): Offset {
    return Offset(x / factor, y / factor)
}

private fun Offset.isFinite(): Boolean {
    return x.isFinite() && y.isFinite()
}

private fun sanitizePreviewScale(value: Float, minScale: Float, maxScale: Float): Float {
    if (!value.isFinite()) return minScale
    if (value <= 0f) return minScale
    return value.coerceIn(minScale, maxScale)
}

private fun sanitizePreviewOffset(value: Offset): Offset {
    return if (value.isFinite()) value else Offset.Zero
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

private data class ArticlePreviewImage(
    val url: String,
    val alt: String?,
    val aspectRatio: Float?,
    val sourceNodeIndex: Int,
    val sourceBounds: Rect?
)

internal data class ArticleContentNodesKey(
    val articleId: String,
    val contentHash: String,
    val contentHtml: String?,
    val contentText: String,
    val excerpt: String,
    val url: String
)

internal data class ArticleContentNodesSnapshot(
    val key: ArticleContentNodesKey,
    val nodes: List<ArticleNode>
)

internal sealed class ArticleNode {
    data class Heading(val text: String, val level: Int) : ArticleNode()
    data class Paragraph(val text: String) : ArticleNode()
    data class Image(val url: String, val alt: String, val aspectRatio: Float?) : ArticleNode()
    data class BlockQuote(val text: String) : ArticleNode()
    data class CodeBlock(val text: String) : ArticleNode()
    data class ListItem(val text: String) : ArticleNode()
    object HorizontalRule : ArticleNode()
    data class Spacer(val height: Dp) : ArticleNode()
}

private sealed class VisibleArticleNodeWithLayout {
    abstract val itemInfo: LazyListItemInfo
    abstract val nodeIndex: Int

    data class Text(
        override val itemInfo: LazyListItemInfo,
        override val nodeIndex: Int,
        val text: String,
        val layout: TextLayoutResult
    ) : VisibleArticleNodeWithLayout()

    data class Image(
        override val itemInfo: LazyListItemInfo,
        override val nodeIndex: Int
    ) : VisibleArticleNodeWithLayout()
}

private data class VisibleImportedTextChunkWithLayout(
    val itemInfo: LazyListItemInfo,
    val chunkIndex: Int,
    val text: String,
    val layout: TextLayoutResult
)

private sealed class ArticleRestoreTarget {
    abstract val itemIndex: Int

    data class Text(
        override val itemIndex: Int,
        val nodeIndex: Int,
        val byteOffsetInNode: Int
    ) : ArticleRestoreTarget()

    data class Image(
        override val itemIndex: Int,
        val nodeIndex: Int,
        val offsetFraction: Float
    ) : ArticleRestoreTarget()
}

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

private fun articleNodeReadingUnits(node: ArticleNode?): Int {
    return articleNodeText(node)?.let(::utf8ByteCount)
        ?: if (node is ArticleNode.Image) ARTICLE_IMAGE_READING_UNITS else 0
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

private fun calculateArticleReadingProgressFromLayout(
    listState: LazyListState,
    nodes: List<ArticleNode>,
    textLayouts: Map<Int, TextLayoutResult>,
    anchorOffsetPx: Int
): Float? {
    val totalReadingUnits = nodes.sumOf(::articleNodeReadingUnits)
    if (totalReadingUnits <= 0) return null
    val layoutInfo = listState.layoutInfo
    if (layoutInfo.visibleItemsInfo.isNotEmpty() && !listState.canScrollForward) return 1f
    val normalizedAnchorOffsetPx = anchorOffsetPx.coerceAtLeast(0)
    val visibleNode = layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { itemInfo ->
        if (itemInfo.offset + itemInfo.size <= normalizedAnchorOffsetPx) {
            return@firstNotNullOfOrNull null
        }
        when (nodes.getOrNull(itemInfo.index)) {
            is ArticleNode.Image -> VisibleArticleNodeWithLayout.Image(
                itemInfo = itemInfo,
                nodeIndex = itemInfo.index
            )
            else -> {
                val text = articleNodeText(nodes.getOrNull(itemInfo.index))
                    ?: return@firstNotNullOfOrNull null
                val layout = textLayouts[itemInfo.index] ?: return@firstNotNullOfOrNull null
                if (layout.lineCount <= 0) return@firstNotNullOfOrNull null
                VisibleArticleNodeWithLayout.Text(
                    itemInfo = itemInfo,
                    nodeIndex = itemInfo.index,
                    text = text,
                    layout = layout
                )
            }
        }
    } ?: return null

    val unitsBeforeNode = nodes.asSequence()
        .take(visibleNode.nodeIndex)
        .sumOf(::articleNodeReadingUnits)
    val scrolledInItemPx = (normalizedAnchorOffsetPx - visibleNode.itemInfo.offset)
        .coerceIn(0, visibleNode.itemInfo.size.coerceAtLeast(0))

    val absoluteUnit = when (visibleNode) {
        is VisibleArticleNodeWithLayout.Text -> {
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
            unitsBeforeNode + utf8ByteCountBeforeCharOffset(visibleNode.text, charOffset)
        }
        is VisibleArticleNodeWithLayout.Image -> {
            val imageUnits = articleNodeReadingUnits(nodes.getOrNull(visibleNode.nodeIndex))
            val itemSizePx = visibleNode.itemInfo.size.coerceAtLeast(1)
            val imageUnitOffset = (imageUnits.toDouble() * scrolledInItemPx.toDouble() / itemSizePx.toDouble())
                .roundToInt()
                .coerceIn(0, imageUnits)
            unitsBeforeNode + imageUnitOffset
        }
    }.coerceIn(0, totalReadingUnits)

    return (absoluteUnit.toDouble() / totalReadingUnits.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

private fun articleRestoreTarget(
    progress: Float,
    nodes: List<ArticleNode>
): ArticleRestoreTarget? {
    val readingUnitCounts = nodes.map(::articleNodeReadingUnits)
    val totalReadingUnits = readingUnitCounts.sum()
    if (totalReadingUnits <= 0) return null
    val targetUnit = (totalReadingUnits.toDouble() * progress.coerceIn(0f, 1f).toDouble())
        .roundToInt()
        .coerceIn(0, (totalReadingUnits - 1).coerceAtLeast(0))
    var consumed = 0
    readingUnitCounts.forEachIndexed { index, unitCount ->
        if (unitCount <= 0) return@forEachIndexed
        val next = consumed + unitCount
        if (targetUnit < next) {
            val node = nodes.getOrNull(index)
            if (node is ArticleNode.Image) {
                return ArticleRestoreTarget.Image(
                    itemIndex = index,
                    nodeIndex = index,
                    offsetFraction = ((targetUnit - consumed).toFloat() / unitCount.toFloat())
                        .coerceIn(0f, 1f)
                )
            }
            return ArticleRestoreTarget.Text(
                itemIndex = index,
                nodeIndex = index,
                byteOffsetInNode = (targetUnit - consumed).coerceAtLeast(0)
            )
        }
        consumed = next
    }
    val lastReadableIndex = readingUnitCounts.indexOfLast { it > 0 }
    if (lastReadableIndex < 0) return null
    val lastNode = nodes.getOrNull(lastReadableIndex)
    return if (lastNode is ArticleNode.Image) {
        ArticleRestoreTarget.Image(
            itemIndex = lastReadableIndex,
            nodeIndex = lastReadableIndex,
            offsetFraction = 1f
        )
    } else {
        ArticleRestoreTarget.Text(
            itemIndex = lastReadableIndex,
            nodeIndex = lastReadableIndex,
            byteOffsetInNode = (readingUnitCounts[lastReadableIndex] - 1).coerceAtLeast(0)
        )
    }
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
    return (itemInfo.offset + textTopPaddingPx + layout.getLineTop(lineIndex))
        .roundToInt()
        .coerceAtLeast(0) - anchorOffsetPx.coerceAtLeast(0)
}

private fun articleTextRestoreVisualOffsetPx(
    restoreTarget: ArticleRestoreTarget.Text,
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
    return (itemInfo.offset + textTopPaddingPx + layout.getLineTop(lineIndex))
        .roundToInt()
        .coerceAtLeast(0) - anchorOffsetPx.coerceAtLeast(0)
}

private fun articleImageRestoreVisualOffsetPx(
    restoreTarget: ArticleRestoreTarget.Image,
    itemInfo: LazyListItemInfo,
    anchorOffsetPx: Int
): Int {
    val imageOffsetPx = (itemInfo.size.coerceAtLeast(0).toFloat() * restoreTarget.offsetFraction)
        .roundToInt()
        .coerceAtLeast(0)
    return (itemInfo.offset + imageOffsetPx)
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
                parseImageNode(element)?.let(result::add)
            }
            "figure" -> {
                val img = element.selectFirst("img")
                if (img != null) {
                    parseImageNode(img)?.let(result::add)
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
            parseImageNode(element)?.let(result::add)
        }
        "figure" -> {
            val img = element.selectFirst("img")
            if (img != null) {
                parseImageNode(img)?.let(result::add)
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

private fun parseImageNode(element: Element): ArticleNode.Image? {
    val src = element.attr("src").trim()
    if (src.isBlank()) return null
    return ArticleNode.Image(
        url = src,
        alt = element.attr("alt"),
        aspectRatio = parseImageAspectRatio(element)
    )
}

private fun parseImageAspectRatio(element: Element): Float? {
    val width = firstImageDimension(
        element,
        attrs = listOf("width", "data-width", "data-w", "data-original-width", "data-actual-width"),
        styleProperty = "width"
    )
    val height = firstImageDimension(
        element,
        attrs = listOf("height", "data-height", "data-h", "data-original-height", "data-actual-height"),
        styleProperty = "height"
    )
    if (width == null || height == null || height <= 0f) return null
    return (width / height)
        .takeIf { it.isFinite() }
}

private fun firstImageDimension(
    element: Element,
    attrs: List<String>,
    styleProperty: String
): Float? {
    attrs.firstNotNullOfOrNull { attr ->
        parseImageDimension(element.attr(attr))
    }?.let { return it }
    return parseStyleDimension(
        style = element.attr("style"),
        property = styleProperty
    )
}

private fun parseStyleDimension(style: String, property: String): Float? {
    if (style.isBlank()) return null
    val match = Regex(
        pattern = "(?i)(?:^|;)\\s*${Regex.escape(property)}\\s*:\\s*([^;]+)"
    ).find(style) ?: return null
    return parseImageDimension(match.groupValues[1])
}

private fun parseImageDimension(value: String): Float? {
    val normalized = value.trim().lowercase()
    if (normalized.isBlank() || normalized.contains("%") || normalized == "auto") return null
    val number = Regex("""\d+(?:\.\d+)?""")
        .find(normalized)
        ?.value
        ?.toFloatOrNull()
        ?: return null
    return number.takeIf { it > 0f && it.isFinite() }
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
private const val ARTICLE_RESTORE_HIDE_PROGRESS_EPSILON = 0.001f
private const val ARTICLE_READING_PROGRESS_SAMPLE_MS = 500L
private const val MAX_ARTICLE_TEXT_NODE_CHARS = 2_000
private const val ARTICLE_IMAGE_READING_UNITS = 520
private const val IMPORTED_TEXT_CHUNK_KEY_PREFIX = "importedText:"
private const val READER_CHROME_SNAP_ANIMATION_MS = 140
private const val AUTO_SCROLL_PREFERENCES = "reader_auto_scroll"
private const val AUTO_SCROLL_ENABLED_KEY = "enabled"
private const val AUTO_SCROLL_LINES_PER_SECOND_KEY = "lines_per_second"
private const val AUTO_SCROLL_DEFAULT_LINES_PER_SECOND = 2f
private const val AUTO_SCROLL_MIN_LINES_PER_SECOND = 0.5f
private const val AUTO_SCROLL_MAX_LINES_PER_SECOND = 10f
private const val AUTO_SCROLL_STEP_LINES_PER_SECOND = 0.5f
private const val AUTO_SCROLL_FEEDBACK_DURATION_MS = 700L
private const val AUTO_SCROLL_SETTINGS_Z_INDEX = 20f
private const val AUTO_SCROLL_FEEDBACK_Z_INDEX = 21f
private const val PREVIEW_OVERLAY_Z_INDEX = 10f
private const val PREVIEW_BACKGROUND_ALPHA = 0.96f
private const val PREVIEW_BACKGROUND_DISMISS_ALPHA_LOSS = 0.94f
private const val PREVIEW_IMAGE_DISMISS_ALPHA_LOSS = 0.42f
private const val PREVIEW_SWIPE_MIN_SCALE_LOSS = 0.12f
private const val PREVIEW_SWIPE_START_THRESHOLD_PX = 18f
private const val PREVIEW_SWIPE_AXIS_DOMINANCE = 1.2f
private const val PREVIEW_SCALE_GESTURE_ZOOM_EPSILON = 0.012f
private const val PREVIEW_SWIPE_AFTER_SCALE_BLOCK_MS = 180L
private const val PREVIEW_SWIPE_DISMISS_DISTANCE_FRACTION = 0.16f
private const val PREVIEW_SWIPE_BACKGROUND_FADE_DISTANCE_FRACTION = 0.36f
private const val PREVIEW_SWIPE_DISMISS_VELOCITY_PX = 1_050f
private const val PREVIEW_SOURCE_EXIT_ANIMATION_MS = 220
private const val PREVIEW_SWIPE_SETTLE_ANIMATION_MS = 180
