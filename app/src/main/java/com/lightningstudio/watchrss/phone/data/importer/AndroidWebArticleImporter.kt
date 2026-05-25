package com.lightningstudio.watchrss.phone.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidWebArticleImporter(
    context: Context,
    private val staticImporter: WebArticleImporter = WebArticleImporter()
) {
    private val appContext = context.applicationContext
    @Volatile private var cachedReadabilityScript: String? = null

    suspend fun importUrl(input: String): ImportedWebArticle {
        val url = WebArticleImporter.normalizeUrl(input)
        val staticResult = runCatching {
            withContext(Dispatchers.IO) { staticImporter.importUrl(url) }
        }
        staticResult.getOrNull()
            ?.takeIf { it.hasReadableContent() }
            ?.let { return it }

        val readabilityResult = runCatching { extractWithReadability(url) }
        readabilityResult.getOrNull()
            ?.takeIf { extracted ->
                staticResult.isFailure ||
                    staticResult.getOrNull()?.contentHtml.isNullOrBlank() ||
                    extracted.contentText.length > (staticResult.getOrNull()?.contentText?.length ?: 0)
            }
            ?.let { return it }

        return staticResult.getOrElse { throwable ->
            readabilityResult.getOrElse { throw throwable }
        }
    }

    private suspend fun extractWithReadability(url: String): ImportedWebArticle {
        val readabilityScript = loadReadabilityScript()
        return withContext(Dispatchers.Main.immediate) {
            withTimeout(WEBVIEW_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val webView = WebView(appContext)
                    var completed = false

                    fun cleanup() {
                        runCatching { webView.stopLoading() }
                        runCatching { webView.loadUrl("about:blank") }
                        runCatching { webView.destroy() }
                    }

                    fun complete(result: Result<ImportedWebArticle>) {
                        if (completed || !continuation.isActive) return
                        completed = true
                        cleanup()
                        result.fold(
                            onSuccess = continuation::resume,
                            onFailure = continuation::resumeWithException
                        )
                    }

                    continuation.invokeOnCancellation {
                        if (!completed) {
                            completed = true
                            cleanup()
                        }
                    }

                    configureWebView(webView)
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            // Wait for the final onPageFinished. Redirects can trigger this repeatedly.
                        }

                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            val target = view ?: return
                            target.postDelayed({
                                if (!completed) {
                                    evaluateReadability(target, readabilityScript, url, ::complete)
                                }
                            }, READABILITY_SETTLE_MS)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                complete(Result.failure(
                                    IllegalStateException(error?.description?.toString() ?: "WebView 加载失败")
                                ))
                            }
                        }
                    }
                    webView.loadUrl(url)
                }
            }
        }
    }

    private suspend fun loadReadabilityScript(): String {
        cachedReadabilityScript?.let { return it }
        return withContext(Dispatchers.IO) {
            cachedReadabilityScript ?: appContext.assets
                .open("readability/Readability.js")
                .bufferedReader()
                .use { it.readText() }
                .also { cachedReadabilityScript = it }
        }
    }

    private fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
    }

    private fun evaluateReadability(
        webView: WebView,
        readabilityScript: String,
        url: String,
        complete: (Result<ImportedWebArticle>) -> Unit
    ) {
        webView.evaluateJavascript(readabilityScript) {
            webView.evaluateJavascript(READABILITY_EXTRACT_SCRIPT) { raw ->
                val article = runCatching {
                    parseReadabilityResult(url, raw)
                }
                complete(article)
            }
        }
    }

    private fun parseReadabilityResult(url: String, rawResult: String?): ImportedWebArticle {
        val json = decodeJavascriptString(rawResult)
            ?: throw IllegalStateException("Readability 未返回结果")
        val result = JSONObject(json)
        if (!result.optBoolean("ok", false)) {
            throw IllegalStateException(result.cleanString("error").ifBlank { "Readability 未提取到正文" })
        }
        val contentDocument = Jsoup.parseBodyFragment(result.cleanString("content"), url)
        contentDocument.outputSettings().prettyPrint(false)
        contentDocument.select("script,style,noscript,template,svg,form,button,nav,footer,header,aside").remove()
        val contentHtml = contentDocument.body().html().trim()
            .takeIf { it.isNotBlank() }
            ?.let { "<article>$it</article>" }
        val contentText = result.cleanString("textContent")
            .ifBlank { contentDocument.body().text().trim() }
        require(contentText.isNotBlank()) { "Readability 未提取到正文" }
        val title = result.cleanString("title")
            .ifBlank { WebArticleImporter.hostLabel(url) }
            .ifBlank { url }
            .take(MAX_TITLE_CHARS)
        val excerpt = result.cleanString("excerpt")
            .ifBlank { contentText.take(MAX_EXCERPT_CHARS) }
            .take(MAX_EXCERPT_CHARS)
        val articleId = WebArticleImporter.stableArticleId(url)
        return ImportedWebArticle(
            articleId = articleId,
            url = url,
            title = title,
            siteName = result.cleanString("siteName")
                .ifBlank { WebArticleImporter.hostLabel(url) },
            excerpt = excerpt,
            contentHtml = contentHtml,
            contentText = contentText,
            imageUrl = result.cleanString("imageUrl").ifBlank { null },
            contentHash = WebArticleImporter.sha256(contentHtml ?: contentText)
        )
    }

    private fun decodeJavascriptString(rawResult: String?): String? {
        val raw = rawResult?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return runCatching { JSONArray("[$raw]").getString(0) }.getOrElse { raw }
    }

    private fun ImportedWebArticle.hasReadableContent(): Boolean {
        if (!contentHtml.isNullOrBlank() && contentText.length >= MIN_READABLE_TEXT_CHARS) return true
        return contentText.length >= MIN_LONG_TEXT_CHARS
    }

    private fun JSONObject.cleanString(name: String): String {
        val value = optString(name, "").trim()
        return value.takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
    }

    private companion object {
        private const val WEBVIEW_TIMEOUT_MS = 18_000L
        private const val READABILITY_SETTLE_MS = 1_200L
        private const val MIN_READABLE_TEXT_CHARS = 120
        private const val MIN_LONG_TEXT_CHARS = 500
        private const val MAX_TITLE_CHARS = 160
        private const val MAX_EXCERPT_CHARS = 280

        private const val READABILITY_EXTRACT_SCRIPT = """
(function() {
  function meta(selector) {
    var el = document.querySelector(selector);
    return el ? (el.getAttribute("content") || el.content || "").trim() : "";
  }
  function absoluteUrl(value) {
    try {
      return value ? new URL(value, document.baseURI).href : "";
    } catch (e) {
      return value || "";
    }
  }
  function firstAttr(el, attrs) {
    for (var i = 0; i < attrs.length; i += 1) {
      var value = el.getAttribute(attrs[i]);
      if (value && value.trim()) return value.trim();
    }
    return "";
  }
  function sanitizeContent(content) {
    var parsed = document.implementation.createHTMLDocument("");
    parsed.body.innerHTML = content || "";
    parsed.body.querySelectorAll("script,style,noscript,template,svg,form,button,nav,footer,header,aside").forEach(function(el) {
      el.remove();
    });
    parsed.body.querySelectorAll("img").forEach(function(img) {
      var src = firstAttr(img, ["src", "data-src", "data-original", "data-lazy-src", "data-actualsrc", "data-url"]);
      if (src) img.setAttribute("src", absoluteUrl(src));
      img.removeAttribute("srcset");
    });
    parsed.body.querySelectorAll("video[src],source[src],iframe[src]").forEach(function(el) {
      var src = el.getAttribute("src");
      if (src) el.setAttribute("src", absoluteUrl(src));
    });
    return parsed.body.innerHTML;
  }
  try {
    if (typeof Readability === "undefined") {
      return JSON.stringify({ ok: false, error: "Readability 未加载" });
    }
    var parsed = new Readability(document.cloneNode(true), { keepClasses: false }).parse();
    if (!parsed) {
      return JSON.stringify({ ok: false, error: "Readability 未提取到正文" });
    }
    var content = sanitizeContent(parsed.content || "");
    var body = document.implementation.createHTMLDocument("");
    body.body.innerHTML = content;
    var firstImage = body.body.querySelector("img[src]");
    return JSON.stringify({
      ok: true,
      title: parsed.title || meta("meta[property='og:title']") || meta("meta[name='twitter:title']") || document.title || "",
      siteName: parsed.siteName || meta("meta[property='og:site_name']") || location.hostname || "",
      excerpt: parsed.excerpt || meta("meta[property='og:description']") || meta("meta[name='description']") || meta("meta[name='twitter:description']") || "",
      content: content,
      textContent: parsed.textContent || body.body.textContent || "",
      imageUrl: absoluteUrl(meta("meta[property='og:image']") || meta("meta[name='twitter:image']") || (firstImage ? firstImage.getAttribute("src") : ""))
    });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String((e && e.message) || e || "Readability 执行失败") });
  }
})()
"""
    }
}
