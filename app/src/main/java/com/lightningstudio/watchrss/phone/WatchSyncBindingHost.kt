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

/** Shows why a sync stopped before touching the watch; binding is the only way forward from an unbound watch. */
@Composable
internal fun WatchSyncBindingHost(viewModel: MainViewModel) {
    val context = LocalContext.current
    val prompt by viewModel.watchSyncBindingPrompt.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.watchSyncBindingEvents.collect { event ->
            if (event == WatchSyncBindingEvent.OpenWatchPairing) context.startActivity(WatchPairingActivity.createIntent(context))
        }
    }
    when (prompt) {
        WatchSyncBindingEvent.RequireWatchBinding -> AlertDialog(
            onDismissRequest = viewModel::dismissWatchSyncBindingPrompt,
            title = { Text("先绑定手表") },
            text = { Text("这块手表还没有登录腕上RSS账号。扫码绑定后，手表会登录当前账号并开启云同步，然后再同步。") },
            confirmButton = { TextButton(onClick = viewModel::openWatchPairing) { Text("扫码绑定") } },
            dismissButton = { TextButton(onClick = viewModel::dismissWatchSyncBindingPrompt) { Text("取消") } }
        )
        WatchSyncBindingEvent.WatchAccountMismatch -> AlertDialog(
            onDismissRequest = viewModel::dismissWatchSyncBindingPrompt,
            title = { Text("手表登录了其他账号") },
            text = { Text("请先在手表「我的 → 云同步」底部退出原账号，再回到这里扫码绑定。") },
            confirmButton = { TextButton(onClick = viewModel::dismissWatchSyncBindingPrompt) { Text("知道了") } }
        )
        else -> Unit
    }
}
