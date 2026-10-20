package com.lightningstudio.watchrss.phone.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.data.db.PhoneRssSourceEntity
import com.lightningstudio.watchrss.phone.data.model.ImportedContentIds

@Composable
fun CloudInventoryPanel(
    service: PhoneCloudSyncService,
    userId: String,
    sources: List<PhoneRssSourceEntity>,
    enabled: Boolean
) {
    val channels = sources.filterNot { ImportedContentIds.isImportedContentUrl(it.url) }
    Text("选择每个频道从云库存同步的范围。设置立即保存，下次同步生效。")
    Text("最近 128 条节省空间；全部最多 8192 条。", style = MaterialTheme.typography.bodySmall)
    if (channels.isEmpty()) Text("暂无 RSS 频道，请先添加订阅。")
    channels.forEach { source ->
        key(userId, source.url) {
            var mode by remember { mutableStateOf(service.rssInventoryMode(source.url)) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(source.title.ifBlank { source.url }, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CloudRssInventoryMode.entries.forEach { choice ->
                            FilterChip(
                                selected = mode == choice,
                                onClick = { service.setRssInventoryMode(source.url, choice); mode = choice },
                                enabled = enabled,
                                label = { Text(if (choice == CloudRssInventoryMode.ALL) "全部" else "最近 128 条") }
                            )
                        }
                    }
                }
            }
        }
    }
}
