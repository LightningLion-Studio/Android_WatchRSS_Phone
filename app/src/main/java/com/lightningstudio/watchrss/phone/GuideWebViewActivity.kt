package com.lightningstudio.watchrss.phone

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

class GuideWebViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        setContent {
            WatchRssPhoneTheme {
                GuideWebViewScreen(
                    url = url,
                    title = title,
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
            return Intent(context, GuideWebViewActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_URL, url)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideWebViewScreen(
    url: String,
    title: String,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title.ifBlank { url },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenExternal) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "外部打开")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SimpleWebView(
                url = url,
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
private fun SimpleWebView(
    url: String,
    onCreated: (WebView) -> Unit,
    onProgress: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                onCreated(this)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
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
                loadUrl(url)
            }
        },
        update = {}
    )
}

private fun shouldOpenExternally(context: Context, url: String?): Boolean {
    val target = url?.trim().orEmpty()
    if (target.isBlank()) return false
    val scheme = Uri.parse(target).scheme?.lowercase().orEmpty()
    if (scheme == "http" || scheme == "https" || scheme == "file") return false
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
    if (fallbackUrl != null && context.tryOpenExternal(fallbackUrl)) return

    // 弹不出任何应用时，用 App 内阻断式弹窗明确告知，而不是 Toast 一闪而过。
    context.showUnopenableDialog(url)
}

private fun isHttpUrl(url: String): Boolean {
    val scheme = Uri.parse(url).scheme?.lowercase()
    return scheme == "http" || scheme == "https"
}
