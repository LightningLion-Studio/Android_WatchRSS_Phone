package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lightningstudio.watchrss.phone.data.db.PhoneArticleEntity
import com.lightningstudio.watchrss.phone.data.importer.WebArticleImporter
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

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
                ArticleReaderScreen(
                    article = article,
                    invalidArticleId = articleId.isBlank(),
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

@Composable
private fun ArticleReaderScreen(
    article: PhoneArticleEntity?,
    invalidArticleId: Boolean,
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
                Button(onClick = onBack) {
                    Text(text = "返回")
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
                Button(onClick = onBack) {
                    Text(text = "返回")
                }
            }
            return@Surface
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = safeArticle.title.ifBlank { safeArticle.url },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            safeArticle.siteName.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack) {
                    Text(text = "返回")
                }
                if (safeArticle.url.isNotBlank() && !ImportedContentIds.isImportedContentUrl(safeArticle.url)) {
                    Button(onClick = { onOpenOriginal(safeArticle.url) }) {
                        Text(text = "原网页")
                    }
                }
            }
            if (!safeArticle.contentHtml.isNullOrBlank()) {
                HtmlArticleView(
                    article = safeArticle,
                    onOpenImportedArticle = onOpenImportedArticle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                PlainArticleView(
                    text = safeArticle.contentText
                        .ifBlank { safeArticle.excerpt }
                        .ifBlank { safeArticle.url },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HtmlArticleView(
    article: PhoneArticleEntity,
    onOpenImportedArticle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val html = remember(article.articleId, article.contentHash, article.updatedAt) {
        buildReaderHtml(article)
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        return shouldOpenImportedArticle(
                            targetUrl = request?.url?.toString(),
                            currentUrl = article.url,
                            onOpenImportedArticle = onOpenImportedArticle
                        )
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return shouldOpenImportedArticle(
                            targetUrl = url,
                            currentUrl = article.url,
                            onOpenImportedArticle = onOpenImportedArticle
                        )
                    }
                }
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.loadsImagesAutomatically = true
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(article.url, html, "text/html", "UTF-8", null)
        }
    )
}

private fun shouldOpenImportedArticle(
    targetUrl: String?,
    currentUrl: String,
    onOpenImportedArticle: (String) -> Unit
): Boolean {
    val url = targetUrl?.trim().orEmpty()
    if (!ImportedContentIds.isImportedContentUrl(url)) return false
    if (url.substringBefore('#') == currentUrl.substringBefore('#')) return false
    onOpenImportedArticle(url)
    return true
}

@Composable
private fun PlainArticleView(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.verticalScroll(rememberScrollState())
    )
}

private fun buildReaderHtml(article: PhoneArticleEntity): String {
    val title = escapeHtml(article.title.ifBlank { article.url })
    val site = escapeHtml(article.siteName)
    val body = article.contentHtml.orEmpty()
    return """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <meta charset="utf-8">
          <style>
            body {
              margin: 0;
              padding: 0 0 32px 0;
              color: #202124;
              background: #ffffff;
              font-family: system-ui, -apple-system, "Noto Sans CJK SC", sans-serif;
              font-size: 18px;
              line-height: 1.72;
              overflow-wrap: anywhere;
            }
            h1, h2, h3 { line-height: 1.28; }
            img, video, iframe { max-width: 100%; height: auto; }
            pre, code { white-space: pre-wrap; overflow-wrap: anywhere; }
            blockquote {
              margin-left: 0;
              padding-left: 14px;
              border-left: 4px solid #d0d7de;
              color: #4f5b66;
            }
            .reader-title {
              font-size: 0;
              height: 0;
              overflow: hidden;
            }
            .site {
              color: #5f6368;
              font-size: 14px;
              margin-bottom: 14px;
            }
          </style>
        </head>
        <body>
          <div class="reader-title">$title</div>
          ${if (site.isNotBlank()) """<div class="site">$site</div>""" else ""}
          $body
        </body>
        </html>
    """.trimIndent()
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
