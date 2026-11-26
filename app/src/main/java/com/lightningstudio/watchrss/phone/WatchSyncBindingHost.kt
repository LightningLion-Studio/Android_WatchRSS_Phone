package com.lightningstudio.watchrss.phone

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lightningstudio.watchrss.phone.connection.bluetooth.WatchSyncBindingEvent
import com.lightningstudio.watchrss.phone.viewmodel.MainViewModel

/** Shows the required login or binding action before library data can be sent. */
@Composable
internal fun WatchSyncBindingHost(viewModel: MainViewModel) {
    val context = LocalContext.current
    val container = (context.applicationContext as PhoneCompanionApplication).container
    val cloudEntryAllowed = com.lightningstudio.watchrss.phone.cloud.rememberCloudSyncEntryAllowed(container)
    val prompt by viewModel.watchSyncBindingPrompt.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.watchSyncBindingEvents.collect { event ->
            if (event == WatchSyncBindingEvent.OpenWatchPairing && container.cloudSyncService.canOpenCloudSync()) context.startActivity(WatchPairingActivity.createIntent(context))
        }
    }
    when (prompt) {
        WatchSyncBindingEvent.RequirePhoneDeviceVerification -> PhoneDeviceVerificationPrompt(viewModel::dismissWatchSyncBindingPrompt)
        WatchSyncBindingEvent.RequirePhoneLogin -> PhoneSyncLoginDialog(
            onDismiss = viewModel::dismissWatchSyncBindingPrompt,
            onLogin = {
                openPhoneSyncReauthentication(context, viewModel::dismissWatchSyncBindingPrompt)
            }
        )
        WatchSyncBindingEvent.RequireWatchBinding -> if (cloudEntryAllowed) AlertDialog(
            onDismissRequest = viewModel::dismissWatchSyncBindingPrompt,
            title = { Text("先绑定手表") },
            text = { Text("这块手表还没有登录腕上RSS账号。点「扫码绑定」后手表会自动弹出二维码；如果没有弹出，请在手表上进入「我的 → 连接手机 → 扫码绑定」。绑定后手表会登录当前账号并开启云同步。") },
            confirmButton = { TextButton(onClick = viewModel::openWatchPairing) { Text("扫码绑定") } },
            dismissButton = { TextButton(onClick = viewModel::dismissWatchSyncBindingPrompt) { Text("取消") } }
        )
        WatchSyncBindingEvent.WatchAccountMismatch -> if (cloudEntryAllowed) AlertDialog(
            onDismissRequest = viewModel::dismissWatchSyncBindingPrompt,
            title = { Text("手表登录了其他账号") },
            text = { Text("请先在手表「我的 → 云同步」底部退出原账号，再回到这里扫码绑定。") },
            confirmButton = { TextButton(onClick = viewModel::dismissWatchSyncBindingPrompt) { Text("知道了") } }
        )
        else -> Unit
    }
}

@Composable
internal fun PhoneSyncLoginDialog(onDismiss: () -> Unit, onLogin: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新登录") },
        text = { Text("登录已失效，请重新登录") },
        confirmButton = { TextButton(onClick = onLogin) { Text("去登录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Navigation is non-destructive; AccountActivity pauses authentication through the repository. */
internal fun openPhoneSyncReauthentication(context: android.content.Context, dismiss: () -> Unit) {
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

/** Both hosts use exactly the same recovery navigation, including an already-valid account. */
@Composable
internal fun PhoneDeviceVerificationPrompt(onDismiss: () -> Unit) {
    val context = LocalContext.current
    PhoneDeviceVerificationDialog(onDismiss, onAccount = { openPhoneSyncReauthentication(context, onDismiss) })
}
