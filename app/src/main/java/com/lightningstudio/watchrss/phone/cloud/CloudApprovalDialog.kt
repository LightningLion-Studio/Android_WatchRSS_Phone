package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The dialog appears before making the network request; all feedback stays in it. */
@Composable
internal fun CloudApprovalDialog(
    service: PhoneCloudSyncService,
    onAuthorized: (CloudBootstrap) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var requestId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var requesting by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        requesting = true
        error = null
        try {
            requestId = service.beginCodeApproval()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = cloudApprovalRequestError(failure)
        } finally {
            requesting = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (complete) "云同步已授权" else "请求旧设备批准") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    complete -> Text("此手机已获得云端解密密钥，可以使用云同步。")
                    requesting -> {
                        CircularProgressIndicator()
                        Text("正在通知已授权设备…")
                    }
                    requestId.isNotBlank() -> {
                        Text("请打开另一台已授权设备上的腕上RSS或微信小程序，查看弹窗中的 8 位配对码。")
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(8); error = null },
                            label = { Text("8 位配对码") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = !submitting,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("配对码 5 分钟内有效。", style = MaterialTheme.typography.bodySmall)
                        if (submitting) CircularProgressIndicator()
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                complete -> TextButton(onClick = onDismiss) { Text("完成") }
                !requesting && requestId.isBlank() -> TextButton(onClick = { attempt++ }) { Text("重试") }
                else -> TextButton(
                    enabled = !requesting && !submitting && code.length == 8,
                    onClick = {
                        submitting = true
                        error = null
                        scope.launch {
                            try {
                                if (service.redeemCodeApproval(requestId, code)) {
                                    onAuthorized(service.loadBootstrap())
                                    complete = true
                                } else {
                                    error = "密钥尚未就绪，请稍后重试"
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "配对未完成，请检查配对码。过期或连续输错 5 次后，请关闭弹窗重新请求。"
                            } finally {
                                submitting = false
                            }
                        }
                    }
                ) { Text(if (submitting) "正在验证…" else "验证并授权") }
            }
        },
        dismissButton = {
            if (!complete) TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        }
    )
}
