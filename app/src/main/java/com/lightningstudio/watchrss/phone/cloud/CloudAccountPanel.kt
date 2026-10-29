package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.data.backup.BackupImportMode
import kotlinx.coroutines.CancellationException
import com.lightningstudio.watchrss.phone.tips.TipIds
import com.lightningstudio.watchrss.phone.tips.ui.tipAnchor

@Composable
fun CloudAccountPanel(
    service: PhoneCloudSyncService,
    userId: String,
    busy: Boolean,
    runAction: (suspend () -> Unit) -> Unit,
    onBusyChange: (Boolean) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    onOpenInventory: () -> Unit
) {
    val syncState by service.state.collectAsState()
    var retentionDays by remember(userId) { mutableStateOf(service.settings.retentionDays) }
    var networkPolicy by remember(userId) { mutableStateOf(service.settings.networkPolicy) }
    var bootstrap by remember(userId) { mutableStateOf<CloudBootstrap?>(null) }
    var setup by remember(userId) { mutableStateOf<RecoveryKeySetup?>(null) }
    var showApprovalDialog by rememberSaveable(userId) { mutableStateOf(false) }
    var recoveryInput by remember(userId) { mutableStateOf("") }
    var estimate by remember { mutableStateOf<CloudTransferEstimate?>(null) }
    var showHistory by remember(userId) { mutableStateOf(false) }
    var snapshotHeads by remember(userId) { mutableStateOf<List<CloudSnapshotHead>>(emptyList()) }
    var historyLoading by remember(userId) { mutableStateOf(false) }
    var restorePreview by remember { mutableStateOf<CloudSnapshotRestorePreview?>(null) }
    var overwriteConfirm by remember { mutableStateOf<CloudSnapshotRestorePreview?>(null) }
    var deleteCandidate by remember { mutableStateOf<CloudSnapshotHead?>(null) }
    var showResetWarning by remember(userId) { mutableStateOf(false) }
    var showResetConfirmation by remember(userId) { mutableStateOf(false) }
    var resetConfirmationInput by remember(userId) { mutableStateOf("") }
    var resetError by remember(userId) { mutableStateOf<String?>(null) }
    val registrationConflict by service.deviceRegistrationConflict.collectAsState()
    var showReregisterConfirmation by remember(userId) { mutableStateOf(false) }
    var activationInFlight by remember(userId) { mutableStateOf(false) }

    fun refresh() {
        runAction {
            historyLoading = showHistory
            try {
                runCatching {
                    val member = service.loadMembership()
                    bootstrap = if (member.readable) service.loadBootstrap() else null
                    snapshotHeads = if (showHistory && member.readable) {
                        service.listSnapshotHistory()
                    } else {
                        emptyList()
                    }
                }.onFailure { onError(it.message ?: "会员状态读取失败") }
            } finally {
                historyLoading = false
            }
        }
    }

    LaunchedEffect(userId) { refresh() }

    if (showReregisterConfirmation && registrationConflict) {
        AlertDialog(
            onDismissRequest = { showReregisterConfirmation = false },
            title = { Text("重新登记本机") },
            text = { Text("将撤销服务器上本机的旧身份并重新登记，之后需使用恢复词或其他设备授权。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                showReregisterConfirmation = false
                runAction {
                    onBusyChange(true)
                    try { service.reregisterCurrentDevice(); refresh(); onMessage("本机已重新登记，请恢复或授权") }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { onError(error.message ?: "重新登记失败") }
                    finally { onBusyChange(false) }
                }
            }) { Text("确认重新登记") } },
            dismissButton = { TextButton(onClick = { showReregisterConfirmation = false }) { Text("取消") } }
        )
    }
    if (showApprovalDialog) {
        key(userId) {
            CloudApprovalDialog(
                service = service,
                onAuthorized = { bootstrap = it; onMessage("云同步已授权") },
                onDismiss = { showApprovalDialog = false }
            )
        }
    }

    val member = syncState.member
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("云空间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val keyWarning by service.keyWarning.collectAsState()
        val needsReapproval by service.needsReapproval.collectAsState()
        if (registrationConflict) {
            OutlinedButton(enabled = !busy, onClick = { showReregisterConfirmation = true }) { Text("重新登记本机") }
        }
        keyWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (member == null) {
            Text("正在读取会员状态…")
        } else if (!member.readable) {
            Text("暂时无法读取云空间，请刷新状态或重新登录。")
            Text(
                "免费账号提供15 MB基础云空间；已有云服务额度按账号实际权益显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val used = member.usedBytes
            val fraction = if (member.quotaBytes <= 0) 0f
            else (used.toDouble() / member.quotaBytes).toFloat().coerceIn(0f, 1f)
            Text("云备份可用 · 按账号云空间额度同步")
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                "${formatBytes(used)} / ${formatBytes(member.quotaBytes)}",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(
                onClick = { showHistory = !showHistory; if (showHistory) refresh() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (showHistory) "收起历史快照" else "历史快照 · 恢复与删除") }
            if (showHistory) {
                Text("历史快照", fontWeight = FontWeight.Medium)
                Text(
                    "覆盖恢复会先创建本地安全备份。大小包含共享数据，删除后实际释放空间可能更少。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (historyLoading && snapshotHeads.isEmpty()) {
                    Text("正在读取历史快照…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (snapshotHeads.isEmpty()) {
                    Text("暂无可恢复的历史快照")
                }
                snapshotHeads.forEach { head ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "${bootstrap?.devices?.find { it.deviceId == head.sourceDeviceId }?.displayName?.takeIf { it.isNotBlank() } ?: "设备 ${head.sourceDeviceId.take(8)}"} · 快照 #${head.deviceSequence}",
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${head.completedAt?.let { java.time.Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.orEmpty()} · ${formatBytes(head.totalSizeBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        runAction {
                                            onBusyChange(true)
                                            runCatching { service.inspectSnapshot(head.id) }
                                                .onSuccess { restorePreview = it }
                                                .onFailure {
                                                    onError(it.message ?: "无法读取快照预览")
                                                }
                                            onBusyChange(false)
                                        }
                                    },
                                    enabled = !busy && service.hasLocalAccountKey()
                                ) { Text("切换 / 恢复") }
                                TextButton(
                                    onClick = { deleteCandidate = head },
                                    enabled = !busy
                                ) { Text("删除") }
                            }
                        }
                    }
                }
            }


            val recoveryEnvelopeExists =
                bootstrap?.keyEnvelopes?.any { it.recipientType == "recovery" } == true
            val hasLocalKey = service.hasLocalAccountKey()
            if (!recoveryEnvelopeExists && !hasLocalKey && bootstrap != null) {
                // Leaving before activation must not keep an unbacked key around.
                DisposableEffect(Unit) { onDispose { if (setup != null && !activationInFlight) service.discardRecoverySetup() } }
                if (setup == null) {
                    Button(
                        onClick = {
                            runAction {
                                onBusyChange(true)
                                runCatching { service.prepareFirstDevice() }
                                    .onSuccess { setup = it }
                                    .onFailure { onError(it.message ?: "云备份启用失败") }
                                onBusyChange(false)
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tipAnchor(TipIds.CLOUD_E2EE)
                    ) { Text("启用端到端加密云备份") }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("请离线抄写这 24 个恢复词", fontWeight = FontWeight.SemiBold)
                            Text("服务器无法替你找回。所有设备丢失后，只能依靠它恢复。")
                            SelectionContainer {
                                Text(setup!!.words.joinToString(" "))
                            }
                            RecoveryInputPrivacy {
                                OutlinedTextField(
                                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                                    value = recoveryInput,
                                    onValueChange = { recoveryInput = it },
                                    label = { Text("重新输入全部 24 词进行确认") },
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Button(
                                onClick = activate@{
                                    if (activationInFlight) return@activate
                                    val pendingSetup = setup ?: return@activate
                                    val confirmedWords = RecoveryWords.parse(recoveryInput)
                                    if (confirmedWords.size != 24) return@activate
                                    activationInFlight = true
                                    onBusyChange(true)
                                    runAction {
                                        try {
                                            runCatching {
                                                service.activateFirstDevice(
                                                    pendingSetup,
                                                    confirmedWords
                                                )
                                                bootstrap = service.loadBootstrap()
                                            }.onSuccess {
                                                setup = null
                                                recoveryInput = ""
                                                onMessage("加密云备份已启用")
                                            }.onFailure {
                                                onError(it.message ?: "恢复词确认失败")
                                            }
                                        } finally {
                                            activationInFlight = false
                                            onBusyChange(false)
                                        }
                                    }
                                },
                                enabled = !busy && !activationInFlight &&
                                    RecoveryWords.parse(recoveryInput).size == 24,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("我已安全保存，正式启用") }
                            TextButton(
                                onClick = { service.discardRecoverySetup(); setup = null; recoveryInput = "" },
                                enabled = !activationInFlight,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("取消") }
                        }
                    }
                }
            } else if (recoveryEnvelopeExists && !hasLocalKey) {
                Text("可使用恢复词，或请求另一台已授权设备批准此手机。")
                Button(
                    onClick = { showApprovalDialog = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("请已登录的设备批准") }
                RecoveryInputPrivacy {
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        value = recoveryInput,
                        onValueChange = { recoveryInput = it },
                        label = { Text("24词恢复密钥") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = {
                        runAction {
                            onBusyChange(true)
                            runCatching {
                                service.recoverWithWords(RecoveryWords.parse(recoveryInput))
                                bootstrap = service.loadBootstrap()
                            }.onSuccess {
                                recoveryInput = ""
                                onMessage("当前手机已获得解密能力")
                            }.onFailure { onError(it.message ?: "恢复失败") }
                            onBusyChange(false)
                        }
                    },
                    enabled = !busy && RecoveryWords.parse(recoveryInput).size == 24,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("使用恢复词授权此手机") }
                Text("仅短信登录不能解密。设备审批使用本机硬件保护密钥。")
            } else if (hasLocalKey) {
                Text(
                    "✓ 端到端加密云备份已启用",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text("快照保留", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7 to "7天", 30 to "30天", 90 to "90天", 0 to "永久").forEach { (days, label) ->
                        val selected = retentionDays == days.takeIf { it > 0 }
                        if (selected) {
                            Button(onClick = {}, enabled = !busy) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    service.settings.retentionDays = days.takeIf { it > 0 }
                                    retentionDays = service.settings.retentionDays
                                },
                                enabled = !busy
                            ) { Text(label) }
                        }
                    }
                }
                Text("网络策略", fontWeight = FontWeight.Medium)
                CloudNetworkPolicy.entries.forEach { policy ->
                    val label = when (policy) {
                        CloudNetworkPolicy.WIFI_BODIES_ANY_STATE -> "Wi‑Fi传正文，任意网络同步小状态"
                        CloudNetworkPolicy.WIFI_AND_CHARGING -> "仅Wi‑Fi且充电"
                        CloudNetworkPolicy.ANY_NETWORK -> "任何网络"
                    }
                    OutlinedButton(
                        onClick = { service.settings.networkPolicy = policy; networkPolicy = policy },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (networkPolicy == policy) "✓ $label" else label)
                    }
                }
                OutlinedButton(
                    onClick = onOpenInventory,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("频道云库存 ›") }
                RecoveryInputPrivacy {
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                        value = recoveryInput,
                        onValueChange = { recoveryInput = it },
                        label = { Text("轮换密钥前输入原24词恢复密钥") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedButton(
                    onClick = {
                        runAction {
                            onBusyChange(true)
                            runCatching {
                                service.rotateAccountKey(RecoveryWords.parse(recoveryInput))
                            }.onSuccess { version ->
                                recoveryInput = ""
                                onMessage("账号密钥已轮换到第${version}版，原24词恢复密钥继续有效")
                                try { service.syncNow(manual = true) }
                                catch (_: Exception) { onMessage("密钥已轮换，后续同步暂未完成，请稍后重试同步") }
                            }.onFailure { onError(it.message ?: "密钥轮换失败") }
                            onBusyChange(false)
                        }
                    },
                    enabled = !busy && RecoveryWords.parse(recoveryInput).size == 24,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("轮换账号加密密钥") }
                Button(
                    onClick = {
                        runAction {
                            onBusyChange(true)
                            runCatching { service.estimateManualSync() }
                                .onSuccess { estimate = it }
                                .onFailure { onError(it.message ?: "无法估算同步流量") }
                            onBusyChange(false)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("立即同步") }
                Text(
                    syncState.message.ifBlank { "等待同步" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncState.phase == CloudSyncPhase.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )


                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("设备授权", fontWeight = FontWeight.Medium)
                    TextButton(onClick = { refresh() }, enabled = !busy) { Text("刷新") }
                }
                bootstrap?.devices?.filter { it.revokedAt == null && !it.deviceId.startsWith("reader-migration-") }?.forEach { device ->
                    val hasEnvelope = bootstrap!!.keyEnvelopes.any {
                        it.recipientType == "device" && it.recipientDeviceId == device.deviceId
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(device.displayName.ifBlank { device.deviceId }, fontWeight = FontWeight.Medium)
                            val unattested = needsReapproval.any { it.deviceId == device.deviceId }
                            Text("${device.platform} · ${when { unattested -> "需要重新授权"; hasEnvelope -> "已授权"; else -> "等待补发密钥" }}")
                            if (unattested) Text("请在该设备上重新发起授权，并核对两台设备显示的 8 位码。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else if (!hasEnvelope && device.publicKeySpki.isNotBlank()) {
                                Button(
                                    onClick = {
                                        runAction {
                                            onBusyChange(true)
                                            runCatching {
                                                service.approveDevice(device.deviceId)
                                                bootstrap = service.loadBootstrap()
                                            }.onSuccess { onMessage("已补发密钥") }
                                                .onFailure { onError(it.message ?: "设备授权失败") }
                                            onBusyChange(false)
                                        }
                                    },
                                    enabled = !busy
                                ) { Text("补发密钥") }
                            }
                            TextButton(
                                onClick = {
                                    runAction {
                                        onBusyChange(true)
                                        runCatching {
                                            service.revokeDevice(device.deviceId)
                                            bootstrap = service.loadBootstrap()
                                        }.onSuccess { onMessage("设备已撤销") }
                                            .onFailure { onError(it.message ?: "设备撤销失败") }
                                        onBusyChange(false)
                                    }
                                },
                                enabled = !busy
                            ) { Text("撤销设备") }
                        }
                    }
                }
            }
            if (recoveryEnvelopeExists) {
                OutlinedButton(
                    onClick = { showResetWarning = true },
                    enabled = !busy,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "忘记恢复密钥？删除旧云端库并重新开始",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (showResetWarning) {
        AlertDialog(
            onDismissRequest = { if (!busy) showResetWarning = false },
            title = { Text("无法恢复旧云端库？") },
            text = {
                Text(
                    "仅当已经无法找回 24 词恢复密钥，并接受放弃所有旧云端快照时继续。" +
                        "若另一台设备仍有需要保留的资料，请先取消并从该设备恢复或导出。" +
                        "旧云端快照、加密密钥和设备授权会永久删除，服务器无法找回；" +
                        "这台手机上的本地文章、订阅和阅读状态不会删除。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetWarning = false
                        resetConfirmationInput = ""
                        resetError = null
                        showResetConfirmation = true
                    },
                    enabled = !busy
                ) { Text("我理解，继续") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetWarning = false },
                    enabled = !busy
                ) { Text("取消") }
            }
        )
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    showResetConfirmation = false
                    resetConfirmationInput = ""
                    resetError = null
                }
            },
            title = { Text("永久删除云端库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("此操作不可撤销。请输入“$CLOUD_LIBRARY_DELETE_PHRASE”完成最终确认。")
                    OutlinedTextField(
                        value = resetConfirmationInput,
                        onValueChange = {
                            resetConfirmationInput = it
                            resetError = null
                        },
                        label = { Text(CLOUD_LIBRARY_DELETE_PHRASE) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (busy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在永久删除云端库，请勿关闭页面")
                    }
                    resetError?.let { visibleError ->
                        Text(
                            visibleError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val confirmation = resetConfirmationInput
                        resetError = null
                        runAction {
                            onBusyChange(true)
                            runCatching { service.resetCloudLibrary(confirmation) }
                                .onSuccess { result ->
                                    showResetConfirmation = false
                                    resetConfirmationInput = ""
                                    resetError = null
                                    recoveryInput = ""
                                    setup = null
                                    snapshotHeads = emptyList()
                                    bootstrap = runCatching { service.loadBootstrap() }
                                        .getOrElse {
                                            bootstrap?.copy(
                                                devices = emptyList(),
                                                keyEnvelopes = emptyList()
                                            )
                                        }
                                    onMessage(
                                        if (result.storageObjectsQueued > 0) {
                                            "旧云端库已删除，本机资料仍保留；密文对象正在后台清理"
                                        } else {
                                            "旧云端库已删除，本机资料仍保留，现在可重新启用云备份"
                                        }
                                    )
                                }
                                .onFailure {
                                    val visibleError = cloudLibraryResetErrorMessage(it)
                                    resetError = visibleError
                                    onError(visibleError)
                                }
                            onBusyChange(false)
                        }
                    },
                    enabled = !busy && isCloudLibraryDeleteConfirmed(resetConfirmationInput),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("永久删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        resetConfirmationInput = ""
                        resetError = null
                    },
                    enabled = !busy
                ) { Text("取消") }
            }
        )
    }

    estimate?.let { pending ->
        AlertDialog(
            onDismissRequest = { estimate = null },
            title = { Text("确认立即同步") },
            text = {
                Text(
                    "预计最多上传 ${formatBytes(pending.uploadBytes)}，" +
                        "下载 ${formatBytes(pending.downloadBytes)}。本次将临时覆盖网络限制。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    estimate = null
                    runAction {
                        onBusyChange(true)
                        runCatching { service.syncNow(manual = true) }
                            .onSuccess {
                                bootstrap = service.loadBootstrap()
                                onMessage("云同步完成")
                            }
                            .onFailure { onError(it.message ?: "云同步失败") }
                        onBusyChange(false)
                    }
                }) { Text("继续同步") }
            },
            dismissButton = {
                TextButton(onClick = { estimate = null }) { Text("取消") }
            }
        )
    }

    restorePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { restorePreview = null },
            title = { Text("恢复预览") },
            text = {
                Text(
                    "来源 ${preview.sourceDeviceId} · 快照 #${preview.deviceSequence}\n" +
                        "订阅 ${preview.sourceCount}，私有内容 ${preview.privateArticleCount}，" +
                        "中继文章 ${preview.relayArticleCount}，阅读状态 ${preview.rssStateCount}\n" +
                        "加密下载量约 ${formatBytes(preview.encryptedDownloadBytes)}\n" +
                        "合并恢复会恢复快照中的收藏和阅读状态，保留其他资料；执行前先创建本地安全备份。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restorePreview = null
                    runAction {
                        onBusyChange(true)
                        runCatching {
                            service.restoreSnapshot(preview.snapshotId, BackupImportMode.MERGE)
                        }.onSuccess { result ->
                            refresh()
                            onMessage(
                                "合并恢复完成：文章 ${result.privateArticlesChanged + result.relayItemsChanged}，" +
                                    "状态 ${result.rssStatesApplied}"
                            )
                        }.onFailure { onError(it.message ?: "快照恢复失败") }
                        onBusyChange(false)
                    }
                }) { Text("合并恢复") }
            },
            dismissButton = {
                Row {
                    if (preview.hasPrivateArchive) {
                        TextButton(onClick = {
                            restorePreview = null
                            overwriteConfirm = preview
                        }) { Text("覆盖恢复") }
                    }
                    TextButton(onClick = { restorePreview = null }) { Text("取消") }
                }
            }
        )
    }

    overwriteConfirm?.let { preview ->
        AlertDialog(
            onDismissRequest = { overwriteConfirm = null },
            title = { Text("再次确认覆盖恢复") },
            text = {
                Text("当前私有资料将以该快照为准。执行前会自动创建本地安全 .wrss 快照。")
            },
            confirmButton = {
                TextButton(onClick = {
                    overwriteConfirm = null
                    runAction {
                        onBusyChange(true)
                        runCatching {
                            service.restoreSnapshot(preview.snapshotId, BackupImportMode.REPLACE)
                        }.onSuccess {
                            refresh()
                            onMessage("覆盖恢复完成，本地安全快照已保留")
                        }.onFailure { onError(it.message ?: "覆盖恢复失败") }
                        onBusyChange(false)
                    }
                }) { Text("确认覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { overwriteConfirm = null }) { Text("取消") }
            }
        )
    }

    deleteCandidate?.let { head ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除云快照？") },
            text = { Text("删除快照 #${head.deviceSequence} 后无法恢复此版本，不会删除本机资料。共享数据块会保留，独占数据块由后台回收后释放空间。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    runAction {
                        onBusyChange(true)
                        runCatching { service.deleteSnapshot(head.id) }
                            .onSuccess {
                                refresh()
                                onMessage("云快照已删除")
                            }
                            .onFailure { onError(it.message ?: "删除快照失败") }
                        onBusyChange(false)
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("取消") }
            }
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / 1073741824.0)
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / 1048576.0)
    else -> "${bytes / 1024L} KiB"
}
