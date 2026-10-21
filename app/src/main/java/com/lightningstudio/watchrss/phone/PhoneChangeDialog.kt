package com.lightningstudio.watchrss.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.account.PhoneChangeError
import com.lightningstudio.watchrss.phone.account.PhoneChangePhase
import com.lightningstudio.watchrss.phone.account.PhoneChangeState

@Composable
internal fun PhoneChangeDialog(
    state: PhoneChangeState,
    newPhone: String,
    onNewPhoneChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onVerify: () -> Unit,
    onDismiss: () -> Unit
) {
    val restart = state.error == PhoneChangeError.MFA_EXPIRED || state.error == PhoneChangeError.ACCOUNT_CHANGED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.phase == PhoneChangePhase.COMPLETE) "手机号已更换" else "更换手机号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state.phase) {
                    PhoneChangePhase.COMPLETE -> Text("当前手机号：${state.phoneMasked}\n之后请使用新号码登录，账号、云端资料和会员保持不变。")
                    PhoneChangePhase.ENTER_CODE -> {
                        Text("验证码已发送至 $newPhone")
                        OutlinedTextField(
                            value = code,
                            onValueChange = onCodeChange,
                            label = { Text("6 位验证码") },
                            singleLine = true,
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.semantics { contentType = ContentType.SmsOtpCode }
                        )
                    }
                    else -> {
                        Text("账号、云端资料和会员都会保留。更换后，旧号码将不能再登录。")
                        OutlinedTextField(
                            value = newPhone,
                            onValueChange = onNewPhoneChange,
                            label = { Text("新手机号") },
                            singleLine = true,
                            enabled = !state.busy && !restart,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                        )
                    }
                }
                state.error?.let { Text(phoneChangeErrorMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                state.phase == PhoneChangePhase.COMPLETE || restart -> TextButton(onClick = onDismiss) { Text(if (restart) "关闭" else "完成") }
                state.phase == PhoneChangePhase.ENTER_CODE ->
                    TextButton(onClick = onVerify, enabled = !state.busy && code.length == 6) { Text("确认更换") }
                else -> TextButton(onClick = onSendCode, enabled = !state.busy && newPhone.length == 11) { Text("发送验证码") }
            }
        },
        dismissButton = {
            if (state.phase != PhoneChangePhase.COMPLETE && !restart)
                TextButton(onClick = onDismiss, enabled = !state.busy) { Text("取消") }
        }
    )
}

internal fun phoneChangeErrorMessage(error: PhoneChangeError?): String = when (error) {
    PhoneChangeError.PHONE_IN_USE -> "该号码已绑定其他账号"
    PhoneChangeError.RATE_LIMITED -> "操作太频繁，请稍后再试"
    PhoneChangeError.INVALID_CODE -> "验证码不正确"
    PhoneChangeError.MFA_EXPIRED -> "身份验证已过期，请关闭后重新开始"
    PhoneChangeError.TRANSACTION_EXPIRED -> "验证码已过期，请重新发送"
    PhoneChangeError.ACCOUNT_CHANGED -> "账号已切换，请重新开始"
    PhoneChangeError.NETWORK, null -> "网络异常，请稍后重试"
}
