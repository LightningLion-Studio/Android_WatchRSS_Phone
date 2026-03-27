package com.lightningstudio.watchrss.phone.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.connection.guided.GuidedSessionState
import com.lightningstudio.watchrss.phone.data.db.PhoneSavedItemEntity
import com.lightningstudio.watchrss.phone.data.model.PhoneSavedItemType
import com.lightningstudio.watchrss.phone.viewmodel.MainUiState

@Composable
fun MainScreen(
    uiState: MainUiState,
    onScanQr: () -> Unit,
    onRssUrlChange: (String) -> Unit,
    onSendRss: () -> Unit,
    onSendPureSoundRss: () -> Unit,
    onReceivePureSoundSync: () -> Unit,
    onStartGuidedRemoteInput: () -> Unit,
    onStartGuidedFavorites: () -> Unit,
    onStartGuidedWatchLater: () -> Unit,
    onStopGuidedSession: () -> Unit,
    onSyncFavorites: () -> Unit,
    onSyncWatchLater: () -> Unit,
    onDismissMessage: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "WatchRSS 手机 Companion",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        StatusCard(
            endpointLabel = uiState.endpoint?.displayLabel,
            abilities = uiState.abilities.map { it.name },
            message = uiState.message,
            error = uiState.error,
            onDismissMessage = onDismissMessage
        )

        TransportCard(
            title = "手动 WiFi 连接",
            description = "让手机与手表处于同一 WiFi 或手机热点后，扫描手表上的二维码建立连接。",
            buttonLabel = "扫描手表二维码",
            onClick = onScanQr,
            enabled = !uiState.isBusy
        )

        PureSoundCard(
            title = "纯声波",
            description = "直接通过扬声器和麦克风传输。适合 RSS 输入，或让手机接收手表同步数据。",
            isListening = uiState.isPureSoundListening,
            enabled = !uiState.isBusy,
            onSendRss = onSendPureSoundRss,
            onReceiveSync = onReceivePureSoundSync
        )

        GuidedWifiCard(
            title = "声波引导 WiFi 连接",
            description = "手机先起本地热点和临时会话，再把热点与会话信息通过声波播给手表。",
            session = uiState.guidedSession,
            enabled = !uiState.isBusy,
            onStartRemoteInput = onStartGuidedRemoteInput,
            onStartFavorites = onStartGuidedFavorites,
            onStartWatchLater = onStartGuidedWatchLater,
            onStop = onStopGuidedSession
        )

        if (uiState.endpoint != null) {
            ActionCard(
                title = "从手机输入 RSS",
                enabled = uiState.abilities.any { it.name.contains("RSS") },
                buttonLabel = "发送到手表",
                onClick = onSendRss
            ) {
                OutlinedTextField(
                    value = uiState.rssUrlInput,
                    onValueChange = onRssUrlChange,
                    label = { Text(text = "RSS 地址") },
                    placeholder = { Text(text = "https://example.com/feed.xml") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SyncCard(
                    modifier = Modifier.weight(1f),
                    title = "同步收藏",
                    count = uiState.favorites.size,
                    enabled = uiState.abilities.any { it.name.contains("收藏") },
                    onClick = onSyncFavorites
                )
                SyncCard(
                    modifier = Modifier.weight(1f),
                    title = "同步稍后再看",
                    count = uiState.watchLater.size,
                    enabled = uiState.abilities.any { it.name.contains("稍后") },
                    onClick = onSyncWatchLater
                )
            }
        }

        SavedItemsSection(
            title = "收藏",
            type = PhoneSavedItemType.FAVORITE,
            items = uiState.favorites,
            onOpenLink = { link -> uriHandler.openUri(link) }
        )

        SavedItemsSection(
            title = "稍后再看",
            type = PhoneSavedItemType.WATCH_LATER,
            items = uiState.watchLater,
            onOpenLink = { link -> uriHandler.openUri(link) }
        )
    }
}

@Composable
private fun StatusCard(
    endpointLabel: String?,
    abilities: List<String>,
    message: String?,
    error: String?,
    onDismissMessage: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "当前连接", style = MaterialTheme.typography.titleMedium)
            Text(
                text = endpointLabel ?: "未连接手表",
                style = MaterialTheme.typography.bodyLarge
            )
            if (abilities.isNotEmpty()) {
                Text(
                    text = "可用能力：${abilities.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            message?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onDismissMessage)
                )
            }
            error?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onDismissMessage)
                )
            }
        }
    }
}

@Composable
private fun TransportCard(
    title: String,
    description: String,
    buttonLabel: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            if (buttonLabel != null && onClick != null) {
                Button(onClick = onClick, enabled = enabled) {
                    Text(text = buttonLabel)
                }
            }
        }
    }
}

@Composable
private fun PureSoundCard(
    title: String,
    description: String,
    isListening: Boolean,
    enabled: Boolean,
    onSendRss: () -> Unit,
    onReceiveSync: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onSendRss, enabled = enabled) {
                Text(text = "发送 RSS 到手表")
            }
            Button(onClick = onReceiveSync, enabled = enabled) {
                Text(text = if (isListening) "正在接收…" else "接收手表同步")
            }
        }
    }
}

@Composable
private fun GuidedWifiCard(
    title: String,
    description: String,
    session: GuidedSessionState?,
    enabled: Boolean,
    onStartRemoteInput: () -> Unit,
    onStartFavorites: () -> Unit,
    onStartWatchLater: () -> Unit,
    onStop: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            session?.let {
                Text(
                    text = "当前热点：${it.ssid}\n密码：${it.passphrase}\n会话：${it.ability.displayName}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(onClick = onStartRemoteInput, enabled = enabled) {
                Text(text = "引导手表接收 RSS")
            }
            Button(onClick = onStartFavorites, enabled = enabled) {
                Text(text = "引导同步收藏")
            }
            Button(onClick = onStartWatchLater, enabled = enabled) {
                Text(text = "引导同步稍后再看")
            }
            if (session != null) {
                Button(onClick = onStop) {
                    Text(text = "停止当前会话")
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    enabled: Boolean,
    buttonLabel: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
            Button(
                onClick = onClick,
                enabled = enabled
            ) {
                Text(text = buttonLabel)
            }
        }
    }
}

@Composable
private fun SyncCard(
    modifier: Modifier,
    title: String,
    count: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = "本地 $count 条", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, enabled = enabled) {
                Text(text = "立即同步")
            }
        }
    }
}

@Composable
private fun SavedItemsSection(
    title: String,
    type: PhoneSavedItemType,
    items: List<PhoneSavedItemEntity>,
    onOpenLink: (String) -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "$title (${items.size})",
                style = MaterialTheme.typography.titleMedium
            )
            if (items.isEmpty()) {
                Text(
                    text = "暂无${type.displayName}",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                items.take(20).forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = item.link.isNotBlank()) {
                                if (item.link.isNotBlank()) {
                                    onOpenLink(item.link)
                                }
                            }
                    ) {
                        Text(
                            text = item.title.ifBlank {
                                item.link.ifBlank { "未命名条目" }
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (item.channelTitle.isNotBlank()) {
                            Text(
                                text = item.channelTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (item.summary.isNotBlank()) {
                            Text(
                                text = item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
