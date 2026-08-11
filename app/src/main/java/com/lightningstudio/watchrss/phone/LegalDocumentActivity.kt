package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

class LegalDocumentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val document = LegalDocument.fromName(intent.getStringExtra(EXTRA_DOCUMENT))
        setContent {
            WatchRssPhoneTheme {
                LegalDocumentScreen(
                    title = document.title,
                    content = resources.openRawResource(document.rawResource).bufferedReader().use {
                        it.readText()
                    },
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
    val rawResource: Int
) {
    USER_AGREEMENT("用户协议", R.raw.phone_user_agreement),
    PRIVACY_POLICY("隐私政策", R.raw.phone_privacy_policy);

    companion object {
        fun fromName(value: String?): LegalDocument =
            entries.firstOrNull { it.name == value } ?: PRIVACY_POLICY
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalDocumentScreen(
    title: String,
    content: String,
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
                Text(
                    text = content,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                )
            }
        }
    }
}
