package com.lightningstudio.watchrss.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PaidServiceAgreementDialog(
    onOpenAgreement: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var accepted by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认购买手机版设备授权包") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("一次性支付 ¥6，当前账号增加 3 台手机授权容量，不自动续费。支付成功后七天内可在 App 订单页面无理由全额退款。")
                PaidAccessBoundaryText()
                TextButton(onClick = onOpenAgreement, modifier = Modifier.fillMaxWidth()) {
                    Text("查看《腕上RSS手机版付费服务协议》")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                    Text("我已阅读并同意付费服务协议，且明白这不是B站/抖音会员")
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = accepted) { Text("同意并购买 ¥6") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
