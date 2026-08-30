package com.lightningstudio.watchrss.phone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lightningstudio.watchrss.phone.ContactDeveloperActivity

const val WATCH_RSS_QQ_GROUP_NUMBER = "1083518433"
const val WATCH_RSS_QQ_GROUP_URL = "https://qm.qq.com/q/cJNTQuxfoW"
const val WATCH_RSS_QQ_JOIN_INTENT_URL =
    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr" +
        "%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D1083518433"

/**
 * 支持联系客服的优先级。
 * - INLINE：低优先级，仅在页面内以小链接/按钮形式露出。
 * - DIALOG_FOOTER：中优先级，在弹窗底部放置帮助入口。
 * - BLOCKING：高优先级，使用全屏阻断式 Alert 强制用户处理，并附带客服入口。
 */
enum class SupportContactPriority {
    INLINE,
    DIALOG_FOOTER,
    BLOCKING
}

/**
 * 阻断式客服 Alert 的状态数据。
 */
data class SupportContactAlertUi(
    val title: String,
    val message: String,
    val errorDetails: String? = null
)

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

fun Context.openContactDeveloperActivity() {
    startActivity(Intent(this, ContactDeveloperActivity::class.java))
}

fun Context.joinWatchRssQQGroup() {
    val qqIntent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse(WATCH_RSS_QQ_JOIN_INTENT_URL)
    }
    if (runCatching { startActivity(qqIntent) }.isFailure) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WATCH_RSS_QQ_GROUP_URL))) }
    }
}

fun Context.copyWatchRssQQGroupNumber() {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("QQ群号", WATCH_RSS_QQ_GROUP_NUMBER))
    Toast.makeText(this, "群号已复制", Toast.LENGTH_SHORT).show()
}

/**
 * 统一的客服入口小按钮。适用于卡片、空状态等 INLINE 位置。
 */
@Composable
fun SupportContactInlineButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "帮助与客服",
    hint: String? = null
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Default.HelpOutline,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = if (hint != null) "$hint · $label" else label,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * 统一的客服入口 footer。适合放在错误提示、空状态下方。
 */
@Composable
fun SupportContactFooter(
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SupportContactInlineButton(
            onClick = onClick,
            hint = hint,
            label = "联系客服（QQ群 $WATCH_RSS_QQ_GROUP_NUMBER）"
        )
    }
}

/**
 * 更轻量的客服入口 footer，直接打开客服页而不是先弹出二维码。
 * 适合需要减少一步点击的 INLINE 场景。
 */
@Composable
fun SupportContactInlineFooter(
    hint: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    SupportContactFooter(
        hint = hint,
        onClick = { context.openContactDeveloperActivity() },
        modifier = modifier
    )
}

/**
 * 统一的 QQ 群二维码弹窗。用于 DIALOG_FOOTER 层级，半阻断式。
 */
@Composable
fun SupportContactQrDialog(
    onDismiss: () -> Unit,
    title: String = "联系我们"
) {
    val qrBitmap = remember { generateQRCode(WATCH_RSS_QQ_GROUP_URL, 512).asImageBitmap() }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QrCode, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(title)
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("扫描二维码加入 QQ 群")
                Surface(
                    modifier = Modifier.size(220.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White
                ) {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "QQ群二维码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
                Text(
                    text = "群号：$WATCH_RSS_QQ_GROUP_NUMBER",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { context.joinWatchRssQQGroup(); onDismiss() }) {
                Text("一键加群")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("返回")
            }
        }
    )
}

/**
 * 统一的阻断式客服 Alert。用于 BLOCKING 优先级：同步失败、登录失败等强制处理场景。
 */
@Composable
fun SupportContactBlockingAlert(
    title: String,
    message: String,
    errorDetails: String? = null,
    confirmText: String = "联系客服",
    dismissText: String = "稍后再试",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HelpOutline, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(title)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
                errorDetails?.takeIf { it.isNotBlank() }?.let {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Text(
                    text = "可加入 QQ 群 $WATCH_RSS_QQ_GROUP_NUMBER 寻求帮助",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    context.joinWatchRssQQGroup()
                    onDismiss()
                }
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}

/**
 * 统一的客服入口展示组件，根据优先级选择 INLINE / DIALOG_FOOTER / BLOCKING 形态。
 */
@Composable
fun SupportContactEntry(
    priority: SupportContactPriority,
    onShowQr: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "遇到问题",
    dialogTitle: String = "联系我们",
    blockingTitle: String = "需要帮助",
    blockingMessage: String = "当前操作遇到异常，是否联系客服协助解决？",
    blockingErrorDetails: String? = null,
    blockingVisible: Boolean = false,
    onBlockingDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    when (priority) {
        SupportContactPriority.INLINE -> {
            SupportContactFooter(
                hint = hint,
                onClick = { context.openContactDeveloperActivity() },
                modifier = modifier
            )
        }
        SupportContactPriority.DIALOG_FOOTER -> {
            SupportContactFooter(
                hint = hint,
                onClick = onShowQr,
                modifier = modifier
            )
        }
        SupportContactPriority.BLOCKING -> {
            if (blockingVisible) {
                SupportContactBlockingAlert(
                    title = blockingTitle,
                    message = blockingMessage,
                    errorDetails = blockingErrorDetails,
                    onDismiss = onBlockingDismiss
                )
            }
        }
    }
}
