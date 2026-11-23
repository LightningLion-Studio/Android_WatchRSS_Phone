package com.lightningstudio.watchrss.phone.connection.watchs

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun WatchSBlePairingDialog(
    status: WatchSBlePairings.Status,
    begin: suspend () -> Unit,
    forget: suspend () -> Unit,
    cancelCode: () -> Unit,
    dismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(action: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                action()
                confirming = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message = "操作未完成，请重试"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(status) {
        if (status is WatchSBlePairings.Status.Code) {
            delay((status.expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            cancelCode()
            message = "配对码已过期"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (confirming) "解除 Watch S 配对？" else "Watch S 配对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (confirming) {
                    Text("手表下次连接时会清空同步数据。也可以在手表上立即清空。")
                } else {
                    when (status) {
                        WatchSBlePairings.Status.Idle -> Text("未配对")
                        WatchSBlePairings.Status.Paired -> Text("已配对。在手表上开始同步，并保持手机停留在腕上RSS。")
                        WatchSBlePairings.Status.ResetPending -> Text("配对已解除，等待手表连接并清空同步数据。")
                        is WatchSBlePairings.Status.Code -> {
                            Text("在手表上输入完整配对码")
                            Text(status.text, fontFamily = FontFamily.Monospace)
                            Text("配对码 5 分钟内有效")
                        }
                    }
                }
                message?.let { Text(it) }
            }
        },
        confirmButton = {
            when {
                confirming -> TextButton(onClick = { run(forget) }, enabled = !busy) { Text("确认解绑") }
                status == WatchSBlePairings.Status.Paired -> TextButton(onClick = { confirming = true }) {
                    Text("解除配对")
                }
                status !is WatchSBlePairings.Status.Code -> TextButton(onClick = { run(begin) }, enabled = !busy) {
                    Text("生成配对码")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirming) confirming = false else dismiss() }, enabled = !busy) {
                Text(if (confirming) "取消" else "关闭")
            }
        }
    )
}
