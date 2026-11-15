package com.lightningstudio.watchrss.phone

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.lightningstudio.watchrss.phone.account.PhoneDeviceVerificationRequired
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneLoginRequired

enum class PhoneSyncAccountPrompt { LOGIN, DEVICE }

internal fun phoneSyncAccountPrompt(error: Throwable): PhoneSyncAccountPrompt? = when (error) {
    is PhoneLoginRequired -> PhoneSyncAccountPrompt.LOGIN
    is PhoneDeviceVerificationRequired -> PhoneSyncAccountPrompt.DEVICE
    else -> null
}

@Composable
internal fun PhoneSyncAccountHost(prompt: PhoneSyncAccountPrompt?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    when (prompt) {
        PhoneSyncAccountPrompt.LOGIN -> PhoneSyncLoginDialog(onDismiss) { openPhoneSyncReauthentication(context, onDismiss) }
        PhoneSyncAccountPrompt.DEVICE -> PhoneDeviceVerificationDialog(onDismiss) {
            openPhoneSyncReauthentication(context, onDismiss)
        }
        null -> Unit
    }
}

@Composable
internal fun PhoneSyncLoginDialog(onDismiss: () -> Unit, onLogin: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("重新登录") },
        text = { Text("登录已失效，请重新登录") },
        confirmButton = { TextButton(onClick = onLogin) { Text("去登录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun openPhoneSyncReauthentication(context: Context, dismiss: () -> Unit) {
    dismiss()
    context.startActivity(AccountActivity.createIntent(context, finishAfterLogin = true, reauthenticate = true))
}

@Composable
internal fun PhoneDeviceVerificationDialog(onDismiss: () -> Unit, onAccount: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("验证设备身份") },
        text = { Text("手机设备身份需要验证，请前往账号页面完成验证") },
        confirmButton = { TextButton(onClick = onAccount) { Text("前往账号") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
