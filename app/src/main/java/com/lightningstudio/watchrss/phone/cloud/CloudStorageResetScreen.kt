package com.lightningstudio.watchrss.phone.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CloudResetStep { WARNING, WORDS, RUNNING, SUCCESS, RESULT }

@Composable
internal fun CloudStorageResetScreen(
    service: PhoneCloudSyncService,
    userId: String,
    resume: Boolean,
    onClose: (String?) -> Unit
) {
    val application = LocalContext.current.applicationContext as PhoneCompanionApplication
    val scope = rememberCoroutineScope()
    val errorColor = MaterialTheme.colorScheme.error
    var step by remember(userId) { mutableStateOf(if (resume) CloudResetStep.RUNNING else CloudResetStep.WARNING) }
    var setup by remember(userId) { mutableStateOf<RecoveryKeySetup?>(null) }
    var saved by remember(userId) { mutableStateOf(false) }
    var remaining by remember(userId) { mutableIntStateOf(5) }
    var result by remember(userId) { mutableStateOf("") }
    var preparing by remember(userId) { mutableStateOf(false) }
    var reconcile by remember(userId) { mutableStateOf(resume) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(setup) {
        val secret = setup?.pendingKey
        onDispose { secret?.fill(0) }
    }

    fun showFailure(error: Exception) {
        if (com.lightningstudio.watchrss.phone.BuildConfig.DEBUG) {
            val code = error.message?.takeIf {
                it in setOf("authentication_required", "choice_stale", "closure_incomplete", "storage_pressure",
                    "budget_paused", "reset_migration_required", "trusted_time_unavailable", "scope_mismatch")
            } ?: "unclassified"
            android.util.Log.w("WatchRSSCloudReset", "${error.javaClass.simpleName}: $code")
        }
        result = cloudStorageResetError(error)
        step = CloudResetStep.RESULT
    }

    LaunchedEffect(userId, reconcile, lifecycle) {
        if (reconcile) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                if (service.confirmCloudStorageReset(userId)) step = CloudResetStep.SUCCESS
                else {
                    result = CLOUD_RESET_UNCONFIRMED
                    step = CloudResetStep.RESULT
                }
                reconcile = false
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                showFailure(error)
                reconcile = false
            }
        }
    }
    LaunchedEffect(saved, step) {
        if (saved && step == CloudResetStep.WORDS) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (remaining > 0) {
                val timeLeft = deadline - android.os.SystemClock.elapsedRealtime()
                remaining = ((timeLeft.coerceAtLeast(0) + 999) / 1_000).toInt()
                if (timeLeft > 0) delay((timeLeft % 1_000).takeIf { it > 0 } ?: 1_000)
            }
        }
    }
    fun close() {
        if (step == CloudResetStep.SUCCESS) service.syncAfterCloudStorageReset()
        setup?.pendingKey?.fill(0)
        setup = null
        onClose(result.takeIf { step == CloudResetStep.RESULT })
    }
    BackHandler { if (step != CloudResetStep.RUNNING) close() }

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val title = when (step) {
                        CloudResetStep.WARNING -> "重置云存储？"
                        CloudResetStep.WORDS -> "保存新的恢复词"
                        CloudResetStep.RUNNING -> "正在重置云存储…"
                        CloudResetStep.SUCCESS -> "云存储已重置"
                        CloudResetStep.RESULT -> "云存储重置"
                    }
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    when (step) {
                        CloudResetStep.WARNING -> Text(
                            "这会永久抹除你在云端的所有内容，包括备忘录、书籍、文章、附件和历史快照，并为你生成新的恢复词。" +
                                "其他已登录的设备需要用新恢复词重新加入。这台手机上尚未同步的内容会保留，并在重置后重新上传。此操作无法撤销。"
                        )
                        CloudResetStep.WORDS -> setup?.let {
                            RecoveryContentPrivacy {
                                RecoveryWordsDisplay(it.words, application.recoveryWordClipboard, enabled = true)
                            }
                            Row(
                                Modifier.fillMaxWidth().toggleable(saved, role = Role.Checkbox) {
                                    remaining = 5
                                    saved = it
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(saved, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                                Text("我已保存新的恢复词")
                            }
                        }
                        CloudResetStep.RUNNING -> CloudLoadingRow(
                            if (reconcile) "正在向服务器确认重置结果…" else "正在抹除云端内容并启用新的恢复词，请保持此页面打开。"
                        )
                        CloudResetStep.SUCCESS -> Text("其他已登录的设备需要用新的恢复词重新加入。返回云同步页后，将开始上传本机待同步内容。")
                        CloudResetStep.RESULT -> Text(result)
                    }
                }
                when (step) {
                    CloudResetStep.WARNING -> {
                        Button(onClick = ::close, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = errorColor),
                            border = androidx.compose.foundation.BorderStroke(1.dp, errorColor),
                            enabled = !preparing,
                            onClick = {
                                preparing = true
                                scope.launch {
                                    try {
                                        setup = withContext(Dispatchers.IO) { service.prepareCloudStorageReset(userId) }
                                        step = CloudResetStep.WORDS
                                    } catch (error: CancellationException) { throw error }
                                    catch (error: Exception) {
                                        showFailure(error)
                                    } finally { preparing = false }
                                }
                            }
                        ) { CloudButtonLabel("继续重置", "正在生成恢复词…".takeIf { preparing }) }
                    }
                    CloudResetStep.WORDS -> {
                        Button(onClick = ::close, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = errorColor, contentColor = MaterialTheme.colorScheme.onError),
                            enabled = canSubmitCloudReset(saved, remaining),
                            onClick = {
                                val draft = setup ?: return@Button
                                if (step != CloudResetStep.WORDS || !canSubmitCloudReset(saved, remaining)) return@Button
                                step = CloudResetStep.RUNNING
                                val attempt = draft.copy(pendingKey = draft.pendingKey.copyOf())
                                draft.pendingKey.fill(0)
                                setup = null
                                scope.launch {
                                    try {
                                        service.executeCloudStorageReset(userId, attempt)
                                        reconcile = true
                                    } catch (error: CancellationException) { throw error }
                                    catch (error: Exception) { showFailure(error) }
                                    finally {
                                        attempt.pendingKey.fill(0)
                                    }
                                }
                            }
                        ) {
                            Text(if (saved && remaining > 0) "重置云存储（${remaining} 秒）" else "重置云存储")
                        }
                    }
                    CloudResetStep.SUCCESS, CloudResetStep.RESULT -> Button(
                        onClick = ::close, modifier = Modifier.fillMaxWidth()
                    ) { Text("返回云同步") }
                    CloudResetStep.RUNNING -> Unit
                }
            }
        }
    }
}
