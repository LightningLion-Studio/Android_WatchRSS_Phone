package com.lightningstudio.watchrss.phone.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun SupportLogConsent(message: SupportMessage, agree: () -> Unit, decline: () -> Unit, human: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (message.logState) {
            "" -> {
                Text("经您同意后，收集手机及可读取的手表诊断日志，加密上传到日志服务，供人工客服排查。")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = agree, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDE8DF), contentColor = Color(0xFF3E5746)), modifier = Modifier.testTag("support_log_agree")) { Text("同意") }
                    Button(onClick = decline, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFECE0DF), contentColor = Color(0xFF70504E)), modifier = Modifier.testTag("support_log_decline")) { Text("不同意") }
                }
            }
            "uploading" -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(message.logDetail) }
            "declined" -> Text("已取消日志上传。您仍可去群里寻找人工客服。")
            "failed" -> {
                Text(message.logDetail, color = MaterialTheme.colorScheme.error)
                Button(onClick = agree, modifier = Modifier.testTag("support_log_retry")) { Text("重试上传日志") }
            }
            "uploaded" -> {
                Text("日志上传成功。请复制这串报错代码，去群里寻找人工客服。")
                SelectionContainer { Text(message.logCode, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("support_log_code")) }
                if (message.logDetail.isNotBlank()) Text(message.logDetail, style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("报错代码", message.logCode))
                    Toast.makeText(context, "报错代码已复制", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.testTag("support_log_copy")) { Text("点击复制") }
            }
        }
        if (message.logState in listOf("uploaded", "declined", "failed")) {
            FilledTonalButton(onClick = human, modifier = Modifier.testTag("support_log_human")) { Text("寻找人工客服") }
        }
    }
}
