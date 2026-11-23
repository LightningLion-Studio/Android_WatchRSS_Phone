package com.lightningstudio.watchrss.phone.connection.watchs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun WatchSBleBindingPage(
    status: WatchSBleBindings.Status,
    begin: () -> Unit,
    unbind: () -> Unit,
    close: () -> Unit
) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Watch S", style = MaterialTheme.typography.headlineMedium)
                val name = status.boundName
                Text(if (name == null) "未绑定" else "已绑定：$name")
                if (status.waiting) Text("请在 2 分钟内从手表发起同步。")
                if (status.hasResets) Text("有手表等待清空同步数据。")
                if (name == null) {
                    Button(onClick = begin, enabled = !status.waiting && status.request == null) {
                        Text("连接新手表")
                    }
                } else {
                    Button(onClick = unbind) { Text("解除绑定") }
                }
                TextButton(onClick = close) { Text("返回") }
            }
        }
    }
}

@Composable
internal fun WatchSBleBindingPrompt(request: WatchSBleBindings.Request, decide: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { decide(false) },
        title = { Text("允许 ${request.name} 同步？") },
        confirmButton = { TextButton(onClick = { decide(true) }) { Text("允许") } },
        dismissButton = { TextButton(onClick = { decide(false) }) { Text("拒绝") } }
    )
}
