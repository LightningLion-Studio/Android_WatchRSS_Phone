package com.lightningstudio.watchrss.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.PixelCopy
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lightningstudio.watchrss.phone.platform.PlatformLinkKind
import com.lightningstudio.watchrss.phone.platform.PlatformLinkRouter
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.PREDICTIVE_BACK_CANCEL_ANIMATION_MS
import com.lightningstudio.watchrss.phone.ui.PREDICTIVE_BACK_EXIT_ANIMATION_MS
import com.lightningstudio.watchrss.phone.ui.PREDICTIVE_BACK_EXIT_PROGRESS
import com.lightningstudio.watchrss.phone.ui.adaptiveContentWidth
import com.lightningstudio.watchrss.phone.ui.predictiveBackExitPreview
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.ui.theme.roundedClickable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PlatformWebViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val articleId = intent.getStringExtra(EXTRA_ARTICLE_ID).orEmpty()
        val fallbackReadingProgress = intent
            .getFloatExtra(EXTRA_READING_PROGRESS, 0f)
            .coerceIn(0f, 1f)
        val platform = PlatformLinkRouter.detect(url)
        val repository = (application as PhoneCompanionApplication).container.repository

        setContent {
            WatchRssPhoneTheme {
                val initialReadingProgress by produceState<Float?>(
                    initialValue = if (articleId.isBlank()) fallbackReadingProgress else null,
                    key1 = articleId,
                    key2 = fallbackReadingProgress
                ) {
                    value = if (articleId.isBlank()) {
                        fallbackReadingProgress
                    } else {
                        repository.getArticle(articleId)
                            ?.readingProgress
                            ?.coerceIn(0f, 1f)
                            ?: fallbackReadingProgress
                    }
                }
                val continuePlaybackInBackground by produceState(
                    initialValue = false,
                    key1 = articleId
                ) {
                    val article = articleId
                        .takeIf { it.isNotBlank() }
                        ?.let { repository.getArticle(it) }
                    value = article
                        ?.rssSourceUrl
                        ?.let { repository.getRssSource(it) }
                        ?.continuePlaybackInBackground == true
                }
                initialReadingProgress?.let { restoredProgress ->
                    PlatformWebViewScreen(
                        url = url,
                        title = title,
                        platform = platform,
                        onBack = { finish() },
                        onOpenExternal = { openExternalUrl(this, url) },
                        continuePlaybackInBackground = continuePlaybackInBackground,
                        initialScrollProgress = restoredProgress,
                        onSaveScrollProgress = if (articleId.isNotBlank()) {
                            { progress -> repository.updateArticleReadingProgress(articleId, progress) }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val EXTRA_ARTICLE_ID = "articleId"
        private const val EXTRA_READING_PROGRESS = "readingProgress"

        fun createIntent(
            context: Context,
            title: String,
            url: String,
            articleId: String = "",
            initialReadingProgress: Float = 0f
        ): Intent {
            return Intent(context, PlatformWebViewActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_ARTICLE_ID, articleId)
                putExtra(EXTRA_READING_PROGRESS, initialReadingProgress.coerceIn(0f, 1f))
            }
        }
    }
}

@Composable
internal fun PlatformWebViewScreen(
    url: String,
    title: String,
    platform: PlatformLinkKind?,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
    continuePlaybackInBackground: Boolean = false,
    embedded: Boolean = false,
    initialScrollProgress: Float = 0f,
    onSaveScrollProgress: (suspend (Float) -> Unit)? = null,
    embeddedFullscreen: Boolean = false,
    onOpenFullscreen: (() -> Unit)? = null
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var webScrollProgress by remember(url) { mutableFloatStateOf(initialScrollProgress.coerceIn(0f, 1f)) }
    var webScrollCanSave by remember(url) { mutableStateOf(initialScrollProgress <= 0f) }
    var lastSavedScrollProgress by remember(url) { mutableFloatStateOf(initialScrollProgress.coerceIn(0f, 1f)) }
    var lastScrollSavedAt by remember(url) { mutableStateOf(0L) }
    var webViewSnapshot by remember(url) { mutableStateOf<WebViewSnapshot?>(null) }
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    var topBarBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    var bottomBarBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    var webHistoryBackProgress by remember(url) { mutableFloatStateOf(0f) }
    var screenBackProgress by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val onSaveScrollProgressState = rememberUpdatedState(onSaveScrollProgress)
    val onBackState = rememberUpdatedState(onBack)
    val canGoBack = webView?.canGoBack() == true

    fun saveWebScrollProgress(force: Boolean) {
        val saver = onSaveScrollProgressState.value ?: return
        if (!force && !webScrollCanSave) return
        val clamped = webScrollProgress.coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            val diff = abs(clamped - lastSavedScrollProgress)
            if (diff < 0.02f && now - lastScrollSavedAt < 1500L) return
        }
        lastSavedScrollProgress = clamped
        lastScrollSavedAt = now
        coroutineScope.launch {
            saver(clamped)
        }
    }

    fun saveWebScrollProgressBlocking() {
        val saver = onSaveScrollProgressState.value ?: return
        runBlocking {
            saver(webScrollProgress.coerceIn(0f, 1f))
        }
    }

    fun handleBack() {
        saveWebScrollProgressBlocking()
        onBackState.value()
    }

    PredictiveBackHandler(enabled = canGoBack) { backEvents ->
        try {
            webHistoryBackProgress = 0f
            backEvents.collect { backEvent ->
                webHistoryBackProgress = backEvent.progress.coerceIn(0f, 1f)
            }
            animate(
                initialValue = webHistoryBackProgress,
                targetValue = 1f,
                animationSpec = tween(PREDICTIVE_BACK_EXIT_ANIMATION_MS)
            ) { value, _ ->
                webHistoryBackProgress = value
            }
            webView?.goBack()
            webHistoryBackProgress = 0f
        } catch (exception: CancellationException) {
            animate(
                initialValue = webHistoryBackProgress,
                targetValue = 0f,
                animationSpec = tween(PREDICTIVE_BACK_CANCEL_ANIMATION_MS)
            ) { value, _ ->
                webHistoryBackProgress = value
            }
        }
    }

    PredictiveBackHandler(enabled = !embedded && !canGoBack) { backEvents ->
        try {
            screenBackProgress = 0f
            backEvents.collect { backEvent ->
                screenBackProgress = backEvent.progress.coerceIn(0f, 1f)
            }
            animate(
                initialValue = screenBackProgress,
                targetValue = PREDICTIVE_BACK_EXIT_PROGRESS,
                animationSpec = tween(PREDICTIVE_BACK_EXIT_ANIMATION_MS)
            ) { value, _ ->
                screenBackProgress = value
            }
            saveWebScrollProgressBlocking()
            onBackState.value()
            screenBackProgress = 0f
        } catch (exception: CancellationException) {
            animate(
                initialValue = screenBackProgress,
                targetValue = 0f,
                animationSpec = tween(PREDICTIVE_BACK_CANCEL_ANIMATION_MS)
            ) { value, _ ->
                screenBackProgress = value
            }
        }
    }

    DisposableEffect(url) {
        onDispose {
            saveWebScrollProgressBlocking()
        }
    }

    DisposableEffect(lifecycleOwner, webView, continuePlaybackInBackground) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (!continuePlaybackInBackground) webView?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val webViewVerticalGap = 32.dp

    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .predictiveBackExitPreview(screenBackProgress)
    ) {
        // WebView 内容区域
        Box(
            modifier = Modifier
                .fillMaxSize()
                .platformWebHistoryBackPreview(webHistoryBackProgress)
                .padding(
                    top = topBarHeight + webViewVerticalGap,
                    bottom = bottomBarHeight + webViewVerticalGap
                )
        ) {
            key(url) {
                PlatformWebView(
                    url = url,
                    platform = platform,
                    allowExternalDeepLinks = !embedded,
                    onCreated = {
                        webView = it
                        progress = 0
                    },
                    onProgress = { progress = it },
                    onSnapshot = { webViewSnapshot = it },
                    initialScrollProgress = initialScrollProgress,
                    onScrollProgress = { scrollProgress ->
                        webScrollProgress = scrollProgress
                        saveWebScrollProgress(force = false)
                    },
                    onScrollReady = {
                        webScrollCanSave = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                )
            }
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                )
            }
        }

        // 顶部无圆角高斯模糊
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onGloballyPositioned { coordinates ->
                    topBarHeight = with(density) { coordinates.size.height.toDp() }
                    topBarBoundsInWindow = coordinates.toAndroidWindowRect()
                }
        ) {
            BlurredWebViewSnapshotStrip(
                snapshot = webViewSnapshot,
                targetBoundsInWindow = topBarBoundsInWindow,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.24f))
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .adaptiveContentWidth(
                        windowInfo = windowInfo,
                        mediumMaxWidth = 720.dp,
                        expandedMaxWidth = 860.dp
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = ::handleBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = title.ifBlank { url },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenExternal) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "外部打开",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 底部无圆角高斯模糊按钮栏
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    bottomBarHeight = with(density) { coordinates.size.height.toDp() }
                    bottomBarBoundsInWindow = coordinates.toAndroidWindowRect()
                }
        ) {
            BlurredWebViewSnapshotStrip(
                snapshot = webViewSnapshot,
                targetBoundsInWindow = bottomBarBoundsInWindow,
                modifier = Modifier.matchParentSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.24f))
            )
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .adaptiveContentWidth(
                        windowInfo = windowInfo,
                        mediumMaxWidth = 720.dp,
                        expandedMaxWidth = 860.dp
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = if (embedded) {
                    Arrangement.spacedBy(8.dp, Alignment.End)
                } else {
                    Arrangement.spacedBy(12.dp)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (embedded) {
                    GlassIconButton(
                        onClick = ::handleBack,
                        contentDescription = "返回"
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                    GlassIconButton(
                        onClick = {
                            if (webView?.canGoBack() == true) {
                                webView?.goBack()
                            } else {
                                handleBack()
                            }
                        },
                        enabled = canGoBack,
                        contentDescription = "上一页"
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                    GlassIconButton(
                        onClick = onOpenExternal,
                        contentDescription = "外部打开"
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    }
                    if (onOpenFullscreen != null) {
                        GlassIconButton(
                            onClick = {
                                saveWebScrollProgressBlocking()
                                onOpenFullscreen()
                            },
                            contentDescription = if (embeddedFullscreen) "缩小" else "全屏"
                        ) {
                            Icon(
                                if (embeddedFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = null
                            )
                        }
                    }
                } else {
                    GlassButton(onClick = ::handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text("返回")
                    }
                    GlassButton(
                        onClick = {
                            if (webView?.canGoBack() == true) {
                                webView?.goBack()
                            } else {
                                handleBack()
                            }
                        },
                        enabled = canGoBack
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text("上一页")
                    }
                    GlassButton(onClick = onOpenExternal) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        Text("外部打开")
                    }
                    if (onOpenFullscreen != null) {
                        GlassButton(
                            onClick = {
                                saveWebScrollProgressBlocking()
                                onOpenFullscreen()
                            }
                        ) {
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
    }
}

@Composable
private fun BlurredWebViewSnapshotStrip(
    snapshot: WebViewSnapshot?,
    targetBoundsInWindow: Rect?,
    modifier: Modifier = Modifier
) {
    val source = snapshot ?: return
    val targetBounds = targetBoundsInWindow ?: return
    val bitmap = source.bitmap
    val intersection = Rect(targetBounds).apply {
        if (!intersect(source.boundsInWindow)) return
    }
    if (intersection.isEmpty) return
    Canvas(
        modifier = modifier
            .clipToBounds()
            .blur(20.dp)
    ) {
        if (size.width <= 0f || size.height <= 0f || bitmap.width <= 0 || bitmap.height <= 0) {
            return@Canvas
        }
        val targetWidth = targetBounds.width().coerceAtLeast(1)
        val targetHeight = targetBounds.height().coerceAtLeast(1)
        val scaleX = bitmap.width.toFloat() / source.boundsInWindow.width().coerceAtLeast(1)
        val scaleY = bitmap.height.toFloat() / source.boundsInWindow.height().coerceAtLeast(1)
        val srcLeft = ((intersection.left - source.boundsInWindow.left) * scaleX)
            .roundToInt()
            .coerceIn(0, bitmap.width - 1)
        val srcTop = ((intersection.top - source.boundsInWindow.top) * scaleY)
            .roundToInt()
            .coerceIn(0, bitmap.height - 1)
        val srcRight = ((intersection.right - source.boundsInWindow.left) * scaleX)
            .roundToInt()
            .coerceIn(srcLeft + 1, bitmap.width)
        val srcBottom = ((intersection.bottom - source.boundsInWindow.top) * scaleY)
            .roundToInt()
            .coerceIn(srcTop + 1, bitmap.height)
        val dstLeft = ((intersection.left - targetBounds.left) * size.width / targetWidth)
            .roundToInt()
        val dstTop = ((intersection.top - targetBounds.top) * size.height / targetHeight)
            .roundToInt()
        val dstRight = ((intersection.right - targetBounds.left) * size.width / targetWidth)
            .roundToInt()
        val dstBottom = ((intersection.bottom - targetBounds.top) * size.height / targetHeight)
            .roundToInt()
        drawImage(
            image = bitmap.asImageBitmap(),
            srcOffset = IntOffset(srcLeft, srcTop),
            srcSize = IntSize(srcRight - srcLeft, srcBottom - srcTop),
            dstOffset = IntOffset(dstLeft, dstTop),
            dstSize = IntSize(
                (dstRight - dstLeft).coerceAtLeast(1),
                (dstBottom - dstTop).coerceAtLeast(1)
            )
        )
    }
}

@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    contentDescription: String,
    content: @Composable () -> Unit
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .semantics {
                this.contentDescription = contentDescription
            }
            .roundedClickable(
                shape = RoundedCornerShape(percent = 50),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Box(contentAlignment = Alignment.Center) {
                content()
            }
    }
    }
}

private fun Modifier.platformWebHistoryBackPreview(progress: Float): Modifier {
    val previewProgress = progress.coerceIn(0f, 1f)
    if (previewProgress <= 0f) return this
    return graphicsLayer {
        translationX = 72.dp.toPx() * previewProgress
        scaleX = 1f - 0.02f * previewProgress
        scaleY = 1f - 0.02f * previewProgress
        alpha = 1f - 0.08f * previewProgress
    }
}

@Composable
private fun GlassButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
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
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                content()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PlatformWebView(
    url: String,
    platform: PlatformLinkKind?,
    allowExternalDeepLinks: Boolean,
    onCreated: (WebView) -> Unit,
    onProgress: (Int) -> Unit,
    onSnapshot: (WebViewSnapshot?) -> Unit,
    initialScrollProgress: Float,
    onScrollProgress: (Float) -> Unit,
    onScrollReady: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snapshotter = remember { ForegroundWebViewSnapshotter() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            snapshotter.release()
            onSnapshot(null)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                var restoredInitialScroll = false
                var restoreInitialScrollPending = false
                var restoreInitialScrollAttempts = 0
                fun restoreInitialScrollIfNeeded(target: WebView?) {
                    val view = target ?: return
                    if (restoredInitialScroll || restoreInitialScrollPending) return
                    val restoreProgress = initialScrollProgress.coerceIn(0f, 1f)
                    if (restoreProgress <= 0f) {
                        restoredInitialScroll = true
                        onScrollReady()
                        return
                    }
                    restoreInitialScrollPending = true
                    view.postDelayed(
                        {
                            restoreInitialScrollPending = false
                            if (restoredInitialScroll) return@postDelayed
                            val maxScroll = view.maxVerticalScroll()
                            if (maxScroll > 0) {
                                view.scrollTo(
                                    view.scrollX,
                                    (maxScroll * restoreProgress).roundToInt().coerceIn(0, maxScroll)
                                )
                                onScrollProgress(restoreProgress)
                                restoredInitialScroll = true
                                view.postDelayed(onScrollReady, 240L)
                            } else if (restoreInitialScrollAttempts < 10) {
                                restoreInitialScrollAttempts += 1
                                restoreInitialScrollIfNeeded(view)
                            } else {
                                restoredInitialScroll = true
                                onScrollReady()
                            }
                        },
                        if (restoreInitialScrollAttempts == 0) 360L else 180L
                    )
                }
                onCreated(this)
                configurePlatformWebView(platform)
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgress(newProgress)
                        if (newProgress == 100) {
                            restoreInitialScrollIfNeeded(view)
                            snapshotter.request(view, onSnapshot)
                        }
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        restoreInitialScrollIfNeeded(view)
                        snapshotter.request(view, onSnapshot)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        return handleNonWebUrl(context, view, request?.url?.toString(), allowExternalDeepLinks)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return handleNonWebUrl(context, view, url, allowExternalDeepLinks)
                    }
                }
                setOnScrollChangeListener { source, _, _, _, _ ->
                    val sourceWebView = source as? WebView
                    sourceWebView?.let { scrolledView ->
                        val maxScroll = scrolledView.maxVerticalScroll()
                        if (maxScroll > 0) {
                            onScrollProgress(
                                (scrolledView.scrollY.toFloat() / maxScroll.toFloat())
                                    .coerceIn(0f, 1f)
                            )
                        }
                    }
                    snapshotter.request(sourceWebView, onSnapshot)
                }
                post {
                    snapshotter.request(this, onSnapshot, delayMs = 220L)
                }
                loadUrl(url)
            }
        },
        update = {},
        onRelease = { view ->
            snapshotter.release()
            view.destroy()
        }
    )
}

private class ForegroundWebViewSnapshotter {
    private var handler: Handler? = null
    private var released = false
    private var pendingRunnable: Runnable? = null
    private var lastCaptureAt = 0L

    fun request(
        webView: WebView?,
        onSnapshot: (WebViewSnapshot) -> Unit,
        delayMs: Long = SNAPSHOT_THROTTLE_MS
    ) {
        val view = webView ?: return
        if (released || view.width <= 0 || view.height <= 0) return
        val mainHandler = handler ?: Handler(view.context.mainLooper).also { handler = it }
        pendingRunnable?.let(mainHandler::removeCallbacks)
        val now = android.os.SystemClock.uptimeMillis()
        val effectiveDelay = max(delayMs, SNAPSHOT_THROTTLE_MS - (now - lastCaptureAt))
        val capture = Runnable {
            if (released || view.width <= 0 || view.height <= 0) return@Runnable
            lastCaptureAt = android.os.SystemClock.uptimeMillis()
            view.captureVisibleBitmap(mainHandler, onSnapshot)
        }
        pendingRunnable = capture
        mainHandler.postDelayed(capture, effectiveDelay)
    }

    fun release() {
        released = true
        pendingRunnable?.let { runnable ->
            handler?.removeCallbacks(runnable)
        }
        pendingRunnable = null
    }

    companion object {
        private const val SNAPSHOT_THROTTLE_MS = 180L
    }
}

private fun WebView.captureVisibleBitmap(
    handler: Handler,
    onSnapshot: (WebViewSnapshot) -> Unit
) {
    val activity = context.findActivity()
    val window = activity?.window
    val location = IntArray(2)
    getLocationInWindow(location)
    val sourceRect = Rect(
        location[0],
        location[1],
        location[0] + width,
        location[1] + height
    )
    if (window != null && !sourceRect.isEmpty) {
        val copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(window, sourceRect, copy, { result ->
                if (result == PixelCopy.SUCCESS) {
                    onSnapshot(WebViewSnapshot(copy, Rect(sourceRect)))
                } else {
                    copy.recycle()
                    captureDrawnSnapshot(sourceRect)?.let(onSnapshot)
                }
            }, handler)
        }.onFailure {
            copy.recycle()
            captureDrawnSnapshot(sourceRect)?.let(onSnapshot)
        }
        return
    }
    captureDrawnSnapshot(sourceRect)?.let(onSnapshot)
}

private fun WebView.captureDrawnSnapshot(boundsInWindow: Rect): WebViewSnapshot? {
    if (width <= 0 || height <= 0) return null
    return runCatching {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            draw(AndroidCanvas(bitmap))
        }
        WebViewSnapshot(bitmap, Rect(boundsInWindow))
    }.getOrNull()
}

private fun WebView.maxVerticalScroll(): Int {
    return ((contentHeight * scale) - height)
        .roundToInt()
        .coerceAtLeast(0)
}

private data class WebViewSnapshot(
    val bitmap: Bitmap,
    val boundsInWindow: Rect
)

private fun LayoutCoordinates.toAndroidWindowRect(): Rect {
    val bounds = boundsInWindow()
    return Rect(
        bounds.left.roundToInt(),
        bounds.top.roundToInt(),
        bounds.right.roundToInt(),
        bounds.bottom.roundToInt()
    )
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configurePlatformWebView(platform: PlatformLinkKind?) {
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.loadsImagesAutomatically = true
    settings.mediaPlaybackRequiresUserGesture = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.userAgentString = buildUserAgent(settings.userAgentString, platform)
}

private fun buildUserAgent(base: String?, platform: PlatformLinkKind?): String {
    val suffix = when (platform) {
        PlatformLinkKind.BILI -> "腕上RSS手机端/BiliWebView"
        PlatformLinkKind.DOUYIN -> "腕上RSS手机端/DouyinWebView"
        null -> "腕上RSS手机端/WebView"
    }
    return listOfNotNull(base?.takeIf { it.isNotBlank() }, suffix).joinToString(" ")
}

private fun handleNonWebUrl(
    context: Context,
    webView: WebView?,
    url: String?,
    allowExternalDeepLinks: Boolean
): Boolean {
    val target = url?.trim().orEmpty()
    if (target.isBlank()) return false
    val scheme = Uri.parse(target).scheme?.lowercase().orEmpty()
    if (scheme == "http" || scheme == "https") return false
    val fallbackUrl = target.intentFallbackUrl()
    if (!allowExternalDeepLinks) {
        if (fallbackUrl != null) {
            webView?.loadUrl(fallbackUrl)
        }
        return true
    }
    openExternalUrl(context, target)
    return true
}

private fun String.intentFallbackUrl(): String? {
    val intent = runCatching {
        if (startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(this, Intent.URI_INTENT_SCHEME)
        } else {
            null
        }
    }.getOrNull()
    return intent
        ?.getStringExtra("browser_fallback_url")
        ?.takeIf { isHttpUrl(it) }
}

private fun openExternalUrl(context: Context, url: String) {
    val intent = runCatching {
        if (url.startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
    }.getOrNull()
    if (intent != null && runCatching { context.startActivity(intent) }.isSuccess) return

    val fallbackUrl = intent
        ?.getStringExtra("browser_fallback_url")
        ?.takeIf { isHttpUrl(it) }
    if (fallbackUrl != null) {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
        if (runCatching { context.startActivity(fallbackIntent) }.isSuccess) return
    }

    Toast.makeText(context, "没有可打开的应用", Toast.LENGTH_SHORT).show()
}

private fun isHttpUrl(url: String): Boolean {
    val scheme = Uri.parse(url).scheme?.lowercase()
    return scheme == "http" || scheme == "https"
}
