package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.syncv2.SyncV2CloudError
import com.lightningstudio.watchrss.phone.syncv2.SyncV2PhoneUiPort
import com.lightningstudio.watchrss.phone.syncv2.SyncV2UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun codeOf(error: Throwable): String =
    (error as? SyncV2CloudError)?.code ?: error.message?.takeIf { Regex("[a-z][a-z0-9_]{0,63}").matches(it) } ?: "temporarily_unavailable"

/**
 * T-SYNCV2-UI §2: Sync v2 notices on 云同步 and their four sub-views. With the
 * switch off, no announcement, or every gate closed, the port is absent or
 * disabled and this renders nothing, so the page is unchanged.
 */
@Composable
internal fun SyncV2Section(service: PhoneCloudSyncService) {
    val port by service.syncV2UiPort.collectAsState()
    val live = port ?: return
    val state by live.state.collectAsState()
    val notices = SyncV2View.notices(state)
    if (notices.isEmpty()) return
    val scope = rememberCoroutineScope()
    var target by remember(live) { mutableStateOf<SyncV2View.Target?>(null) }
    var retrying by remember(live) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notices.forEach { notice ->
            val accent = when (notice.tone) {
                SyncV2View.Tone.ATTENTION -> MaterialTheme.colorScheme.tertiary
                SyncV2View.Tone.ERROR -> MaterialTheme.colorScheme.error
                SyncV2View.Tone.MUTED -> MaterialTheme.colorScheme.onSurface
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(notice.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = accent)
                        Text(notice.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    when {
                        notice.target != null -> FilledTonalButton(onClick = { target = notice.target }) { Text(notice.openLabel ?: "查看") }
                        notice.retry != null -> OutlinedButton(enabled = !retrying, onClick = {
                            retrying = true
                            scope.launch {
                                try { live.syncNow() } catch (e: CancellationException) { throw e } catch (_: Throwable) { /* state carries the code */ }
                                finally { retrying = false }
                            }
                        }) { Text(if (retrying) "正在重试…" else notice.retry) }
                    }
                }
            }
        }
    }

    when (target) {
        SyncV2View.Target.CHOICE -> ChoiceDialog(live, state) { target = null }
        SyncV2View.Target.UNOPENABLE -> UnopenableDialog(state) { target = null }
        SyncV2View.Target.RECOVERY -> RecoveryDialog(live, state) { target = null }
        SyncV2View.Target.HISTORY -> HistoryDialog(live, state) { target = null }
        null -> Unit
    }
}

@Composable
private fun SubViewDialog(title: String, onDismiss: () -> Unit, error: String?, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                content()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ChoiceDialog(port: SyncV2PhoneUiPort, state: SyncV2UiState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<SyncV2PhoneUiPort.ChoicePreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SyncV2View.Option?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        error = null
        try { preview = port.openChoice() } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = SyncV2View.errorText(codeOf(e)) }
    }
    fun leave() { preview?.let { p -> preview = null; scope.launch { runCatching { port.closeChoice(p) } } }; onClose() }
    fun choose(option: SyncV2View.Option) {
        val p = preview ?: return
        preview = null // A choice consumes the preview, success or not.
        busy = true
        scope.launch {
            try {
                when (option.key) { "cloud" -> port.chooseCloud(p); "local" -> port.chooseLocal(p); else -> port.chooseMerge(p) }
                onClose()
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                val code = codeOf(e)
                error = SyncV2View.errorText(code)
                if (code == "choice_stale") reload++
            } finally { busy = false }
        }
    }

    val title = if (state.choice?.kind == "rollback") "云端已回到较早版本" else "需要选择保留哪一份"
    SubViewDialog(title, ::leave, error) {
        Text("选择前不会上传。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val p = preview
        if (p == null) { if (error == null) Text("正在比较两边的修改…", style = MaterialTheme.typography.bodySmall); return@SubViewDialog }
        SyncV2View.options(p, state.keepDiscardedLocalChanges7Days).forEach { option ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().clickable(enabled = option.enabled && !busy) {
                    if (option.confirmTitle != null) pending = option else choose(option)
                },
                colors = CardDefaults.outlinedCardColors()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(option.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = if (option.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                    Text(option.consequence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        PreviewList("本机的修改", p.local, p.localMore)
        PreviewList("云端的修改", p.cloud, p.cloudMore)
    }
    pending?.let { option ->
        ConfirmDialog(option.confirmTitle!!, option.confirmText!!, option.label,
            onConfirm = { pending = null; choose(option) }, onDismiss = { pending = null })
    }
}

@Composable
private fun PreviewList(title: String, items: List<SyncV2PhoneUiPort.ChoiceItem>, more: Int) {
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    if (items.isEmpty() && more == 0) { Text("没有", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    items.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SyncV2View.kindLabel(item.kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(item.title.trim().ifEmpty { "无标题" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (more > 0) Text("还有 $more 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun UnopenableDialog(state: SyncV2UiState, onClose: () -> Unit) {
    SubViewDialog("本机无法打开的笔记", onClose, null) {
        Text("这些笔记太大，请在小程序查看。内容已原样保留在云端。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.unopenableNotes.forEach { Text(it.title ?: "无标题", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun RecoveryDialog(port: SyncV2PhoneUiPort, state: SyncV2UiState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var restoreId by remember { mutableStateOf<String?>(null) }
    fun run(work: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = SyncV2View.errorText(codeOf(e)) }
            finally { busy = false }
        }
    }
    SubViewDialog("可找回的本机修改", onClose, error) {
        Text("选择保留云端或回到旧版本时，被替换的本机修改保留 7 天。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (state.recoveryCopies.isEmpty()) Text("没有可找回的修改。", style = MaterialTheme.typography.bodySmall)
        state.recoveryCopies.forEach { copy ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${SyncV2View.formatTime(copy.createdAtMillis)} 保存", style = MaterialTheme.typography.bodyMedium)
                    Text("${SyncV2View.formatTime(copy.expiresAtMillis)} 后删除", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(enabled = !busy, onClick = { restoreId = copy.copyId }) { Text("找回") }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("保留被替换的修改 7 天", style = MaterialTheme.typography.bodyMedium)
                Text("关闭后，被替换的本机修改不可找回", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.keepDiscardedLocalChanges7Days, enabled = !busy, onCheckedChange = { value -> run { port.setKeepDiscarded(value) } })
        }
    }
    restoreId?.let { id ->
        ConfirmDialog("找回这份修改？", "这份被替换的修改会恢复到本机。", "找回",
            onConfirm = { restoreId = null; run { port.restoreRecovery(id); notice = "已找回这份修改。" } },
            onDismiss = { restoreId = null })
    }
}

private data class HistoryRow(val commitOid: String, val time: String, val source: String)

@Composable
private fun HistoryDialog(port: SyncV2PhoneUiPort, state: SyncV2UiState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var token by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<HistoryRow?>(null) }

    fun run(work: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { work() } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = SyncV2View.errorText(codeOf(e)) }
            finally { busy = false }
        }
    }
    fun load(next: String?) = run {
        val page = port.history(next)
        val items = page.getJSONArray("items")
        val now = System.currentTimeMillis()
        val add = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            HistoryRow(item.getString("commitOid"), SyncV2View.formatTime(item.getLong("retiredAtMillis"), now),
                SyncV2View.historySource(item.optString("authorDeviceId").takeIf { it.isNotEmpty() }, state.ownDeviceId))
        }
        rows = if (next == null) add else rows + add
        token = page.optString("pageToken").takeIf { !page.isNull("pageToken") && it.isNotEmpty() }
        loaded = true
    }
    LaunchedEffect(Unit) { load(null) }

    SubViewDialog("历史版本", onClose, error) {
        Text("每个版本保留 7 天。回到某个版本后，各设备都会同步到该版本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (loaded && rows.isEmpty()) Text("暂无历史版本。", style = MaterialTheme.typography.bodySmall)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { target = row }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("截至 ${row.time}", style = MaterialTheme.typography.bodyMedium)
                    Text(row.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("回到这个版本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        token?.let { next -> TextButton(enabled = !busy, onClick = { load(next) }) { Text("加载更早的版本") } }
    }
    target?.let { row ->
        ConfirmDialog("回到这个版本？", "截至 ${row.time} 的版本。本机未上传的修改会先保存为可找回副本。", "回到此版本",
            onConfirm = {
                target = null
                run { val capture = port.captureRollback(); port.rollback(row.commitOid, capture); notice = "已回到这个版本，其他设备会随后同步。" }
            },
            onDismiss = { target = null })
    }
}
