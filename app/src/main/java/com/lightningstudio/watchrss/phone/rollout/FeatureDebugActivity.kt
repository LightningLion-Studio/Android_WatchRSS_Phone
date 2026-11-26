package com.lightningstudio.watchrss.phone.rollout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.DateFormat
import java.util.Date

class FeatureDebugActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PhoneCompanionApplication).container
        val repository = container.clientConfig
        setContent {
            WatchRssPhoneTheme {
                repository.changes.collectAsState(initial = null).value
                container.accountRepository.session.collectAsState().value
                container.cloudSyncService.localKeyChanges.collectAsState().value
                val snapshot = repository.debugSnapshot()
                val scope = rememberCoroutineScope()
                val overrideWrites = remember { Mutex() }
                var refreshing by remember { mutableStateOf(false) }
                Scaffold(topBar = {
                    TopAppBar(title = { Text("调试") }, navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    })
                }) { insets ->
                    Column(
                        Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(snapshot.configStatus)
                        val date = snapshot.fetchedAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
                        Text("配置版本 ${snapshot.fetchedAt?.let { snapshot.config.version } ?: "—"} · 拉取时间 ${date ?: "—"}",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(enabled = !refreshing, onClick = {
                            refreshing = true
                            scope.launch {
                                try { repository.refreshNow() } finally { refreshing = false }
                            }
                        }) { Text(if (refreshing) "正在刷新…" else "刷新云控") }
                        FeatureRegistry.entries.zip(snapshot.features).forEach { (feature, state) ->
                            val alreadyEnabled = feature == FeatureRegistry.cloudSyncEntry &&
                                container.cloudSyncService.hasLocalAccountKey()
                            FeatureDebugCard(state, enabled = alreadyEnabled || state.enabled) { override ->
                                scope.launch {
                                    overrideWrites.withLock { repository.setDebugOverride(feature, override) }
                                }
                            }
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                overrideWrites.withLock { repository.clearDebugOverrides() }
                            }
                        }) {
                            Text("全部恢复跟随云端")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FeatureDebugCard(
    state: ClientFeatureDebugState,
    enabled: Boolean,
    onOverrideChange: (FeatureOverride?) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.key, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.status,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (state.override != null) {
                        TextButton(onClick = { onOverrideChange(null) }) {
                            Text("恢复云控", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    onOverrideChange(if (checked) FeatureOverride.ON else FeatureOverride.OFF)
                },
                modifier = Modifier.semantics { contentDescription = state.key }
            )
        }
    }
}
