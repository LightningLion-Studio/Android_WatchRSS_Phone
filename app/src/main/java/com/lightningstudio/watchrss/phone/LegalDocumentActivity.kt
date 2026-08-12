package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import com.lightningstudio.watchrss.phone.network.withWatchRssAppVersionHeader

class LegalDocumentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val document = LegalDocument.fromName(intent.getStringExtra(EXTRA_DOCUMENT))
        val repository = LegalDocumentRepository()
        setContent {
            WatchRssPhoneTheme {
                var reloadKey by rememberSaveable { mutableIntStateOf(0) }
                var content by rememberSaveable(document) { mutableStateOf<String?>(null) }
                var isLoading by rememberSaveable(document) { mutableStateOf(true) }

                LaunchedEffect(document, reloadKey) {
                    isLoading = true
                    content = runCatching { repository.fetch(document) }.getOrNull()
                    isLoading = false
                }

                LegalDocumentScreen(
                    title = document.title,
                    content = content,
                    isLoading = isLoading,
                    onRetry = { reloadKey += 1 },
                    onBack = ::finish
                )
            }
        }
    }

    companion object {
        private const val EXTRA_DOCUMENT = "legal_document"

        fun createIntent(context: Context, document: LegalDocument): Intent =
            Intent(context, LegalDocumentActivity::class.java)
                .putExtra(EXTRA_DOCUMENT, document.name)
    }
}

enum class LegalDocument(
    val title: String,
    val path: String
) {
    USER_AGREEMENT("用户协议", "/functions/v1/legal/phone/user-agreement"),
    PRIVACY_POLICY("隐私政策", "/functions/v1/legal/phone/privacy-policy");

    companion object {
        fun fromName(value: String?): LegalDocument =
            entries.firstOrNull { it.name == value } ?: PRIVACY_POLICY
    }
}

internal class LegalDocumentRepository(
    private val baseUrl: String = BuildConfig.WATCHRSS_PRODUCTION_BACKEND_BASE_URL.trimEnd('/'),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetch(document: LegalDocument): String = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw IOException("legal document service unavailable")
        val request = Request.Builder()
            .url(baseUrl + document.path)
            .withWatchRssAppVersionHeader()
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("legal document request failed: ${response.code}")
            }
            response.body?.string()?.trim().orEmpty()
                .takeIf(String::isNotEmpty)
                ?: throw IOException("legal document response was empty")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalDocumentScreen(
    title: String,
    content: String?,
    isLoading: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    AdaptiveWindowScope(Modifier.fillMaxSize()) { windowInfo ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            AdaptiveContentFrame(
                windowInfo = windowInfo,
                mediumMaxWidth = 720.dp,
                expandedMaxWidth = 800.dp,
                modifier = Modifier.padding(padding)
            ) {
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    content != null -> Text(
                        text = content,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                    )

                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("协议正文加载失败，请检查网络后重试。", textAlign = TextAlign.Center)
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp)
                        ) {
                            Text("重新加载")
                        }
                    }
                }
            }
        }
    }
}
