package com.lightningstudio.watchrss.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.adaptiveContentWidth
import com.lightningstudio.watchrss.phone.ui.theme.roundedCombinedClickable

private const val WATCH_RSS_QQ_GROUP_NUMBER = "1083518433"
private const val WATCH_RSS_QQ_GROUP_URL = "https://qm.qq.com/q/cJNTQuxfoW"
private const val BEIAN_MIIT_URL = "https://beian.miit.gov.cn/"

class ContactDeveloperActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme {
                val onBack = { finish() }
                ContactDeveloperScreen(
                    onBack = onBack,
                    onJoinQQ = {
                        val qqIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(
                                "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr" +
                                    "%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D1083518433"
                            )
                        }
                        // 未安装 QQ 时降级到浏览器；无浏览器时由 openExternally 弹出阻断式提示。
                        if (runCatching { startActivity(qqIntent) }.isFailure) {
                            openExternally(WATCH_RSS_QQ_GROUP_URL)
                        }
                    },
                    onBeianClick = {
                        openExternally(BEIAN_MIIT_URL)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDeveloperScreen(
    onBack: () -> Unit,
    onJoinQQ: () -> Unit,
    onBeianClick: () -> Unit
) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.QrCode, contentDescription = null)
                            Text("帮助与客服")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                AdaptiveContentFrame(
                    windowInfo = windowInfo,
                    mediumMaxWidth = 520.dp,
                    expandedMaxWidth = 560.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (windowInfo.isMediumOrExpanded) 40.dp else 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(animationSpec = tween(800)) +
                                    scaleIn(
                                        initialScale = 0.8f,
                                        animationSpec = tween(800, easing = FastOutSlowInEasing)
                                    )
                        ) {
                            val qrBitmap = remember {
                                generateQRCode(WATCH_RSS_QQ_GROUP_URL, 512)
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                Text(
                                    text = "加入QQ群",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "群号：$WATCH_RSS_QQ_GROUP_NUMBER",
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                ContactQrCard(bitmap = qrBitmap)

                                Button(
                                    onClick = onJoinQQ,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("一键加群", fontSize = 16.sp)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("QQ群号", WATCH_RSS_QQ_GROUP_NUMBER))
                                            Toast.makeText(context, "群号已复制", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("复制群号", fontSize = 14.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("QQ群链接", WATCH_RSS_QQ_GROUP_URL))
                                            Toast.makeText(context, "加群链接已复制", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("复制加群链接", fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // 备案号常显且底部居中：备案号是安全、可信、引以为傲的合规标识，
                // 不是需要弱化或隐藏的内容，因此不做延迟渐显、始终直接显示；
                // 底部居中与主流App对备案号的展示方式保持一致。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .adaptiveContentWidth(
                            windowInfo = windowInfo,
                            mediumMaxWidth = 520.dp,
                            expandedMaxWidth = 560.dp
                        )
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BeianNumberText(onClick = onBeianClick)
                }
            }
        }
    }
}

@Composable
private fun ContactQrCard(
    bitmap: Bitmap,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.sizeIn(maxWidth = 280.dp, maxHeight = 280.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QQ群二维码",
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BeianNumberText(onClick: () -> Unit) {
    val context = LocalContext.current
    val beianText = "浙ICP备2024111886号-5A"

    Text(
        text = beianText,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.roundedCombinedClickable(
            shape = RoundedCornerShape(8.dp),
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

fun generateQRCode(content: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }

    return bitmap
}
