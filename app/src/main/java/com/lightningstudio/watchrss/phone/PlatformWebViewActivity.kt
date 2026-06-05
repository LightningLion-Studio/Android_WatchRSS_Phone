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
import android.view.PixelCopy
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.lightningstudio.watchrss.phone.platform.PlatformLinkKind
import com.lightningstudio.watchrss.phone.platform.PlatformLinkRouter
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlin.math.max
import kotlin.math.roundToInt

class PlatformWebViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val platform = PlatformLinkRouter.detect(url)

        setContent {
            WatchRssPhoneTheme {
                PlatformWebViewScreen(
                    url = url,
                    title = title,
                    platform = platform,
                    onBack = { finish() },
                    onOpenExternal = { openExternalUrl(this, url) }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"

        fun createIntent(context: Context, title: String, url: String): Intent {
            return Intent(context, PlatformWebViewActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_URL, url)
            }
        }
    }
}

@Composable
private fun PlatformWebViewScreen(
    url: String,
    title: String,
    platform: PlatformLinkKind?,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var webViewSnapshot by remember { mutableStateOf<WebViewSnapshot?>(null) }
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    var topBarBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    var bottomBarBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    val canGoBack = webView?.canGoBack() == true
    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    val webViewVerticalGap = 32.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // WebView 内容区域
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = topBarHeight + webViewVerticalGap,
                    bottom = bottomBarHeight + webViewVerticalGap
                )
        ) {
            PlatformWebView(
                url = url,
                platform = platform,
                onCreated = { webView = it },
                onProgress = { progress = it },
                onSnapshot = { webViewSnapshot = it },
                modifier = Modifier.fillMaxSize()
            )
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
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBack) {
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
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text("返回")
                }
                GlassButton(
                    onClick = {
                        if (webView?.canGoBack() == true) {
                            webView?.goBack()
                        } else {
                            onBack()
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
            .clickable(enabled = enabled, onClick = onClick)
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
    onCreated: (WebView) -> Unit,
    onProgress: (Int) -> Unit,
    onSnapshot: (WebViewSnapshot?) -> Unit,
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
                onCreated(this)
                configurePlatformWebView(platform)
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgress(newProgress)
                        if (newProgress == 100) {
                            snapshotter.request(view, onSnapshot)
                        }
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        snapshotter.request(view, onSnapshot)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        return shouldOpenExternally(context, request?.url?.toString())
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return shouldOpenExternally(context, url)
                    }
                }
                setOnScrollChangeListener { source, _, _, _, _ ->
                    snapshotter.request(source as? WebView, onSnapshot)
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

private fun shouldOpenExternally(context: Context, url: String?): Boolean {
    val target = url?.trim().orEmpty()
    if (target.isBlank()) return false
    val scheme = Uri.parse(target).scheme?.lowercase().orEmpty()
    if (scheme == "http" || scheme == "https") return false
    openExternalUrl(context, target)
    return true
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
