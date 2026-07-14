package com.lightningstudio.watchrss.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.lightningstudio.watchrss.phone.data.backup.BackupImportMode
import com.lightningstudio.watchrss.phone.data.backup.BackupPreview
import com.lightningstudio.watchrss.phone.data.backup.BackupVersionTooHighException
import com.lightningstudio.watchrss.phone.data.backup.GITHUB_RELEASES_URL
import com.lightningstudio.watchrss.phone.data.backup.WATCHRSS_BACKUP_EXTENSION
import com.lightningstudio.watchrss.phone.data.backup.WATCHRSS_BACKUP_MIME_TYPE
import com.lightningstudio.watchrss.phone.data.backup.WATCHRSS_HUMAN_READABLE_EXTENSION
import com.lightningstudio.watchrss.phone.ui.AdaptiveContentFrame
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import com.lightningstudio.watchrss.phone.ui.AdaptiveWindowScope
import com.lightningstudio.watchrss.phone.ui.PredictiveBackSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataManagementActivity : ComponentActivity() {
    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, DataManagementActivity::class.java)
    }

    private val backupService
        get() = (application as PhoneCompanionApplication).container.backupService

    private var pendingImportUri: String? = null

    private val exportWrssLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(WATCHRSS_BACKUP_MIME_TYPE)) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                runCatching { backupService.exportTo(uri.toString()) }
                    .onSuccess { showToast("已导出 WRSS：${it.articleCount} 篇文章，${it.sourceCount} 个 RSS 源") }
                    .onFailure { showToast("导出失败：${it.message ?: "未知错误"}") }
            }
        }

    private val exportHumanReadableLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                runCatching { backupService.exportHumanReadable(uri.toString()) }
                    .onSuccess { showToast("已导出 JSON：${it.articleCount} 篇文章，${it.sourceCount} 个 RSS 源") }
                    .onFailure { showToast("导出失败：${it.message ?: "未知错误"}") }
            }
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val uriString = uri.toString()
            pendingImportUri = uriString
            lifecycleScope.launch {
                runCatching {
                    val preview = withContext(Dispatchers.IO) { backupService.inspect(uriString) }
                    preview
                }.onSuccess { preview ->
                    importPreviewState = preview
                }.onFailure { throwable ->
                    if (throwable is BackupVersionTooHighException) {
                        versionTooHighState = throwable
                    } else {
                        showToast("无法读取备份：${throwable.message ?: "未知错误"}")
                    }
                }
            }
        }

    private var importPreviewState by mutableStateOf<BackupPreview?>(null)
    private var versionTooHighState by mutableStateOf<BackupVersionTooHighException?>(null)
    private var isImporting by mutableStateOf(false)

    private var toastMessage by mutableStateOf<String?>(null)

    private fun showToast(message: String) {
        toastMessage = message
    }

    private fun exportWrssBackup() {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        exportWrssLauncher.launch("WatchRSS-backup-$timestamp$WATCHRSS_BACKUP_EXTENSION")
    }

    private fun exportHumanReadable() {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        exportHumanReadableLauncher.launch("WatchRSS-export-$timestamp$WATCHRSS_HUMAN_READABLE_EXTENSION")
    }

    private fun selectBackupForImport() {
        importBackupLauncher.launch(arrayOf(WATCHRSS_BACKUP_MIME_TYPE, "application/zip", "*/*"))
    }

    private fun confirmImport(mode: BackupImportMode) {
        val uriString = pendingImportUri ?: return
        importPreviewState = null
        isImporting = true
        lifecycleScope.launch {
            runCatching { backupService.importFrom(uriString, mode) }
                .onSuccess { result ->
                    val action = if (mode == BackupImportMode.REPLACE) "覆盖" else "合并"
                    showToast("已${action}备份：${result.articleCount} 篇文章，${result.sourceCount} 个 RSS 源")
                }
                .onFailure { throwable ->
                    if (throwable is BackupVersionTooHighException) {
                        versionTooHighState = throwable
                    } else {
                        showToast("导入失败：${throwable.message ?: "未知错误"}")
                    }
                }
            isImporting = false
            pendingImportUri = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchRssPhoneTheme {
                val onBack = { finish() }
                PredictiveBackSurface(onBack = onBack) {
                    DataManagementScreen(
                        onBackClick = onBack,
                        onExportWrss = ::exportWrssBackup,
                        onExportHumanReadable = ::exportHumanReadable,
                        onImportBackup = ::selectBackupForImport,
                        importPreview = importPreviewState,
                        onConfirmImport = ::confirmImport,
                        onDismissImport = { importPreviewState = null; pendingImportUri = null },
                        versionTooHigh = versionTooHighState,
                        onDismissVersionTooHigh = { versionTooHighState = null },
                        isImporting = isImporting,
                        toastMessage = toastMessage,
                        onConsumeToast = { toastMessage = null }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        toastMessage?.let {
            Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            toastMessage = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementScreen(
    onBackClick: () -> Unit,
    onExportWrss: () -> Unit,
    onExportHumanReadable: () -> Unit,
    onImportBackup: () -> Unit,
    importPreview: BackupPreview?,
    onConfirmImport: (BackupImportMode) -> Unit,
    onDismissImport: () -> Unit,
    versionTooHigh: BackupVersionTooHighException?,
    onDismissVersionTooHigh: () -> Unit,
    isImporting: Boolean,
    toastMessage: String?,
    onConsumeToast: () -> Unit
) {
    val context = LocalContext.current

    // 显示 Toast
    androidx.compose.runtime.LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
            onConsumeToast()
        }
    }

    AdaptiveWindowScope(modifier = Modifier.fillMaxSize()) { windowInfo ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("数据管理") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { paddingValues ->
            AdaptiveContentFrame(
                windowInfo = windowInfo,
                mediumMaxWidth = 600.dp,
                expandedMaxWidth = 720.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (isImporting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    // ── 备份导出 ──
                    SectionCard(
                        title = "备份导出",
                        description = "选择备份格式，将数据保存到文件"
                    ) {
                        ExportOptionItem(
                            icon = { Icon(Icons.Default.Archive, contentDescription = null) },
                            title = "专有格式备份",
                            subtitle = "完整备份所有数据（.wrss），包含首次使用日期等元数据",
                            onClick = onExportWrss
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ExportOptionItem(
                            icon = { Icon(Icons.Default.Code, contentDescription = null) },
                            title = "人类可读格式导出",
                            subtitle = "导出为 JSON 文件，方便在其他平台查看",
                            onClick = onExportHumanReadable
                        )
                    }

                    // ── 备份导入 ──
                    SectionCard(
                        title = "备份导入",
                        description = "从 .wrss 备份文件恢复数据"
                    ) {
                        ExportOptionItem(
                            icon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                            title = "导入备份文件",
                            subtitle = "选择 .wrss 文件恢复数据",
                            onClick = onImportBackup
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // 导入确认对话框
    importPreview?.let { preview ->
        ImportConfirmDialog(
            preview = preview,
            onMerge = { onConfirmImport(BackupImportMode.MERGE) },
            onReplace = { onConfirmImport(BackupImportMode.REPLACE) },
            onDismiss = onDismissImport
        )
    }

    // 版本过高对话框
    versionTooHigh?.let { error ->
        VersionTooHighDialog(
            error = error,
            onDismiss = onDismissVersionTooHigh,
            onOpenReleases = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_RELEASES_URL))
                context.startActivity(intent)
            }
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = description,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ExportOptionItem(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ImportConfirmDialog(
    preview: BackupPreview,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Archive, contentDescription = null) },
        title = { Text("导入备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "备份信息",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = "导出时间：${dateFormat.format(Date(preview.exportedAt))}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "数据结构版本：v${preview.dataStructureVersion}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "应用版本：${preview.appVersion.ifBlank { "未知" }}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "RSS 源：${preview.sourceCount} 个　文章：${preview.articleCount} 篇　收藏项：${preview.savedItemCount} 个",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (preview.appMetaCount > 0) {
                    Text(
                        text = "元数据：${preview.appMetaCount} 条",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "覆盖模式将清除本地现有数据后导入；合并模式将保留本地已有的较新数据。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = onMerge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(Icons.Default.Merge, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("合并")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onReplace,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("覆盖")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun VersionTooHighDialog(
    error: BackupVersionTooHighException,
    onDismiss: () -> Unit,
    onOpenReleases: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
        title = { Text("需要升级应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "备份文件的数据结构版本（v${error.backupVersion}）高于当前应用支持的版本（v${error.currentVersion}）。",
                    fontSize = 14.sp
                )
                Text(
                    text = "请升级到最新版本后再导入此备份。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onOpenReleases()
            }) {
                Text("前往下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
