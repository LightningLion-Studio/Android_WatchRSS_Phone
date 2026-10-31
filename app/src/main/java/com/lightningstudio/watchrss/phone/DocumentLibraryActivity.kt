package com.lightningstudio.watchrss.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

class DocumentLibraryActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchRssPhoneTheme {
                AdaptiveWindowScope(Modifier.fillMaxSize()) { windowInfo ->
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("文档库") },
                                navigationIcon = {
                                    IconButton(onClick = ::finish) {
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
                            Column(
                                modifier = Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                listOf(
                                    LegalDocument.PHONE_QA to "内容管理、蓝牙同步与常见问题",
                                    LegalDocument.WATCH_QA to "手表阅读、同步与常见问题",
                                    LegalDocument.MINI_PROGRAM_QA to "小程序阅读、云同步与常见问题"
                                ).forEach { (document, description) ->
                                    LegalDocumentSection(
                                        title = document.title,
                                        description = description,
                                        onClick = {
                                            startActivity(LegalDocumentActivity.createIntent(
                                                this@DocumentLibraryActivity, document
                                            ))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
