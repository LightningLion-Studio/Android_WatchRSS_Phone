package com.lightningstudio.watchrss.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchRSSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onScanClick = {
                            startActivity(Intent(this, QRScanActivity::class.java))
                        },
                        onBeianClick = {
                            val intent = Intent(Intent.ACTION_VIEW)
                            intent.data = Uri.parse("https://beian.miit.gov.cn/")
                            startActivity(intent)
                        },
                        onAboutClick = {
                            startActivity(Intent(this, AboutActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(onScanClick: () -> Unit, onBeianClick: () -> Unit, onAboutClick: () -> Unit) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // 右上角省略号按钮
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(600, delayMillis = 200)) +
                    scaleIn(
                        initialScale = 0.5f,
                        animationSpec = tween(600, delayMillis = 200, easing = FastOutSlowInEasing)
                    ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            IconButton(
                onClick = onAboutClick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "关于",
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(800)) +
                        slideInVertically(
                            initialOffsetY = { -100 },
                            animationSpec = tween(800, easing = FastOutSlowInEasing)
                        )
            ) {
                Text(
                    text = "腕上RSS",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(64.dp))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(800, delayMillis = 300)) +
                        scaleIn(
                            initialScale = 0.8f,
                            animationSpec = tween(800, delayMillis = 300, easing = FastOutSlowInEasing)
                        )
            ) {
                Button(
                    onClick = onScanClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "前往扫一扫",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 备案号在底部
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(800, delayMillis = 600)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            BeianNumber(onClick = onBeianClick)
        }
    }
}

@Composable
fun WatchRSSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = dynamicColorScheme(),
        content = content
    )
}

@Composable
fun dynamicColorScheme(): ColorScheme {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    return if (isDark) {
        darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF90CAF9),
            secondary = androidx.compose.ui.graphics.Color(0xFFCE93D8),
            tertiary = androidx.compose.ui.graphics.Color(0xFFA5D6A7)
        )
    } else {
        lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF1976D2),
            secondary = androidx.compose.ui.graphics.Color(0xFF7B1FA2),
            tertiary = androidx.compose.ui.graphics.Color(0xFF388E3C)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BeianNumber(onClick: () -> Unit) {
    val context = LocalContext.current
    val beianText = "浙ICP备2024111886号-5A"

    Text(
        text = beianText,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("备案号", beianText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "备案号已复制", Toast.LENGTH_SHORT).show()
            }
        )
    )
}
