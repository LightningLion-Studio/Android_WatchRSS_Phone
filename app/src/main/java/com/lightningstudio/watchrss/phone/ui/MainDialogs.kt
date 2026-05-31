package com.lightningstudio.watchrss.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.connection.bluetooth.PhoneSyncConflictResolution
import com.lightningstudio.watchrss.phone.viewmodel.MainBluetoothDevicePromptUi
import com.lightningstudio.watchrss.phone.viewmodel.MainBluetoothDeviceUi
import com.lightningstudio.watchrss.phone.viewmodel.MainConflictPromptUi
import com.lightningstudio.watchrss.phone.viewmodel.SharedImportPromptKind
import com.lightningstudio.watchrss.phone.viewmodel.SharedImportPromptUi

@Composable
fun AddRssSourceDialog(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 RSS 源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "输入 RSS 订阅地址",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = onUrlChange,
                    placeholder = { Text("https://example.com/feed.xml") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAdd,
                enabled = urlInput.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun AddArticleDialog(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加独立文章") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "输入文章网页地址",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = onUrlChange,
                    placeholder = { Text("https://example.com/article.html") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onImport,
                enabled = urlInput.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun DeleteConflictDialog(
    prompt: MainConflictPromptUi,
    onChooseResolution: (PhoneSyncConflictResolution) -> Unit,
    onShowManualOptions: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(if (prompt.manual) "保留/删除" else "双端内容有冲突，请选择处理方式")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val countText = "检测到 ${prompt.conflicts.size} 篇内容一端已删除，另一端仍保留。"
                Text(text = countText, style = MaterialTheme.typography.bodyMedium)
                prompt.conflicts.firstOrNull()?.let { conflict ->
                    Text(
                        text = conflict.title.ifBlank { conflict.url },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (prompt.manual) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.KEEP_WATCH) }
                    ) {
                        Text("保留手表版本")
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.KEEP_PHONE) }
                    ) {
                        Text("保留手机版本")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.DELETE_CONTENT) }
                    ) {
                        Text("删除")
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.KEEP_LATEST) }
                    ) {
                        Text("保留最新操作")
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.MERGE_CONTENT) }
                    ) {
                        Text("合并内容")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseResolution(PhoneSyncConflictResolution.DELETE_CONTENT) }
                    ) {
                        Text("删除内容")
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onShowManualOptions
                    ) {
                        Text("手动")
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun BluetoothDeviceChooserDialog(
    prompt: MainBluetoothDevicePromptUi,
    onChooseDevice: (MainBluetoothDeviceUi) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择同步手表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "检测到多块已打开 腕上RSS 的手表，请选择本次同步目标。",
                    style = MaterialTheme.typography.bodyMedium
                )
                prompt.devices.forEach { device ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onChooseDevice(device) }
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = device.name.ifBlank { "未知手表" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = device.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun SharedImportDialog(
    prompt: SharedImportPromptUi,
    onImportLinkAsArticle: (String) -> Unit,
    onImportLinkAsRss: (String) -> Unit,
    onConfirmFileImport: (SharedImportPromptUi) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (prompt.kind) {
                    SharedImportPromptKind.LINK -> "导入链接"
                    SharedImportPromptKind.FILE -> "导入文件"
                }
            )
        },
        text = {
            when (prompt.kind) {
                SharedImportPromptKind.LINK -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "请选择导入方式",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = prompt.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SharedImportPromptKind.FILE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = prompt.fileName.ifBlank { "未命名文件" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    prompt.mimeType?.takeIf { it.isNotBlank() }?.let { mimeType ->
                        Text(
                            text = mimeType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (prompt.kind) {
                    SharedImportPromptKind.LINK -> {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onImportLinkAsRss(prompt.url) }
                        ) {
                            Text("添加 RSS 源")
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onImportLinkAsArticle(prompt.url) }
                        ) {
                            Text("添加独立文章")
                        }
                    }
                    SharedImportPromptKind.FILE -> {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onConfirmFileImport(prompt) }
                        ) {
                            Text("导入")
                        }
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss
                ) {
                    Text("取消")
                }
            }
        }
    )
}
