package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lightningstudio.watchrss.phone.AccountActivity
import com.lightningstudio.watchrss.phone.AccountSection
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardCloudSyncBanner() {
    val context = LocalContext.current
    val container = (context.applicationContext as? PhoneCompanionApplication)?.container ?: return
    val service = container.cloudSyncService
    val session by container.accountRepository.session.collectAsState()
    val state by service.state.collectAsState()
    val paused by service.paused.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Keep quota scoped to the visible account; never show the previous account's usage.
    var member by remember(session?.userId) { mutableStateOf<CloudMemberState?>(null) }
    var quotaFailed by remember(session?.userId) { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var actionFailed by remember { mutableStateOf(false) }
    val recordError by service.recordError.collectAsState()
    var ownerConflict by remember(session?.userId, state.phase) { mutableStateOf(service.libraryOwnerConflict()) }
    var showOwnerChoice by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session?.userId, lifecycle, refreshKey) {
        if (session == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    member = service.refreshDashboardMembership()
                    quotaFailed = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { quotaFailed = true }
                delay(60_000)
            }
        }
    }
    val hasKey = session != null && service.hasLocalAccountKey()
    val presentation = if (ownerConflict) CloudDashboardPresentation("暂停上传", "本机资料属于另一个账号，选择处理方式后继续同步", "选择处理方式")
        else cloudDashboardPresentation(session != null, hasKey, paused, state.phase, member)
    val used = member?.usedBytes?.coerceAtLeast(0)
    CloudSyncStatusCard(
        status = presentation.status,
        detail = when {
            actionFailed -> "设置未保存，请稍后重试"
            paused && state.phase in cloudActivePhases -> "当前传输完成后暂停，资料仍保留在本机与云端"
            paused -> "已停止后续自动同步，继续后立即检查更新"
            quotaFailed -> "云空间状态暂未更新，请稍后重试"
            presentation.detail == null && state.pendingSnapshotUntil > System.currentTimeMillis() -> "资料变化已记下，稍后合并成一次备份"
            presentation.detail == null && recordError != null -> recordError
            else -> presentation.detail
        },
        quotaLabel = member?.let {
            "已用 ${cloudQuotaBytes(used ?: 0)} / ${cloudQuotaBytes(it.quotaBytes)}" +
                if (it.reservedBytes > 0) " · 上传预留 ${cloudQuotaBytes(it.reservedBytes)}" else ""
        },
        paused = paused,
        canPause = session != null && hasKey && member?.readable == true,
        actionLabel = presentation.actionLabel ?: if (quotaFailed) "重新获取" else null,
        onAction = {
            if (ownerConflict) showOwnerChoice = true
            else if (presentation.actionLabel == null && quotaFailed) refreshKey++
            else if (presentation.actionLabel == "重试同步") container.setCloudSyncPaused(false)
            else context.startActivity(AccountActivity.createIntent(context,
                section = if (presentation.actionLabel in setOf("验证设备身份", "登录账号")) AccountSection.ROOT else AccountSection.CLOUD_SYNC))
        },
        onTogglePause = {
            actionFailed = runCatching { container.setCloudSyncPaused(!paused) }.isFailure
        }
    )
    if (showOwnerChoice) LibraryOwnerDialog(
        onChoose = { merge ->
            showOwnerChoice = false
            scope.launch {
                actionFailed = runCatching { service.resolveLibraryOwner(merge) }.isFailure
                ownerConflict = service.libraryOwnerConflict()
                if (!ownerConflict) container.setCloudSyncPaused(false)
            }
        },
        onDismiss = { showOwnerChoice = false }
    )
}

internal val cloudActivePhases = setOf(CloudSyncPhase.CHECKING, CloudSyncPhase.DOWNLOADING,
    CloudSyncPhase.MERGING, CloudSyncPhase.UPLOADING)

internal data class CloudDashboardPresentation(val status: String, val detail: String?, val actionLabel: String? = null)

internal fun cloudDashboardPresentation(loggedIn: Boolean, hasKey: Boolean, paused: Boolean,
    phase: CloudSyncPhase, member: CloudMemberState?): CloudDashboardPresentation = when {
    !loggedIn -> CloudDashboardPresentation("未登录", "登录后查看云同步与云空间", "登录账号")
    member == null -> CloudDashboardPresentation("正在获取状态", "正在读取云空间额度")
    !member.readable -> CloudDashboardPresentation("云空间暂不可用", "请刷新状态或重新登录", "登录账号")
    !hasKey -> CloudDashboardPresentation("此手机尚未授权", "验证身份，安全恢复云端资料", "授权此手机")
    phase == CloudSyncPhase.NEEDS_RECOVERY -> CloudDashboardPresentation("设备身份需要重新验证", "前往账号页面完成验证，资料仍保留在本机", "验证设备身份")
    paused -> CloudDashboardPresentation(if (phase in cloudActivePhases) "正在暂停" else "同步暂停", null)
    phase == CloudSyncPhase.ERROR -> CloudDashboardPresentation("同步未完成", "请检查网络后重试", "重试同步")
    phase in cloudActivePhases -> CloudDashboardPresentation("正在同步", null)
    phase == CloudSyncPhase.WAITING_FOR_WIFI -> CloudDashboardPresentation("等待同步", "连接 Wi-Fi 后继续，正文遵循同步网络设置")
    phase == CloudSyncPhase.COMPLETE -> CloudDashboardPresentation("同步完成", null)
    else -> CloudDashboardPresentation("等待同步", "即将检查本机与云端更新")
}

internal fun cloudQuotaBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GiB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MiB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "${bytes.coerceAtLeast(0)} B"
}

/** Both choices are irreversible, so each needs a second, explicit confirmation. */
@Composable
private fun LibraryOwnerDialog(onChoose: (merge: Boolean) -> Unit, onDismiss: () -> Unit) {
    var pending by remember { mutableStateOf<Boolean?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when (pending) { true -> "合并到当前账号？"; false -> "清空本机资料？"; null -> "本机资料属于另一个账号" }) },
        text = {
            Text(when (pending) {
                true -> "本机资料会上传到当前账号，并同步到这个账号的所有设备，无法撤回。"
                false -> "本机的收藏、导入文章、订阅和阅读记录会被删除，之后从当前账号的云端重新同步。此操作不可撤销。"
                null -> "这台手机上的资料是用另一个账号产生的。在你选择之前，不会上传到当前账号。\n\n合并：本机资料会上传并同步到当前账号的所有设备。\n清空：删除本机资料，改用当前账号的云端资料。"
            })
        },
        confirmButton = {
            when (val choice = pending) {
                null -> TextButton(onClick = { pending = false }) { Text("清空本机资料") }
                else -> TextButton(onClick = { onChoose(choice) }) { Text(if (choice) "合并" else "清空") }
            }
        },
        dismissButton = {
            if (pending == null) TextButton(onClick = { pending = true }) { Text("合并到当前账号") }
            else TextButton(onClick = { pending = null }) { Text("返回") }
        }
    )
}
