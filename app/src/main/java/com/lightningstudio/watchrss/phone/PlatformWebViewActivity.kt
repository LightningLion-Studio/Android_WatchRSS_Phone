package com.lightningstudio.watchrss.phone

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lightningstudio.watchrss.phone.platform.PlatformLinkKind
import com.lightningstudio.watchrss.phone.platform.PlatformLinkRouter
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

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
    val canGoBack = webView?.canGoBack() == true
    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title.ifBlank { url },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack) {
                    Text(text = "返回")
                }
                Button(
                    onClick = {
                        if (webView?.canGoBack() == true) {
                            webView?.goBack()
                        } else {
                            onBack()
                        }
                    },
                    enabled = canGoBack
                ) {
                    Text(text = "上一页")
                }
                Button(onClick = onOpenExternal) {
                    Text(text = "外部打开")
                }
            }
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            PlatformWebView(
                url = url,
                platform = platform,
                onCreated = { webView = it },
                onProgress = { progress = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
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
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                onCreated(this)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgress(newProgress)
                    }
                }
                webViewClient = object : WebViewClient() {
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
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.userAgentString = buildUserAgent(settings.userAgentString, platform)
                loadUrl(url)
            }
        },
        update = {}
    )
}

private fun buildUserAgent(base: String?, platform: PlatformLinkKind?): String {
    val suffix = when (platform) {
        PlatformLinkKind.BILI -> "WatchRSSPhone/BiliWebView"
        PlatformLinkKind.DOUYIN -> "WatchRSSPhone/DouyinWebView"
        null -> "WatchRSSPhone/WebView"
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
