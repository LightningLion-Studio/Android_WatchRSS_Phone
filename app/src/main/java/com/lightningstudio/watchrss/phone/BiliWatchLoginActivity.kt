package com.lightningstudio.watchrss.phone

import android.view.WindowManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.connection.bili.PhoneBiliGateway
import com.lightningstudio.watchrss.phone.connection.bili.WatchBiliLoginSession
import com.lightningstudio.watchrss.phone.ui.SupportContactInlineFooter
import com.lightningstudio.watchrss.phone.ui.generateQRCode
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only a valid session initiated by the connected watch may open this login page. */
class BiliWatchLoginActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_SESSION = "bili_login_session"
        fun createIntent(context: Context, session: Long): Intent =
            Intent(context, BiliWatchLoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SESSION, session)
    }

    private var qrUrl by mutableStateOf("")
    private var status by mutableStateOf("正在准备登录")
    private var webLogin by mutableStateOf(false)
    private var webView: WebView? = null
    private val gateway = PhoneBiliGateway()
    private var loginJob: Job? = null
    private var sessionToken = 0L
    private var waitingForWatch by mutableStateOf(true)
    private fun acceptSession(intent: Intent): Boolean {
        val token = intent.getLongExtra(EXTRA_SESSION, 0L)
        if (token == 0L || !WatchBiliLoginSession.isCurrent(token)) return false
        loginJob?.cancel()
        sessionToken = token
        waitingForWatch = false
        webLogin = false
        requestQrCode()
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSession(intent)
    }

    override fun onDestroy() {
        loginJob?.cancel()
        webView?.destroy()
        if (isFinishing) WatchBiliLoginSession.fail("手机已结束登录，请重新连接", sessionToken)
        super.onDestroy()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!acceptSession(intent)) {
            finish()
            return
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                if (!waitingForWatch && !WatchBiliLoginSession.isCurrent(sessionToken)) {
                    loginJob?.cancel()
                    qrUrl = ""
                    webLogin = false
                    waitingForWatch = true
                    status = "连接已断开或登录已超时，请在手表重新连接"
                }
            }
        }
        setContent {
            WatchRssPhoneTheme {
                if (waitingForWatch) {
                    Column(
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text("为手表登录 B 站", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                        Text("请在 Watch S 的腕上RSS中重新发起 B 站登录。")
                        Text(status)
                        TextButton(onClick = ::finish) { Text("返回") }
                    }
                } else if (webLogin) PasswordLoginPage(
                    onWebViewReady = { webView = it },
                    onComplete = ::completeFromWebCookies,
                    onBack = { webLogin = false; requestQrCode() }
                ) else QrLoginPage(
                    qrUrl = qrUrl,
                    status = status,
                    onOpenBili = ::openQrUrlInBili,
                    onPasswordLogin = { loginJob?.cancel(); webLogin = true },
                    onRefresh = ::requestQrCode,
                    onCancel = ::finish
                )
            }
        }
    }

    private fun requestQrCode() {
        loginJob?.cancel()
        qrUrl = ""
        val token = sessionToken
        loginJob = lifecycleScope.launch {
            status = "正在获取二维码"
            runCatching {
                withContext(Dispatchers.IO) { gateway.requestQrCode() }
            }.onSuccess { response ->
                val key = response.optString("key")
                qrUrl = response.optString("url")
                if (key.isBlank() || qrUrl.isBlank()) {
                    status = "未能取得二维码"
                    WatchBiliLoginSession.fail(status, token)
                } else {
                    status = "请在 B 站确认登录"
                    pollQrCode(key, token)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                status = "二维码获取失败：${error.message ?: "未知错误"}"
                WatchBiliLoginSession.fail(status, token)
            }
        }
    }

    private suspend fun pollQrCode(key: String, token: Long) {
        while (kotlin.coroutines.coroutineContext.isActive && !webLogin && WatchBiliLoginSession.isCurrent(token)) {
            delay(1_600)
            val result = runCatching {
                withContext(Dispatchers.IO) { gateway.pollQrCode(key) }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                status = "等待 B 站响应：${error.message ?: "网络错误"}"
                continue
            }
            when (result.optString("status")) {
                "success" -> {
                    WatchBiliLoginSession.complete(
                        cookie = result.getString("cookie"),
                        refreshToken = result.optString("refreshToken"), token = token
                    )
                    status = "登录完成，正在同步到手表"
                    return
                }
                "scanned" -> status = "已扫码，请在 B 站确认"
                "expired" -> {
                    status = "二维码已过期，请刷新"
                    WatchBiliLoginSession.fail(status, token)
                    return
                }
            }
        }
    }

    private fun openQrUrlInBili() {
        if (qrUrl.isBlank()) return
        // 没有可打开的应用（B 站/浏览器）时由 openExternally 弹出阻断式提示。
        if (openExternally(qrUrl)) {
            status = "已打开 B 站，请完成确认"
        }
    }

    private fun completeFromWebCookies() {
        val cookie = CookieManager.getInstance().getCookie("https://www.bilibili.com/").orEmpty()
        if (!cookie.contains("SESSDATA=")) {
            status = "尚未检测到登录状态，请完成手机号和密码登录后重试"
            return
        }
        WatchBiliLoginSession.complete(cookie, refreshToken = "", token = sessionToken)
        status = "登录完成，正在同步到手表"
        webLogin = false
    }
}

@androidx.compose.runtime.Composable
private fun QrLoginPage(
    qrUrl: String,
    status: String,
    onOpenBili: () -> Unit,
    onPasswordLogin: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("为手表登录 B 站", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text("此页面由手表请求打开，完成后凭据仅同步到手表。")
        Text(
            "连接手表指引\n1. 确认手机蓝牙已开启，且 OPPO Watch S 已连接。\n2. 保持手机端腕上RSS在前台，并让手表停留在登录页面。\n3. 完成登录后回到手表，凭据会自动同步。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        if (qrUrl.isNotBlank()) {
            Image(
                bitmap = generateQRCode(qrUrl, 640).asImageBitmap(),
                contentDescription = "B站登录二维码",
                modifier = Modifier.size(250.dp)
            )
        }
        Text(status)
        Button(onClick = onOpenBili, enabled = qrUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("在 B 站中打开")
        }
        OutlinedButton(onClick = onPasswordLogin, modifier = Modifier.fillMaxWidth()) {
            Text("手机号 / 密码登录")
        }
        TextButton(onClick = onRefresh) { Text("刷新二维码") }
        TextButton(onClick = onCancel) { Text("取消") }
        SupportContactInlineFooter(
            hint = "手表登录连接不上？联系客服并提供当前状态"
        )
    }
}

@androidx.compose.runtime.Composable
private fun PasswordLoginPage(
    onWebViewReady: (WebView) -> Unit,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "使用手机号和密码登录",
            modifier = Modifier.padding(24.dp),
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
        )
        Text("请在 B 站网页内完成登录；密码不会经过腕上RSS。", modifier = Modifier.padding(horizontal = 24.dp))
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    CookieManager.getInstance().setAcceptCookie(true)
                    loadUrl("https://passport.bilibili.com/login")
                    onWebViewReady(this)
                }
            }
        )
        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("我已完成登录")
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("返回二维码") }
    }
}
