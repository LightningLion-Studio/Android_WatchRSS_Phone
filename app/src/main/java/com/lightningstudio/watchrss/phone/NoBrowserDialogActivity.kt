package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme

/**
 * App 内「无法打开链接」阻断式弹窗。
 *
 * 以半透明全屏窗口承载，遮住下层界面；返回键不会关闭弹窗，用户必须点击
 * 「知道了」明确确认后才能继续。由 [ExternalLinkOpener] 统一拉起。
 */
class NoBrowserDialogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val displayUrl = intent.getStringExtra(EXTRA_DISPLAY_URL).orEmpty()
        setContent {
            WatchRssPhoneTheme {
                // 阻断式弹窗：返回键不关闭，必须点按按钮确认。
                BackHandler { }
                NoBrowserDialogContent(displayUrl = displayUrl, onDismiss = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_DISPLAY_URL = "display_url"

        fun createIntent(context: Context, displayUrl: String): Intent =
            Intent(context, NoBrowserDialogActivity::class.java).apply {
                putExtra(EXTRA_DISPLAY_URL, displayUrl)
            }
    }
}

@Composable
private fun NoBrowserDialogContent(displayUrl: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
                Text(
                    text = "无法打开链接",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = buildString {
                        append("设备上没有可打开此链接的应用（浏览器）。")
                        if (displayUrl.isNotBlank()) {
                            append("\n\n")
                            append(displayUrl)
                        }
                        append("\n\n请安装或启用浏览器后重试。")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("知道了") }
            }
        }
    }
}
