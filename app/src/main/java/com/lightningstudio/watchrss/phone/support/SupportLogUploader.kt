package com.lightningstudio.watchrss.phone.support

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only constructed and started after the user presses the log-consent button. */
internal class SupportLogUploader(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun upload(log: String, status: (String) -> Unit): String = withContext(Dispatchers.Main) {
        val webView = WebView(context.applicationContext)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            withTimeout(90_000) {
                suspendCancellableCoroutine { continuation ->
                    fun failure() {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("日志上传失败，请检查网络后重试，或直接寻找人工客服。"))
                    }
                    val bridge = object {
                        @JavascriptInterface fun ready() { main.post {
                            if (continuation.isActive) webView.evaluateJavascript("window.uploadSupportLog(${JSONObject.quote(log)});", null)
                        } }
                        @JavascriptInterface fun status(text: String) { main.post {
                            if (continuation.isActive) status(text.take(80))
                        } }
                        @JavascriptInterface fun success(code: String) { main.post {
                            if (continuation.isActive) {
                                if (code.matches(Regex("[0-9]{6}"))) continuation.resume(code) else failure()
                            }
                        } }
                        @JavascriptInterface fun failure(message: String) { main.post { failure() } }
                    }
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.allowFileAccess = false
                    webView.settings.allowContentAccess = false
                    webView.addJavascriptInterface(bridge, "SupportLog")
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url
                            if (url.host != "appassets.androidplatform.net") return null
                            val path = url.path.orEmpty().removePrefix("/")
                            if (path.contains("..") || !path.startsWith("support_log_upload/")) return WebResourceResponse("text/plain", "UTF-8", null)
                            return runCatching { WebResourceResponse(if (path.endsWith(".js")) "text/javascript" else "text/html", "UTF-8", context.assets.open(path)) }.getOrElse { WebResourceResponse("text/plain", "UTF-8", null) }
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) failure()
                        }
                    }
                    webView.loadUrl("https://appassets.androidplatform.net/support_log_upload/index.html")
                }
            }
        } finally {
            webView.removeJavascriptInterface("SupportLog")
            webView.stopLoading()
            webView.destroy()
        }
    }
}

internal fun redactSupportLog(text: String): String = text
    .replace(Regex("(?i)(\"(?:access_token|refresh_token|password|authorization|cookie|secret|api_key|token)\"\\s*:\\s*\")[^\"]*\""), "$1[redacted]\"")
    .replace(Regex("(?i)(bearer\\s+)[^\\s\"']+"), "$1[redacted]")
    .replace(Regex("(?i)((?:access_token|refresh_token|password|passwd|authorization|cookie|secret|api_key|token)\\s*[=:]\\s*)[^\\s,;]+"), "$1[redacted]")
    .replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[redacted-token]")
    .replace(Regex("(https?://[^\\s?#]+)\\?[^\\s]+"), "$1?[redacted]")
