package com.lightningstudio.watchrss.phone.rollout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lightningstudio.watchrss.phone.PhoneCompanionApplication
import com.lightningstudio.watchrss.phone.features.FeatureOverride
import com.lightningstudio.watchrss.phone.features.FeatureRegistry
import com.lightningstudio.watchrss.phone.ui.theme.WatchRssPhoneTheme
import kotlinx.coroutines.launch
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            val next = when (state.override) {
                                null -> FeatureOverride.ON
                                FeatureOverride.ON -> FeatureOverride.OFF
                                FeatureOverride.OFF -> null
                            }
                            val overrideLabel = when (state.override) {
                                null -> "跟随云端"
                                FeatureOverride.ON -> "强制开"
                                FeatureOverride.OFF -> "强制关"
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(feature.key, style = MaterialTheme.typography.titleMedium)
                                    Text(state.status(alreadyEnabled), style = MaterialTheme.typography.bodyLarge)
                                    Text("云端值 ${state.cloudValue}", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { scope.launch { repository.setDebugOverride(feature, next) } }) {
                                        Text("本机覆盖 · $overrideLabel")
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = { scope.launch { repository.clearDebugOverrides() } }) {
                            Text("全部恢复跟随云端")
                        }
                    }
                }
            }
        }
    }
}
