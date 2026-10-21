package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** This phone asks an already authorized device for the account key; both users compare the same code. */
@Composable
internal fun CloudApprovalDialog(service: PhoneCloudSyncService, onAuthorized: (CloudBootstrap) -> Unit, onDismiss: () -> Unit) {
    val state by service.approvals.state.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(service) {
        try {
            service.approvals.begin()
            while (true) {
                try { service.approvals.poll() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!retryableCloudApprovalFailure(e)) throw e
                    delay(1500)
                    continue
                }
                when (service.approvals.state.value.phase) {
                    CloudApprovalPhase.DELIVERED -> { onAuthorized(service.loadBootstrap()); break }
                    CloudApprovalPhase.EXPIRED, CloudApprovalPhase.CANCELLED -> break
                    else -> delay(1500)
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { if (service.approvals.state.value.phase != CloudApprovalPhase.EXPIRED) error = "授权未完成，请重新发起" }
    }
    val finished = error != null || state.phase in setOf(CloudApprovalPhase.EXPIRED, CloudApprovalPhase.CANCELLED)
    val comparing = !finished && state.code != null && !state.locallyConfirmed
    AlertDialog(
        onDismissRequest = {},
        title = { Text("请求已登录设备授权") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    error != null -> Text(error!!)
                    state.phase == CloudApprovalPhase.EXPIRED -> Text("请求已过期，请重新发起。")
                    state.phase == CloudApprovalPhase.CANCELLED -> Text("授权已取消。两边的码不一致时，请确认是你本人在操作后重新发起。")
                    state.code != null -> {
                        Text(if (state.locallyConfirmed) "已核对，等待已登录设备完成授权…" else "请在已登录的手机或小程序上核对此码：")
                        Text(
                            formatApprovalCode(state.code!!),
                            style = MaterialTheme.typography.displaySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!state.locallyConfirmed) Text("两边完全一致时，请在两台设备上都点“一致”。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text("请打开已登录同一账号的手机 App 或微信小程序，按提示开始核对。\n\n此页面保持打开，5 分钟内有效。")
                }
            }
        },
        confirmButton = {
            if (comparing) TextButton(onClick = { scope.launch {
                try { service.approvals.confirmMatches() } catch (e: CancellationException) { throw e } catch (_: Exception) { if (service.approvals.state.value.phase != CloudApprovalPhase.EXPIRED) error = "授权验证失败，请重新发起" }
            } }) { Text("一致") }
            else if (finished) TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            if (!finished) TextButton(onClick = { scope.launch {
                try { service.approvals.reject() } catch (e: CancellationException) { throw e } catch (_: Exception) { } finally { onDismiss() }
            } }) { Text(if (comparing) "不一致" else "取消") }
        }
    )
}
